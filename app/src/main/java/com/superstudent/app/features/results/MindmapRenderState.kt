package com.superstudent.app.features.results

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ZLQ-145 ruling (option 2), replacing ZLQ-140's D6: every supported API level tries the accepted
 * HTML first, and only a *runtime* failure falls back. The order is HTML → PNG → native structure
 * tree on all SDKs; the Android level is diagnostic information, never a render-path preselector.
 *
 * This file is the pure-Kotlin half of that rule — no `android.*`, no Compose, no WebView — so the
 * state machine is executable on the JVM. [MindmapWebView] reports observations through its two
 * callbacks, [MindmapRenderController] owns the fallback, and [MindmapPane] mirrors the controller's
 * state into snapshot state. Splitting it this way is what lets `MindmapRenderStateTest` drive the
 * fallbacks with a fake clock instead of needing a Robolectric harness this repo does not have.
 */

/**
 * Wall-clock budget from `loadDataWithBaseURL` to a completed positive handshake. The document is
 * local, ≤ 1 MiB and has zero network access, so anything still handshaking after this is not going
 * to. Success, release and an HTML change must all cancel the timer.
 */
const val MINDMAP_HTML_RENDER_TIMEOUT_MS = 8_000L

/**
 * Why the interactive HTML was given up on. These codes, the SDK level and the WebView provider
 * package are the only render-failure detail that may reach logcat — never the HTML, the study
 * content or a citation.
 */
enum class MindmapRenderFailure {
    /** WebView construction, configuration, load or probe evaluation threw a `RuntimeException`. */
    INIT_FAILED,

    /** `onReceivedError` for the main frame. Sub-resource errors blocked offline are not this. */
    MAIN_FRAME_ERROR,

    /** The renderer process died; that WebView is destroyed and not reused. */
    RENDER_PROCESS_GONE,

    /** The page loaded but the positive handshake probe rejected what it drew. */
    PROBE_FAILED,

    /** [MINDMAP_HTML_RENDER_TIMEOUT_MS] elapsed without a completed handshake. */
    RENDER_TIMEOUT,
}

/**
 * The four conditions of the positive handshake. All four must hold before the WebView is revealed,
 * which is why a page that merely finished loading is not "rendered".
 *
 * [TOGGLE_PROBE] is expected only when the tree has a non-leaf node: the DOM contract requires
 * `button[data-action=toggle][aria-expanded]` on those and on nothing else.
 */
enum class MindmapHandshakeSignal { PAGE_FINISHED, DOM_PROBE, TOGGLE_PROBE, VISUAL_STATE }

/**
 * What the probe is told to look for, derived from `MindmapData.visual`. Counts and one boolean
 * only — the probe is injected into a page that renders a remote artifact, so it must never carry
 * labels, explanations or citation text.
 */
data class MindmapExpectation(
    val nodeCount: Int,
    val edgeCount: Int,
    val hasNonLeafNodes: Boolean,
) {
    val expectedSignals: Set<MindmapHandshakeSignal>
        get() = buildSet {
            add(MindmapHandshakeSignal.PAGE_FINISHED)
            add(MindmapHandshakeSignal.DOM_PROBE)
            if (hasNonLeafNodes) add(MindmapHandshakeSignal.TOGGLE_PROBE)
            add(MindmapHandshakeSignal.VISUAL_STATE)
        }
}

/**
 * Accumulates the handshake conditions. [satisfy] returns true only on the single call that
 * completes the set, so exactly one `onReady` can ever be emitted per document.
 */
class MindmapHandshake(expected: Set<MindmapHandshakeSignal>) {

    private val expected: Set<MindmapHandshakeSignal> = expected.toSet()
    private val satisfied = mutableSetOf<MindmapHandshakeSignal>()

    val isComplete: Boolean get() = satisfied.containsAll(expected)

    fun satisfy(signal: MindmapHandshakeSignal): Boolean {
        if (isComplete) return false
        satisfied += signal
        return isComplete
    }
}

/**
 * Condition 2's arithmetic: what the page actually drew must equal what the accepted manifest
 * declared. The probe counts only *visible* nodes and edges, so a page carrying the right markup
 * with `display:none` on half of it — or an SVG laid out at zero size — fails here instead of being
 * revealed to the student.
 */
fun mindmapCountsMatchManifest(expectation: MindmapExpectation, reply: MindmapProbeReply): Boolean =
    reply.nodes == expectation.nodeCount && reply.edges == expectation.edgeCount

/**
 * Judges the click-to-collapse condition from measured counts alone — pure arithmetic, so the
 * interaction verdict is testable on the JVM even though the click itself is not.
 *
 * The generation contract does not fix a branch's initial `aria-expanded`, so the probe's click may
 * legitimately hide *or* reveal descendants. What must hold is that the visible node and edge counts
 * move in the direction the **new** `aria-expanded` implies: `true` means more became visible,
 * `false` means fewer. A click that flips the attribute but moves nothing is a broken interaction,
 * and an attribute that is neither `true` nor `false` is no verdict at all — it is rejected rather
 * than assumed to mean one or the other.
 */
fun mindmapToggleMovedAsDeclared(
    baselineNodes: Int,
    baselineEdges: Int,
    ariaExpanded: String?,
    nodes: Int,
    edges: Int,
): Boolean {
    val direction = when (ariaExpanded) {
        "true" -> 1
        "false" -> -1
        else -> return false
    }
    return (nodes - baselineNodes) * direction > 0 && (edges - baselineEdges) * direction > 0
}

/** The second click must put the visible counts back to exactly what the DOM probe measured. */
fun mindmapToggleRestoredToBaseline(
    baselineNodes: Int,
    baselineEdges: Int,
    nodes: Int,
    edges: Int,
): Boolean = nodes == baselineNodes && edges == baselineEdges

/**
 * The render state, keyed by [generation] — the token that makes stale callbacks inert.
 *
 * A `Fallback` carrying a non-null [MindmapRenderState.Fallback.reason] is an automatic fallback after a
 * runtime failure. A null reason is the operator's manual escape hatch (the "显示静态图" entry), which
 * exists for the residual case where the DOM and visual-state probes both pass but the system
 * compositor still paints nothing. It is never a way to reinstate an SDK-based preselection.
 */
sealed interface MindmapRenderState {
    /** No accepted HTML to try. The PNG, or failing that the structure tree, is the whole story. */
    data object NoHtml : MindmapRenderState

    /** HTML is loading or handshaking. The WebView is composed but covered by a placeholder. */
    data class LoadingHtml(val generation: Long) : MindmapRenderState

    /** Handshake complete. The WebView is revealed. */
    data class HtmlReady(val generation: Long) : MindmapRenderState

    data class Fallback(val generation: Long, val reason: MindmapRenderFailure?) : MindmapRenderState
}

/**
 * One probe reply. Every `evaluateJavascript` step returns this same shape so the client parses a
 * single type; steps that do not produce a field leave it at its default.
 */
@Serializable
data class MindmapProbeReply(
    val ok: Boolean = false,
    val code: String? = null,
    /** Visible node count as measured in the page. */
    val nodes: Int = -1,
    /** Visible edge count as measured in the page. */
    val edges: Int = -1,
    /** 1 when at least one `button[data-action=toggle][aria-expanded]` is present. */
    val toggle: Int = 0,
    /** `aria-expanded` before the probe's own click. */
    val before: String? = null,
    /** `aria-expanded` after that click. */
    val after: String? = null,
    val contractVersion: Int = -1,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** `evaluateJavascript` hands back `null` for a script that returned nothing. */
        fun parse(raw: String?): MindmapProbeReply? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
            return runCatching { json.decodeFromString<MindmapProbeReply>(text) }.getOrNull()
        }
    }
}

/**
 * The render-path state machine. Every mutating call carries the generation token handed out by
 * [bind] or [retry]; a token that is no longer current is dropped, which is the mechanical form of
 * "换 HTML / 离开页面后旧回调与 timer 无效".
 *
 * Not thread-safe by design: it is driven from the main thread only (WebView callbacks and Compose),
 * and staying synchronous is what makes it unit-testable.
 */
class MindmapRenderController(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Set by the UI so it can mirror [state] into snapshot state. */
    var onStateChanged: ((MindmapRenderState) -> Unit)? = null

    var state: MindmapRenderState = MindmapRenderState.NoHtml
        private set

    var generation: Long = 0L
        private set

    /** The HTML currently bound, so a recomposition with the same document does not restart it. */
    private var htmlKey: String? = null

    private var deadlineArmed: Boolean = false
    private var loadStartedAt: Long = 0L

    /**
     * Binds to [html] and returns the generation token its callbacks must carry.
     *
     * Rebinding the *same* HTML is a no-op that returns the live token: Compose recomposes, and a
     * recomposition must neither reload the document nor discard a handshake in flight. It must not
     * resurrect a render that already reached a terminal state either — only [retry] restarts one.
     * A different HTML (or none) starts a new generation, which is what invalidates the old timer
     * and the old callbacks.
     */
    fun bind(html: String?): Long {
        if (html == null) {
            htmlKey = null
            if (state !is MindmapRenderState.NoHtml) abandon(MindmapRenderState.NoHtml)
            return generation
        }
        if (html == htmlKey && state !is MindmapRenderState.NoHtml) return generation
        htmlKey = html
        startGeneration()
        return generation
    }

    /**
     * The operator's "重试交互导图". Always a fresh generation, so the document is re-loaded and the
     * whole handshake runs again rather than reusing a verdict already reached.
     */
    fun retry(): Long {
        if (htmlKey == null) return generation
        startGeneration()
        return generation
    }

    /** The operator's "显示静态图" escape hatch. Only meaningful while HTML is still in play. */
    fun requestStaticImage() {
        if (state !is MindmapRenderState.HtmlReady && state !is MindmapRenderState.LoadingHtml) return
        deadlineArmed = false
        transition(MindmapRenderState.Fallback(generation, reason = null))
    }

    /** The positive handshake completed: all expected conditions arrived for the current document. */
    fun onHtmlReady(token: Long) {
        if (token != generation) return
        if (state !is MindmapRenderState.LoadingHtml) return
        deadlineArmed = false
        transition(MindmapRenderState.HtmlReady(generation))
    }

    /**
     * One fallback, and only one: a terminal state ignores every later event, so a timeout racing a
     * probe failure cannot fall back twice or overwrite the reason already reported.
     */
    fun onFailure(token: Long, reason: MindmapRenderFailure) {
        if (token != generation) return
        if (state !is MindmapRenderState.LoadingHtml) return
        deadlineArmed = false
        transition(MindmapRenderState.Fallback(generation, reason))
    }

    /**
     * Drives the deadline. The caller schedules the tick; this decides whether it has actually
     * expired, so an early or replayed tick cannot degrade a healthy render.
     */
    fun onTick(now: Long = clock()) {
        if (!deadlineArmed) return
        if (state !is MindmapRenderState.LoadingHtml) return
        if (now - loadStartedAt < MINDMAP_HTML_RENDER_TIMEOUT_MS) return
        onFailure(generation, MindmapRenderFailure.RENDER_TIMEOUT)
    }

    /** The pane left the composition. Invalidates every outstanding callback and timer at once. */
    fun release() {
        htmlKey = null
        abandon(MindmapRenderState.NoHtml)
    }

    val isHtmlRevealed: Boolean get() = state is MindmapRenderState.HtmlReady

    /** True when the visual card should paint `mindmap.png` instead of the WebView. */
    val showsStaticImage: Boolean get() = state is MindmapRenderState.Fallback

    private fun startGeneration() {
        generation += 1
        deadlineArmed = true
        loadStartedAt = clock()
        transition(MindmapRenderState.LoadingHtml(generation))
    }

    private fun abandon(to: MindmapRenderState) {
        generation += 1
        deadlineArmed = false
        transition(to)
    }

    private fun transition(next: MindmapRenderState) {
        state = next
        onStateChanged?.invoke(next)
    }
}
