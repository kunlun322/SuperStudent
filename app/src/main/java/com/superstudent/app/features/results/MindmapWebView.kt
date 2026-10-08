package com.superstudent.app.features.results

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.superstudent.core.model.MINDMAP_VISUAL_CONTRACT_VERSION
import java.io.ByteArrayInputStream

/**
 * Offline-only renderer for mindmap.html (FR-07).
 *
 * The threat model is a remote artifact rendered inside the app: the HTML is loaded from a string
 * with a null base URL, the network is unreachable, the file system is unreachable, and no
 * JavaScript bridge is ever registered, so page script has no channel back into the app. The page's
 * own CSP (mandated by the generation prompt) is a second layer, not the only one.
 *
 * ZLQ-145 (option 2): this renderer is tried on **every** supported API level. Nothing here reads
 * `Build.VERSION.SDK_INT` to choose a render path — the SDK level only appears in failure
 * diagnostics. Whether the HTML is kept is decided by [HtmlHandshakeSession]'s positive handshake,
 * and [MindmapRenderController] owns the fallback to `mindmap.png` and then to the native structure tree.
 *
 * The handshake reports readiness through the standard `evaluateJavascript` result channel. That is
 * deliberately not a bridge: no JS interface object is registered, there is no named entry point the
 * page can call into, and the sandbox settings below are unchanged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MindmapWebView(
    html: String,
    expectation: MindmapExpectation,
    onReady: () -> Unit,
    onRenderFailure: (MindmapRenderFailure) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The callbacks are read through State so a recomposition that rebuilds them cannot leave the
    // session holding a lambda from an earlier frame.
    val ready by rememberUpdatedState(onReady)
    val failure by rememberUpdatedState(onRenderFailure)
    // One session — and therefore one WebView and one `loadDataWithBaseURL` — per document. A new
    // HTML builds a new session and the old one is disposed with its pending probes and callbacks.
    val session = remember(html, expectation) {
        HtmlHandshakeSession(html, expectation, { ready() }, { failure(it) })
    }

    AndroidView(
        modifier = modifier,
        factory = { session.createView(context) },
        // Recomposition lands here every frame the pane is on screen; `ensureLoaded` is what keeps
        // that from reloading the document and restarting a handshake already in flight.
        update = { session.ensureLoaded(it) },
        onRelease = { session.dispose() },
    )
}

/**
 * Drives one document from `loadDataWithBaseURL` to either `onReady` or a single `onRenderFailure`.
 *
 * The handshake is *positive*: readiness is asserted only once all four conditions in
 * [MindmapHandshakeSignal] hold. Screenshot or "white pixel ratio" detection is deliberately not
 * used — a transparent background, a theme and legitimate whitespace all make it unreliable. The residual
 * case where every probe passes but the system compositor still paints nothing is covered by the
 * operator's static-image entry in [MindmapPane], and by the native structure tree when there is no
 * PNG.
 */
private class HtmlHandshakeSession(
    private val html: String,
    private val expectation: MindmapExpectation,
    private val onReady: () -> Unit,
    private val onRenderFailure: (MindmapRenderFailure) -> Unit,
    private val log: (String) -> Unit = { android.util.Log.w(TAG, it) },
) {

    val client: WebViewClient = OfflineOnlyWebViewClient(this)

    private val handshake = MindmapHandshake(expectation.expectedSignals)

    private var view: WebView? = null
    private var loaded = false
    private var settled = false
    private var destroyed = false
    private var baselineNodes = -1
    private var baselineEdges = -1
    private var ariaBefore: String? = null
    private var settlePending: Runnable? = null
    private var geometryRetries = 0

    /**
     * Creates and configures the WebView, hosted in a plain [FrameLayout]. A device with no usable
     * provider throws here, and that is a runtime failure of the HTML path — not a reason to have
     * predicted it from the SDK level.
     *
     * The container is the fix for the blank card, not sugar: Compose measures an `AndroidView`
     * child on Compose's own measure path, and a WebView measured that way resolves CSS viewport
     * units to zero, so the generated CSS's `max-height:100vh` clamps the SVG's `height:auto` to 0
     * and only the page background paints (ZLQ-147). A ViewGroup parent keeps the WebView on the
     * platform measure path, where `100vh` equals the card's real height.
     */
    fun createView(context: Context): View {
        val created = try {
            WebView(context).apply { configure() }
        } catch (e: RuntimeException) {
            // RuntimeException only: an OutOfMemoryError or other process-level error must propagate.
            fail(MindmapRenderFailure.INIT_FAILED, "webview-create:${e.javaClass.simpleName}")
            null
        }
        view = created
        return FrameLayout(context).apply {
            if (created != null) {
                addView(
                    created,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    /**
     * The single load for this document. Every later recomposition is a no-op.
     *
     * [candidate] is the container [createView] returned, so the WebView has to be found inside it.
     * Resolving it from the host rather than from the field this session already holds is
     * deliberate: a factory that ever hands back a container without a WebView in it must fail
     * loudly instead of silently skipping the load and leaving the card on its placeholder until
     * the deadline (ZLQ-151).
     */
    fun ensureLoaded(candidate: View) {
        if (loaded || settled) return
        val webView = candidate.findWebView()
            ?: return fail(MindmapRenderFailure.INIT_FAILED, "webview-not-hosted")
        loaded = true
        try {
            webView.webViewClient = client
            webView.webChromeClient = SilentChromeClient()
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } catch (e: RuntimeException) {
            fail(MindmapRenderFailure.INIT_FAILED, "webview-load:${e.javaClass.simpleName}")
        }
    }

    fun dispose() {
        cancelSettle()
        settled = true
        val webView = view ?: return
        view = null
        if (destroyed) return
        destroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }

    // ---------- WebViewClient entry points ----------

    fun onPageLoaded() {
        if (settled) return
        // Condition 1: the page finished loading.
        if (complete(MindmapHandshakeSignal.PAGE_FINISHED)) return
        probe(ProbeScript.domProbe(expectation.nodeCount, expectation.edgeCount), ::onDomProbe)
    }

    fun onMainFrameError(code: String) = fail(MindmapRenderFailure.MAIN_FRAME_ERROR, code)

    fun onRendererGone() {
        // The renderer is gone and its WebView is unusable; destroy it before falling back, so nothing leaks.
        fail(MindmapRenderFailure.RENDER_PROCESS_GONE, "render-process-gone")
        dispose()
    }

    // ---------- the handshake ----------

    /** Condition 2: unique v2 SVG, laid out and visible, visible node/edge counts match the manifest. */
    private fun onDomProbe(reply: MindmapProbeReply?) {
        if (reply == null) return fail(MindmapRenderFailure.PROBE_FAILED, "dom-probe:no-reply")
        if (!reply.ok) {
            // A local document can finish loading before the platform measure pass has given the
            // WebView a real size, and `getBoundingClientRect` on an unlaid-out SVG reads zero —
            // which the visibility check rejects. Re-measure a bounded number of times inside the
            // existing 8s budget rather than degrading a map that is about to paint. Only geometry
            // retries: `svg-count`, `node-count` and friends are contract verdicts and must not be
            // softened by waiting (ZLQ-151, absorbing ZLQ-147's settle window into this one chain).
            if (reply.code == DOM_GEOMETRY_CODE && geometryRetries < DOM_GEOMETRY_RETRIES) {
                geometryRetries += 1
                return scheduleSettle(DOM_GEOMETRY_SETTLE_MS) {
                    probe(ProbeScript.domProbe(expectation.nodeCount, expectation.edgeCount), ::onDomProbe)
                }
            }
            return fail(MindmapRenderFailure.PROBE_FAILED, "dom-probe:${reply.code}")
        }
        if (!mindmapCountsMatchManifest(expectation, reply)) {
            return fail(MindmapRenderFailure.PROBE_FAILED, "dom-probe:count-mismatch")
        }
        baselineNodes = reply.nodes
        baselineEdges = reply.edges
        if (expectation.hasNonLeafNodes && reply.toggle != 1) {
            return fail(MindmapRenderFailure.PROBE_FAILED, "dom-probe:toggle-missing")
        }
        if (complete(MindmapHandshakeSignal.DOM_PROBE)) return
        if (expectation.hasNonLeafNodes) {
            probe(ProbeScript.toggleClick(), ::onToggleClicked)
        } else {
            requestVisualState()
        }
    }

    /**
     * Condition 3, first half. The click may hide *or* reveal depending on the document's initial
     * `aria-expanded`, so the requirement is that the counts move in the direction the new
     * `aria-expanded` implies — not that they always shrink.
     */
    private fun onToggleClicked(reply: MindmapProbeReply?) {
        if (reply == null) return fail(MindmapRenderFailure.PROBE_FAILED, "toggle-click:no-reply")
        if (!reply.ok) return fail(MindmapRenderFailure.PROBE_FAILED, "toggle-click:${reply.code}")
        val before = reply.before
        val after = reply.after
        if (before == null || after == null || before == after) {
            return fail(MindmapRenderFailure.PROBE_FAILED, "toggle-click:aria-unchanged")
        }
        ariaBefore = before
        if (movedAsDeclared(after, reply.nodes, reply.edges)) return restoreToggle()
        // A transition-driven hide is not visible in the same task, so measure once more after it
        // has had a chance to settle before calling the interaction broken.
        scheduleSettle {
            probe(ProbeScript.measure()) { measured ->
                if (measured != null && measured.ok && movedAsDeclared(after, measured.nodes, measured.edges)) {
                    restoreToggle()
                } else {
                    fail(MindmapRenderFailure.PROBE_FAILED, "toggle-click:descendants-unchanged")
                }
            }
        }
    }

    /** Condition 3, second half: the restore click must put `aria-expanded` and the counts back. */
    private fun restoreToggle() {
        probe(ProbeScript.toggleRestore()) { reply ->
            if (reply == null) return@probe fail(MindmapRenderFailure.PROBE_FAILED, "toggle-restore:no-reply")
            if (!reply.ok) return@probe fail(MindmapRenderFailure.PROBE_FAILED, "toggle-restore:${reply.code}")
            if (reply.after != ariaBefore) {
                return@probe fail(MindmapRenderFailure.PROBE_FAILED, "toggle-restore:aria-not-restored")
            }
            if (isAtBaseline(reply.nodes, reply.edges)) return@probe onToggleRestored()
            scheduleSettle {
                probe(ProbeScript.measure()) { measured ->
                    if (measured != null && measured.ok && measured.after == ariaBefore &&
                        isAtBaseline(measured.nodes, measured.edges)
                    ) {
                        onToggleRestored()
                    } else {
                        fail(MindmapRenderFailure.PROBE_FAILED, "toggle-restore:counts-not-restored")
                    }
                }
            }
        }
    }

    private fun onToggleRestored() {
        if (complete(MindmapHandshakeSignal.TOGGLE_PROBE)) return
        requestVisualState()
    }

    /** Condition 4: the visual-state callback arrives after the probe passed. */
    private fun requestVisualState() {
        probe(ProbeScript.visualState()) { reply ->
            if (reply == null) return@probe fail(MindmapRenderFailure.PROBE_FAILED, "visual-state:no-reply")
            val matches = reply.contractVersion == MINDMAP_VISUAL_CONTRACT_VERSION &&
                mindmapCountsMatchManifest(expectation, reply)
            if (!reply.ok || !matches) {
                return@probe fail(MindmapRenderFailure.PROBE_FAILED, "visual-state:${reply.code ?: "mismatch"}")
            }
            complete(MindmapHandshakeSignal.VISUAL_STATE)
        }
    }

    /** Records a satisfied condition; true when that was the last one the document needed. */
    private fun complete(signal: MindmapHandshakeSignal): Boolean {
        if (!handshake.satisfy(signal)) return false
        settleReady()
        return true
    }

    private fun movedAsDeclared(ariaExpanded: String?, nodes: Int, edges: Int): Boolean =
        mindmapToggleMovedAsDeclared(baselineNodes, baselineEdges, ariaExpanded, nodes, edges)

    private fun isAtBaseline(nodes: Int, edges: Int) =
        mindmapToggleRestoredToBaseline(baselineNodes, baselineEdges, nodes, edges)

    private fun settleReady() {
        if (settled) return
        settled = true
        cancelSettle()
        onReady()
    }

    /** One fallback per document: whoever arrives first wins, everything later is dropped. */
    private fun fail(reason: MindmapRenderFailure, code: String) {
        if (settled) return
        settled = true
        cancelSettle()
        log(diagnostics(code))
        onRenderFailure(reason)
    }

    private fun probe(script: String, onReply: (MindmapProbeReply?) -> Unit) {
        if (settled) return
        val webView = view ?: return fail(MindmapRenderFailure.INIT_FAILED, "probe:no-webview")
        try {
            webView.evaluateJavascript(script) { raw ->
                if (settled) return@evaluateJavascript
                onReply(MindmapProbeReply.parse(raw))
            }
        } catch (e: RuntimeException) {
            fail(MindmapRenderFailure.INIT_FAILED, "probe-evaluate:${e.javaClass.simpleName}")
        }
    }

    private fun scheduleSettle(delayMs: Long = TOGGLE_SETTLE_MS, block: () -> Unit) {
        cancelSettle()
        val webView = view ?: return
        val runnable = Runnable {
            settlePending = null
            if (!settled) block()
        }
        settlePending = runnable
        webView.postDelayed(runnable, delayMs)
    }

    private fun cancelSettle() {
        settlePending?.let { runnable -> view?.removeCallbacks(runnable) }
        settlePending = null
    }

    /**
     * Failure diagnostics and nothing else. The HTML, the study content and citation text are never
     * logged; `SDK_INT` and the provider appear here purely as diagnostics, not as a render gate.
     */
    private fun diagnostics(code: String): String {
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return "mindmap render fallback code=$code sdk=${Build.VERSION.SDK_INT} " +
            "provider=${provider?.packageName ?: "-"}/${provider?.versionName ?: "-"}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configure() {
        settings.apply {
            // Script may run so the generated map is interactive, but it is fully sandboxed:
            // no bridge, no file access, no network, no storage, no windows, no geolocation.
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            defaultTextEncodingName = "utf-8"
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // TEXT_AUTOSIZING reflows a document as a text column and would rescale the SVG's
            // own viewport, so the geometry the contract was validated against is not what is
            // drawn. The map is a fixed-size viewBox scaled by the student, not reflowed text.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
        }
        setBackgroundColor(Color.TRANSPARENT)
    }

    companion object {
        private const val TAG = "MindmapRender"

        /** One re-measure window for a transition-driven show/hide, well inside the 8s budget. */
        private const val TOGGLE_SETTLE_MS = 150L

        /**
         * The probe's code for "the SVG root exists but has no laid-out box", and how long to wait
         * before re-measuring it. Three windows of 400 ms cover the 1.2 s settle ZLQ-147 measured
         * empirically, and stay far inside [MINDMAP_HTML_RENDER_TIMEOUT_MS].
         */
        private const val DOM_GEOMETRY_CODE = "svg-invisible"
        private const val DOM_GEOMETRY_SETTLE_MS = 400L
        private const val DOM_GEOMETRY_RETRIES = 3
    }
}

/**
 * Finds the WebView inside the host [createView] returned. Written as an explicit index walk rather
 * than a recursive Kotlin helper so it stays readable against the ES5 discipline the probe follows.
 */
private fun View.findWebView(): WebView? {
    if (this is WebView) return this
    if (this !is ViewGroup) return null
    for (i in 0 until childCount) {
        getChildAt(i)?.findWebView()?.let { return it }
    }
    return null
}

/** Denies every sub-resource that is not an in-page data/about/blob URL. */
private class OfflineOnlyWebViewClient(
    private val session: HtmlHandshakeSession,
) : WebViewClient() {

    companion object {
        private val ALLOWED_SCHEMES = setOf("data", "about", "blob")
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val scheme = request?.url?.scheme?.lowercase() ?: return blank()
        return if (scheme in ALLOWED_SCHEMES) null else blank()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun onPageFinished(view: WebView?, url: String?) {
        session.onPageLoaded()
    }

    /**
     * Only the main document failing is a render failure. The offline policy above answers every
     * blocked sub-resource with an empty response, and those requests also surface here — treating
     * them as failures would degrade a page that is rendering perfectly.
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame != true) return
        session.onMainFrameError("main-frame-error:${error?.errorCode ?: "-"}")
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        session.onRendererGone()
        // true: this WebView is being torn down, so the host application must not be killed for it.
        return true
    }

    private fun blank() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/** Swallows script-driven dialogs and permission prompts instead of surfacing them. */
private class SilentChromeClient : WebChromeClient() {
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
        result?.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: android.webkit.JsResult?,
    ): Boolean {
        result?.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: android.webkit.JsPromptResult?,
    ): Boolean {
        result?.cancel()
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }
}

/**
 * The injected probe, in ES5.
 *
 * ES5 on purpose: the ruling's whole point is that the WebView *provider* version is not predictable
 * from the API level, so a provider too old for ES2015 must still be able to parse the probe and
 * report a failure rather than a syntax error. For the same reason every construct below is ES5 — no
 * block-scoped bindings, no arrow function, no template literal, no array helper from a NodeList and
 * no ancestor selector; a `NodeList` is walked by index and the SVG root reached by `parentNode`.
 *
 * Only integers are interpolated into the script. It never receives a label, an explanation or a
 * citation, so nothing that could end up in a log or a crash report is page content.
 */
private object ProbeScript {

    private const val HELPERS = """
function ssVis(el){
  if(!el){return false;}
  if(el.getClientRects && el.getClientRects().length===0){return false;}
  var r=el.getBoundingClientRect();
  var edge=(el.getAttribute('class')||'').indexOf('mindmap-edge')>=0;
  if(edge){if(!(r.width>0 || r.height>0)){return false;}}else{if(!(r.width>0 && r.height>0)){return false;}}
  var cs=window.getComputedStyle(el);
  if(!cs){return false;}
  if(cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse'){return false;}
  var op=parseFloat(cs.opacity);
  if(!isNaN(op)&&op<=0.01){return false;}
  return true;
}
function ssCount(list){var c=0;for(var i=0;i<list.length;i++){if(ssVis(list[i])){c++;}}return c;}
function ssSvg(){
  var s=document.querySelectorAll('svg#ss-mindmap[data-contract-version="2"]');
  return s.length===1?s[0]:null;
}
function ssMeasure(){
  var svg=ssSvg();
  if(!svg){return {ok:false,code:'svg-missing',nodes:-1,edges:-1};}
  return {ok:true,code:'measured',
    nodes:ssCount(svg.querySelectorAll('.mindmap-node[data-node-id]')),
    edges:ssCount(svg.querySelectorAll('.mindmap-edge[data-from][data-to]'))};
}
function ssToggle(){
  var svg=ssSvg();
  if(!svg){return null;}
  var t=svg.querySelectorAll('button[data-action=toggle][aria-expanded]');
  return t.length>0?t[0]:null;
}
function ssExpanded(){var b=ssToggle();return b?b.getAttribute('aria-expanded'):null;}
"""

    fun domProbe(expectedNodes: Int, expectedEdges: Int): String = wrap(
        """
  try{
    if(document.readyState!=='complete'){return {ok:false,code:'ready-state'};}
    var v2=document.querySelectorAll('svg#ss-mindmap[data-contract-version="2"]');
    if(v2.length!==1){return {ok:false,code:'svg-count'};}
    if(!ssVis(v2[0])){return {ok:false,code:'svg-invisible'};}
    var m=ssMeasure();
    if(m.nodes!==${expectedNodes}){return {ok:false,code:'node-count',nodes:m.nodes,edges:m.edges};}
    if(m.edges!==${expectedEdges}){return {ok:false,code:'edge-count',nodes:m.nodes,edges:m.edges};}
    return {ok:true,code:'dom-ok',nodes:m.nodes,edges:m.edges,toggle:ssToggle()?1:0};
  }catch(e){return {ok:false,code:'dom-exception'};}
"""
    )

    fun toggleClick(): String = wrap(
        """
  try{
    var b=ssToggle();
    if(!b){return {ok:false,code:'toggle-missing'};}
    var before=b.getAttribute('aria-expanded');
    b.click();
    var m=ssMeasure();
    return {ok:true,code:'clicked',before:before,after:ssExpanded(),nodes:m.nodes,edges:m.edges};
  }catch(e){return {ok:false,code:'toggle-exception'};}
"""
    )

    fun toggleRestore(): String = wrap(
        """
  try{
    var b=ssToggle();
    if(!b){return {ok:false,code:'toggle-missing'};}
    var before=b.getAttribute('aria-expanded');
    b.click();
    var m=ssMeasure();
    return {ok:true,code:'restored',before:before,after:ssExpanded(),nodes:m.nodes,edges:m.edges};
  }catch(e){return {ok:false,code:'toggle-exception'};}
"""
    )

    /** Re-measures without touching the page, for the one settle retry after a click. */
    fun measure(): String = wrap(
        """
  try{
    var m=ssMeasure();
    return {ok:m.ok,code:m.code,nodes:m.nodes,edges:m.edges,after:ssExpanded()};
  }catch(e){return {ok:false,code:'measure-exception'};}
"""
    )

    fun visualState(): String = wrap(
        """
  try{
    var svg=ssSvg();
    if(!svg){return {ok:false,code:'visual-state-svg'};}
    var cv=parseInt(svg.getAttribute('data-contract-version'),10);
    var m=ssMeasure();
    return {ok:true,code:'visual-state',contractVersion:isNaN(cv)?-1:cv,nodes:m.nodes,edges:m.edges};
  }catch(e){return {ok:false,code:'visual-state-exception'};}
"""
    )

    private fun wrap(body: String) = "(function(){$HELPERS$body})()"
}

