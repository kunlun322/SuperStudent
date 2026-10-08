package com.superstudent.app.features.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZLQ-140 D5/D6 cover a WebView and a Compose layout, and ZLQ-146 replaces D6 with a runtime
 * fallback. This repo has no Robolectric or instrumented environment to execute either, so the
 * settings and the wiring are pinned against the shipped source. Real click-to-collapse behaviour
 * and real painting still need the instrumented pass — this file only proves the code that would
 * carry them is configured as designed. The decision logic itself is executed on the JVM in
 * [MindmapRenderStateTest].
 */
class MindmapRenderingContractTest {

    private fun appSource(relative: String): String {
        // Gradle runs unit tests with the module directory as cwd, but accept the repo root too.
        val file = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源文件 $relative（当前目录 ${File(".").absolutePath}）")
        return file.readText()
    }

    private val resultsPackage = "com/superstudent/app/features/results/"
    private val webView = appSource("${resultsPackage}MindmapWebView.kt")
    private val pane = appSource("${resultsPackage}MindmapPane.kt")
    private val renderState = appSource("${resultsPackage}MindmapRenderState.kt")

    // ---------------------------------------------------------------- ZLQ-145 option 2: no SDK gate

    @Test
    fun `the android version no longer preselects the render path`() {
        // The whole point of the ruling: an OS version is not a reliable proxy for WebView
        // capability, so it must not appear in the pane at all and must not choose a path anywhere.
        assertFalse(pane.contains("usePngFirst"))
        assertFalse(pane.contains("android.os.Build"))
        assertFalse(pane.contains("Build.VERSION"))
        assertFalse(pane.contains("SDK_INT"))
        assertFalse(renderState.contains("SDK_INT"))
        assertFalse(renderState.contains("android.os.Build"))
    }

    @Test
    fun `the sdk level survives only as failure diagnostics`() {
        assertTrue(webView.contains("sdk=\${Build.VERSION.SDK_INT}"))
        assertTrue(webView.contains("WebView.getCurrentWebViewPackage()"))
        assertTrue(webView.contains("provider?.packageName"))
        assertTrue(webView.contains("provider?.versionName"))
        // Diagnostics only: the level is read inside `diagnostics(...)`, never to pick a branch.
        assertFalse(webView.contains("if (Build.VERSION"))
        assertFalse(webView.contains("SDK_INT >="))
        assertFalse(webView.contains("SDK_INT <"))
    }

    @Test
    fun `the order is html then png then the structure tree on every sdk`() {
        // The tree is unconditional, which is what keeps AC-02 alive when neither artifact renders.
        assertTrue(pane.contains("is MindmapRenderState.Fallback -> if (png != null)"))
        assertTrue(pane.contains("MindmapPng(png)"))
        assertTrue(pane.contains("DegradedNotice(current.reason)"))
        assertTrue(pane.contains("暂无图形版本，可继续使用下方结构树"))
        assertTrue(pane.contains("""testTag("mindmap_tree")"""))
    }

    // ---------------------------------------------------------------- the runtime probe

    @Test
    fun `the webview reports readiness and failure through its two callbacks`() {
        assertTrue(webView.contains("onReady: () -> Unit"))
        assertTrue(webView.contains("onRenderFailure: (MindmapRenderFailure) -> Unit"))
        assertTrue(pane.contains("onReady = { controller.onHtmlReady(generation) }"))
        assertTrue(pane.contains("onRenderFailure = { controller.onFailure(generation, it) }"))
    }

    @Test
    fun `the page finishing is only the first of four conditions`() {
        assertTrue(webView.contains("override fun onPageFinished(view: WebView?, url: String?)"))
        assertTrue(webView.contains("session.onPageLoaded()"))
        assertTrue(webView.contains("MindmapHandshakeSignal.PAGE_FINISHED"))
        assertTrue(renderState.contains("PAGE_FINISHED, DOM_PROBE, TOGGLE_PROBE, VISUAL_STATE"))
    }

    @Test
    fun `the dom probe checks the v2 contract, visibility and the declared counts`() {
        assertTrue(webView.contains("document.readyState!=='complete'"))
        assertTrue(webView.contains("svg#ss-mindmap[data-contract-version=\"2\"]"))
        assertTrue(webView.contains(".mindmap-node[data-node-id]"))
        assertTrue(webView.contains(".mindmap-edge[data-from][data-to]"))
        assertTrue(webView.contains("getBoundingClientRect()"))
        assertTrue(webView.contains("getComputedStyle(el)"))
        // Zero size and hidden are both "not drawn", so both are failures.
        assertTrue(webView.contains("if(!(r.width>0 && r.height>0)){return false;}"))
        assertTrue(webView.contains("cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse'"))
        assertTrue(webView.contains("code:'node-count'"))
        assertTrue(webView.contains("code:'edge-count'"))
        assertTrue(webView.contains("code:'svg-invisible'"))
        assertTrue(webView.contains("code:'svg-count'"))
        assertTrue(webView.contains("if (!mindmapCountsMatchManifest(expectation, reply))"))
        assertTrue(renderState.contains("fun mindmapCountsMatchManifest("))
    }

    @Test
    fun `the probe stays es5 so an old webview provider can still report a failure`() {
        // The provider version is exactly what the ruling says cannot be predicted from the API
        // level, so the probe must parse on a provider too old for ES2015.
        assertFalse(webView.contains("Array.from"))
        assertFalse(webView.contains(".closest("))
        assertTrue(webView.contains("for(var i=0;i<list.length;i++)"))
        assertTrue(webView.contains("private fun wrap(body: String) = \"(function(){\$HELPERS\$body})()\""))
    }

    @Test
    fun `non leaf nodes must collapse and restore before the map is revealed`() {
        assertTrue(webView.contains("button[data-action=toggle][aria-expanded]"))
        assertTrue(webView.contains("b.click()"))
        assertTrue(webView.contains("aria-expanded"))
        assertTrue(webView.contains("toggle-click:aria-unchanged"))
        assertTrue(webView.contains("toggle-click:descendants-unchanged"))
        assertTrue(webView.contains("toggle-restore:aria-not-restored"))
        assertTrue(webView.contains("toggle-restore:counts-not-restored"))
        assertTrue(webView.contains("if (expectation.hasNonLeafNodes)"))
        // The verdict arithmetic lives in the pure layer so MindmapRenderStateTest can execute it.
        assertTrue(webView.contains("mindmapToggleMovedAsDeclared(baselineNodes, baselineEdges, ariaExpanded, nodes, edges)"))
        assertTrue(webView.contains("mindmapToggleRestoredToBaseline(baselineNodes, baselineEdges, nodes, edges)"))
        assertTrue(renderState.contains("fun mindmapToggleMovedAsDeclared("))
        assertTrue(renderState.contains("fun mindmapToggleRestoredToBaseline("))
        // The click happens while the placeholder still covers the view.
        assertTrue(pane.contains("if (current is MindmapRenderState.LoadingHtml) RenderPlaceholder()"))
    }

    @Test
    fun `the visual state callback is the last condition and is cross checked`() {
        assertTrue(webView.contains("fun visualState(): String"))
        assertTrue(webView.contains("MindmapHandshakeSignal.VISUAL_STATE"))
        assertTrue(webView.contains("reply.contractVersion == MINDMAP_VISUAL_CONTRACT_VERSION"))
        assertTrue(webView.contains("import com.superstudent.core.model.MINDMAP_VISUAL_CONTRACT_VERSION"))
    }

    @Test
    fun `no pixel or screenshot heuristic decides the render path`() {
        // Transparent backgrounds, themes and legitimate whitespace make a "white pixel ratio"
        // unreliable, so the ruling forbids it outright.
        listOf(webView, pane, renderState).forEach { source ->
            assertFalse(source.contains("PixelCopy"))
            assertFalse(source.contains("capturePicture"))
            assertFalse(source.contains("drawToBitmap"))
            assertFalse(source.contains("drawingCache"))
            assertFalse(source.lowercase().contains("whitepixel"))
            assertFalse(source.contains("白色像素"))
        }
    }

    // ---------------------------------------------------------------- failure wiring

    @Test
    fun `only the main document failing degrades, never a blocked sub resource`() {
        assertTrue(webView.contains("if (request?.isForMainFrame != true) return"))
        assertTrue(webView.contains("session.onMainFrameError("))
        assertTrue(webView.contains("MindmapRenderFailure.MAIN_FRAME_ERROR"))
        // The offline policy is unchanged: everything that is not an in-page URL gets an empty body.
        assertTrue(webView.contains("private val ALLOWED_SCHEMES = setOf(\"data\", \"about\", \"blob\")"))
        assertTrue(webView.contains("override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true"))
    }

    @Test
    fun `a dead renderer degrades and its webview is destroyed`() {
        assertTrue(webView.contains("override fun onRenderProcessGone("))
        assertTrue(webView.contains("RenderProcessGoneDetail"))
        assertTrue(webView.contains("session.onRendererGone()"))
        assertTrue(webView.contains("MindmapRenderFailure.RENDER_PROCESS_GONE"))
        assertTrue(webView.contains("webView.destroy()"))
    }

    @Test
    fun `runtime exceptions degrade but process level errors propagate`() {
        assertTrue(webView.contains("catch (e: RuntimeException)"))
        assertTrue(webView.contains("MindmapRenderFailure.INIT_FAILED"))
        assertFalse(webView.contains("catch (e: Throwable)"))
        assertFalse(webView.contains("catch (e: Error)"))
        assertTrue(webView.contains("process-level error must propagate"))
    }

    @Test
    fun `the deadline is eight seconds from load start and is cancelled by success or release`() {
        assertTrue(renderState.contains("const val MINDMAP_HTML_RENDER_TIMEOUT_MS = 8_000L"))
        assertTrue(renderState.contains("fun onTick(now: Long = clock())"))
        assertTrue(renderState.contains("if (!deadlineArmed) return"))
        assertTrue(renderState.contains("if (now - loadStartedAt < MINDMAP_HTML_RENDER_TIMEOUT_MS) return"))
        assertTrue(renderState.contains("MindmapRenderFailure.RENDER_TIMEOUT"))
        assertTrue(pane.contains("delay(MINDMAP_HTML_RENDER_TIMEOUT_MS)"))
        assertTrue(pane.contains("LaunchedEffect(generation)"))
        assertTrue(pane.contains("controller.release()"))
    }

    @Test
    fun `failure logs carry the code and the provider, never the study content`() {
        assertTrue(webView.contains("private fun diagnostics(code: String): String"))
        assertTrue(webView.contains("\"mindmap render fallback code=\$code"))
        assertTrue(webView.contains("log(diagnostics(code))"))
        // The HTML, a label, an explanation and a citation must not be reachable from the log call.
        assertFalse(webView.contains("Log.w(TAG, html"))
        assertFalse(webView.contains("log(html"))
        assertFalse(webView.contains("\$html"))
        // The only values interpolated into the probe are the two declared counts.
        assertTrue(webView.contains("Only integers are interpolated into the script"))
        assertTrue(webView.contains("m.nodes!==\${expectedNodes}"))
        assertTrue(webView.contains("m.edges!==\${expectedEdges}"))
    }

    // ---------------------------------------------------------------- single load, stale callbacks

    @Test
    fun `one document is loaded once, no matter how often compose recomposes`() {
        assertTrue(webView.contains("if (loaded || settled) return"))
        assertTrue(webView.contains("remember(html, expectation)"))
        assertTrue(webView.contains("webView.loadDataWithBaseURL(null, html, \"text/html\", \"utf-8\", null)"))
        // The pre-ZLQ-146 shape reloaded on every recomposition; it must not come back.
        assertFalse(webView.contains("update = { it.loadDataWithBaseURL"))
        assertTrue(webView.contains("update = { session.ensureLoaded(it) }"))
    }

    @Test
    fun `the generation token is what makes an old callback inert`() {
        assertTrue(pane.contains("var generation by remember(html) { mutableStateOf(controller.bind(html)) }"))
        assertTrue(pane.contains("key(generation)"))
        assertTrue(pane.contains("controller.onHtmlReady(generation)"))
        assertTrue(pane.contains("controller.onFailure(generation, it)"))
        assertTrue(renderState.contains("if (token != generation) return"))
        assertTrue(webView.contains("if (settled) return"))
    }

    @Test
    fun `the loading placeholder covers the webview instead of a white flash`() {
        assertTrue(pane.contains("""testTag("mindmap_web_loading")"""))
        assertTrue(pane.contains("正在渲染交互导图…"))
        assertTrue(pane.contains("background(SsColors.Surface)"))
        // The WebView stays composed underneath: the probe measures real geometry, so it cannot run
        // against a view that was never attached.
        assertTrue(pane.contains("is MindmapRenderState.LoadingHtml, is MindmapRenderState.HtmlReady ->"))
    }

    @Test
    fun `the manual static entry exists only as an escape hatch, never as a preselection`() {
        assertTrue(pane.contains("""testTag("mindmap_show_static")"""))
        assertTrue(pane.contains("""testTag("mindmap_retry_html")"""))
        assertTrue(pane.contains("显示静态图"))
        assertTrue(pane.contains("重试交互导图"))
        assertTrue(pane.contains("controller.requestStaticImage()"))
        assertTrue(pane.contains("generation = controller.retry()"))
        // Offered only when there is a static image to escape to, and only while HTML is in play.
        assertTrue(pane.contains("if (html != null && png != null && render !is MindmapRenderState.NoHtml)"))
        assertTrue(renderState.contains("Fallback(generation, reason = null)"))
    }

    // ---------------------------------------------------------------- sandbox, unchanged by ZLQ-146

    @Test
    fun `the webview no longer reflows the validated geometry`() {
        // TEXT_AUTOSIZING treats the page as a text column and rescales the SVG viewport, so what
        // the student sees is not the geometry the contract was validated against.
        assertFalse(webView.contains("LayoutAlgorithm.TEXT_AUTOSIZING"))
        assertTrue(webView.contains("layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL"))
        assertTrue(webView.contains("textZoom = 100"))
        assertTrue(webView.contains("useWideViewPort = true"))
        assertTrue(webView.contains("loadWithOverviewMode = true"))
    }

    @Test
    fun `the webview still lets the student zoom into detail`() {
        assertTrue(webView.contains("setSupportZoom(true)"))
        assertTrue(webView.contains("builtInZoomControls = true"))
    }

    @Test
    fun `the webview keeps the generated page sandboxed and offline`() {
        assertTrue(webView.contains("javaScriptEnabled = true"))
        assertTrue(webView.contains("allowFileAccess = false"))
        assertTrue(webView.contains("allowFileAccessFromFileURLs = false"))
        assertTrue(webView.contains("allowUniversalAccessFromFileURLs = false"))
        assertTrue(webView.contains("domStorageEnabled = false"))
        assertTrue(webView.contains("blockNetworkImage = true"))
        assertTrue(webView.contains("WebSettings.LOAD_NO_CACHE"))
        assertFalse(webView.contains("addJavascriptInterface"))
        // The handshake uses the standard result channel, which is not a bridge the page can call.
        assertTrue(webView.contains("webView.evaluateJavascript(script)"))
    }

    @Test
    fun `the visual card viewport is bounded instead of fixed`() {
        assertTrue(pane.contains("BoxWithConstraints"))
        assertTrue(pane.contains("coerceIn(360.dp, 560.dp)"))
        assertFalse(pane.contains("height(300.dp)"))
        assertTrue(pane.contains("""testTag("mindmap_visual")"""))
        assertTrue(pane.contains("""testTag("mindmap_web")"""))
    }

    @Test
    fun `accepted html drives the webview and the png stays a fallback`() {
        assertTrue(pane.contains("MindmapWebView("))
        assertTrue(pane.contains("html = html,"))
        assertTrue(pane.contains("expectation = expectation,"))
        assertTrue(pane.contains("MindmapPng(png)"))
        assertTrue(pane.contains("""testTag("mindmap_png")"""))
    }

    @Test
    fun `the zlq139 node card fix survives in the same file`() {
        // ZLQ-146 touches only the visual card; ZLQ-139's Column fix lives in the node-card region
        // below it, and ZLQ-142 pinned these tags. Both must still be present after the merge.
        assertTrue(pane.contains("""testTag("mindmap_node_"""))
        assertTrue(pane.contains("""testTag("mindmap_expand_"""))
        assertTrue(pane.contains("""testTag("mindmap_citation_"""))
        assertTrue(pane.contains("must share one Column or they paint on top of each other"))
    }

    // ------------------------------------------------- ZLQ-151: ZLQ-147's host fix, merged in

    @Test
    fun `the webview is hosted in a plain viewgroup so viewport units resolve`() {
        // Compose's own measure path resolves CSS viewport units to zero inside a WebView, so the
        // generated CSS's max-height:100vh clamps the SVG's height:auto to 0 and the card paints
        // only the page background (ZLQ-147). The platform measure path of a ViewGroup parent is
        // what keeps 100vh equal to the card's real height, so the wrapper is the fix, not sugar.
        assertTrue(webView.contains("import android.widget.FrameLayout"))
        assertTrue(webView.contains("return FrameLayout(context).apply {"))
        assertTrue(webView.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        assertTrue(webView.contains("factory = { session.createView(context) }"))
        // The two shapes that measured to zero: the bare WebView ZLQ-146 returned, and the
        // pre-ZLQ-146 factory that handed the remembered WebView straight to AndroidView.
        assertFalse(webView.contains("return created ?: View(context)"))
        assertFalse(webView.contains("factory = { webView }"))
    }

    @Test
    fun `the load finds the webview inside the host container`() {
        // The factory now returns a container, so the old `candidate as? WebView` would silently
        // miss: the document would never load and the card would sit on its placeholder until the
        // deadline, with no failure reported (ZLQ-151). Resolving from the host is what keeps that
        // impossible, and a host with no WebView in it must fail loudly instead of skipping.
        assertFalse(webView.contains("candidate as? WebView"))
        assertTrue(webView.contains("val webView = candidate.findWebView()"))
        assertTrue(webView.contains("private fun View.findWebView(): WebView?"))
        assertTrue(webView.contains("if (this is WebView) return this"))
        assertTrue(webView.contains("MindmapRenderFailure.INIT_FAILED, \"webview-not-hosted\""))
        // The load itself is unchanged and still exactly once per document.
        assertTrue(webView.contains("webView.loadDataWithBaseURL(null, html, \"text/html\", \"utf-8\", null)"))
        assertTrue(webView.contains("update = { session.ensureLoaded(it) }"))
    }

    @Test
    fun `a graph that laid out to zero height degrades visibly instead of silently`() {
        // ZLQ-147 carried its own 1200 ms height probe and its own `degraded` flag. Both are gone:
        // the handshake's visibility check already rejects a zero-box SVG, so keeping them would
        // mean two probes and two degradation chains for one failure (ZLQ-151 acceptance §2).
        assertFalse(webView.contains("SVG_GRAPH_HEIGHT_JS"))
        assertFalse(webView.contains("RENDER_SETTLE_MS"))
        assertFalse(pane.contains("var degraded by remember"))
        assertFalse(pane.contains("mindmap_visual_degraded"))
        // The one surviving path: zero box -> svg-invisible -> PROBE_FAILED -> Fallback -> notice.
        assertTrue(webView.contains("if(!(r.width>0 && r.height>0)){return false;}"))
        assertTrue(webView.contains("code:'svg-invisible'"))
        assertTrue(webView.contains("MindmapRenderFailure.PROBE_FAILED, \"dom-probe:\${reply.code}\""))
        assertTrue(pane.contains("DegradedNotice(current.reason)"))
        assertTrue(pane.contains("""testTag("mindmap_visual_empty")"""))
        assertTrue(pane.contains("交互导图加载失败（"))
        // Waiting for geometry is bounded, and only geometry waits: a contract verdict never does.
        assertTrue(webView.contains("private const val DOM_GEOMETRY_CODE = \"svg-invisible\""))
        assertTrue(webView.contains("private const val DOM_GEOMETRY_RETRIES = 3"))
        assertTrue(webView.contains("geometryRetries < DOM_GEOMETRY_RETRIES"))
    }

    @Test
    fun `the render probe stays a read only query and adds no bridge`() {
        // The probe measures through the standard evaluateJavascript result channel, so having a
        // probe at all does not widen the offline sandbox. `addJavascriptInterface` is also pinned
        // by the sandbox test above; it is repeated here because "no bridge" is this test's claim.
        assertFalse(webView.contains("addJavascriptInterface"))
        assertTrue(webView.contains("webView.evaluateJavascript(script)"))
        assertTrue(webView.contains("getBoundingClientRect()"))
        assertTrue(webView.contains("getClientRects()"))
        // Read-only apart from the one toggle click the handshake needs, which the second click
        // restores before the map is ever revealed.
        assertTrue(webView.contains("fun measure(): String"))
        assertTrue(webView.contains("Re-measures without touching the page"))
    }

    @Test
    fun `an axis aligned edge counts as visible while the node criterion keeps both dimensions`() {
        // A purely horizontal edge has a zero-height bbox and a purely vertical one a zero-width
        // bbox, yet both paint; v2 only requires a non-empty `d`, so both are compliant documents
        // (ZLQ-151 G1). The relaxation is therefore disjunctive and scoped to the contract class.
        assertTrue(webView.contains("indexOf('mindmap-edge')>=0"))
        assertTrue(webView.contains("if(edge){if(!(r.width>0 || r.height>0)){return false;}}" +
            "else{if(!(r.width>0 && r.height>0)){return false;}}"))
        // Exactly one criterion of each shape: the node conjunction is neither dropped nor copied
        // onto edges, and the edge disjunction never leaks onto nodes.
        assertEquals(1, Regex("r\\.width>0 \\|\\| r\\.height>0").findAll(webView).count())
        assertEquals(1, Regex("r\\.width>0 && r\\.height>0").findAll(webView).count())
    }

    @Test
    fun `an edge hidden by collapse stays invisible under the relaxed bbox criterion`() {
        // The on-device fixture hides a collapsed branch with style.display='none' on the node and
        // its touching edges, so hiding must be decided before and after the bbox relaxation, for
        // edges and nodes alike: zero client rects first, computed style and opacity last, with
        // nothing but the two bbox criteria in between.
        val vis = webView.substringAfter("function ssVis(el){").substringBefore("function ssCount")
        assertTrue(vis.contains("getClientRects().length===0"))
        assertTrue(vis.indexOf("getClientRects().length===0") < vis.indexOf("r.width>0 || r.height>0"))
        assertEquals(
            "  var edge=(el.getAttribute('class')||'').indexOf('mindmap-edge')>=0;\n" +
                "  if(edge){if(!(r.width>0 || r.height>0)){return false;}}" +
                "else{if(!(r.width>0 && r.height>0)){return false;}}\n  ",
            vis.substringAfter("var r=el.getBoundingClientRect();\n").substringBefore("var cs=window.getComputedStyle(el);"),
        )
        assertTrue(vis.contains("cs.display==='none'"))
        assertTrue(vis.contains("cs.visibility==='hidden'"))
        assertTrue(vis.contains("op<=0.01"))
    }
}
