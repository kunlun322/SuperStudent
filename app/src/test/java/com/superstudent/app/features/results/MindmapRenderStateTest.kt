package com.superstudent.app.features.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZLQ-146 §四. The render path is now decided at runtime, so the decision itself is what has to be
 * proven: HTML is tried first on every SDK, each runtime failure degrades exactly once, the
 * generation token makes stale callbacks and timers inert, and the four handshake conditions gate
 * readiness.
 *
 * These tests execute the state machine on the JVM with a fake clock. That is the whole point of
 * keeping [MindmapRenderController] free of `android.*` — this repo has no Robolectric or
 * instrumented harness, so real WebView callbacks, painting and click behaviour stay unproven here
 * and are covered by source pinning in [MindmapRenderingContractTest] plus manual QA.
 */
class MindmapRenderStateTest {

    private class FakeClock {
        var now: Long = 1_000L
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val clock = FakeClock()
    private val controller = MindmapRenderController(clock = { clock.now })
    private val seen = mutableListOf<MindmapRenderState>()

    private fun recording(): MindmapRenderController {
        controller.onStateChanged = { seen += it }
        return controller
    }

    private val html = "<svg id=\"ss-mindmap\"></svg>"

    // ---------------------------------------------------------------- HTML first, always

    @Test
    fun `binding html starts loading instead of showing the static image`() {
        val token = controller.bind(html)

        assertEquals(MindmapRenderState.LoadingHtml(token), controller.state)
        assertFalse("HTML must be tried before any PNG, on every SDK", controller.showsStaticImage)
        assertFalse(controller.isHtmlRevealed)
    }

    @Test
    fun `the state machine has no sdk input, so no version can preselect the render path`() {
        // ZLQ-145 option 2 makes the Android level diagnostic only. The mechanical form of that rule
        // is that there is nowhere to hand the controller a level: the whole public surface takes
        // documents, tokens and reasons. Source pinning in MindmapRenderingContractTest covers the
        // same rule on the Compose side, where Build used to be imported.
        val surface = MindmapRenderController::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }

        assertTrue(surface.isNotEmpty())
        assertTrue(
            surface.map { it.name }.toString(),
            surface.none { it.name.lowercase().contains("sdk") || it.name.lowercase().contains("api") },
        )
        assertEquals(listOf(String::class.java.name), surface.first { it.name == "bind" }.parameterTypes.map { p -> p.name })
    }

    @Test
    fun `a completed handshake reveals the html and never falls back to the png afterwards`() {
        val token = controller.bind(html)
        controller.onHtmlReady(token)

        assertEquals(MindmapRenderState.HtmlReady(token), controller.state)
        assertTrue(controller.isHtmlRevealed)

        // Late events from the same document must not undo a verdict already reached.
        controller.onFailure(token, MindmapRenderFailure.RENDER_TIMEOUT)
        controller.onTick(clock.now + MINDMAP_HTML_RENDER_TIMEOUT_MS * 10)
        controller.onHtmlReady(token)

        assertEquals(MindmapRenderState.HtmlReady(token), controller.state)
        assertFalse(controller.showsStaticImage)
    }

    @Test
    fun `the deadline is cancelled once the html is ready`() {
        val token = controller.bind(html)
        controller.onHtmlReady(token)

        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS * 2)
        controller.onTick()

        assertEquals(MindmapRenderState.HtmlReady(token), controller.state)
    }

    // ---------------------------------------------------------------- each failure degrades once

    @Test
    fun `every runtime failure degrades exactly once and keeps its own reason`() {
        MindmapRenderFailure.entries.forEach { reason ->
            val fresh = MindmapRenderController(clock = { clock.now })
            val states = mutableListOf<MindmapRenderState>()
            fresh.onStateChanged = { states += it }

            val token = fresh.bind(html)
            fresh.onFailure(token, reason)

            assertEquals(reason.name, MindmapRenderState.Fallback(token, reason), fresh.state)
            assertTrue(reason.name, fresh.showsStaticImage)
            assertEquals(
                reason.name,
                listOf(MindmapRenderState.LoadingHtml(token), MindmapRenderState.Fallback(token, reason)),
                states,
            )

            // A second event — the same reason, a different reason, or the deadline — changes nothing.
            fresh.onFailure(token, reason)
            fresh.onFailure(token, MindmapRenderFailure.RENDER_TIMEOUT)
            fresh.onHtmlReady(token)
            clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS * 2)
            fresh.onTick()

            assertEquals(reason.name, MindmapRenderState.Fallback(token, reason), fresh.state)
            assertEquals(reason.name, 2, states.size)
        }
    }

    @Test
    fun `the eight second deadline degrades with render timeout`() {
        val token = controller.bind(html)

        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS - 1)
        controller.onTick()
        assertEquals("one millisecond early must not degrade", MindmapRenderState.LoadingHtml(token), controller.state)

        clock.advance(1)
        controller.onTick()
        assertEquals(
            MindmapRenderState.Fallback(token, MindmapRenderFailure.RENDER_TIMEOUT),
            controller.state,
        )
    }

    @Test
    fun `the budget is measured from load start and is eight seconds`() {
        assertEquals(8_000L, MINDMAP_HTML_RENDER_TIMEOUT_MS)

        clock.advance(60_000) // wall clock moved on before this document was ever bound
        val token = controller.bind(html)
        controller.onTick()

        assertEquals(MindmapRenderState.LoadingHtml(token), controller.state)
    }

    @Test
    fun `a tick that replays after the deadline was disarmed is inert`() {
        val token = controller.bind(html)
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS)
        controller.onTick()
        assertEquals(MindmapRenderState.Fallback(token, MindmapRenderFailure.RENDER_TIMEOUT), controller.state)

        controller.onTick()
        controller.onTick(clock.now + MINDMAP_HTML_RENDER_TIMEOUT_MS)

        assertEquals(MindmapRenderState.Fallback(token, MindmapRenderFailure.RENDER_TIMEOUT), controller.state)
    }

    // ---------------------------------------------------------------- stale callbacks and timers

    @Test
    fun `a callback carrying an old generation token cannot overwrite the new state`() {
        val first = controller.bind(html)
        val second = controller.bind("$html<!-- a different document -->")
        assertNotEquals(first, second)

        controller.onHtmlReady(first)
        controller.onFailure(first, MindmapRenderFailure.PROBE_FAILED)

        assertEquals(MindmapRenderState.LoadingHtml(second), controller.state)

        controller.onHtmlReady(second)
        assertEquals(MindmapRenderState.HtmlReady(second), controller.state)
    }

    @Test
    fun `an old generation timer cannot degrade the new one`() {
        val first = controller.bind(html)
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS / 2)
        val second = controller.bind("$html<!-- changed -->")

        // The deadline is measured from the newest load start, so the old one expiring is irrelevant.
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS / 2)
        controller.onTick()
        assertEquals(MindmapRenderState.LoadingHtml(second), controller.state)
        assertNotEquals(first, second)

        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS / 2)
        controller.onTick()
        assertEquals(MindmapRenderState.Fallback(second, MindmapRenderFailure.RENDER_TIMEOUT), controller.state)
    }

    @Test
    fun `rebinding the same document keeps the live generation so a recomposition cannot reload it`() {
        val first = controller.bind(html)
        clock.advance(1_000)

        repeat(5) { assertEquals(first, controller.bind(html)) }

        assertEquals(MindmapRenderState.LoadingHtml(first), controller.state)
        controller.onHtmlReady(first)
        assertEquals(MindmapRenderState.HtmlReady(first), controller.state)
        repeat(5) { assertEquals(first, controller.bind(html)) }
        assertEquals("a recomposition must not resurrect a finished render", MindmapRenderState.HtmlReady(first), controller.state)
    }

    @Test
    fun `release invalidates every outstanding callback and timer`() {
        val token = controller.bind(html)
        controller.release()

        assertEquals(MindmapRenderState.NoHtml, controller.state)
        controller.onHtmlReady(token)
        controller.onFailure(token, MindmapRenderFailure.MAIN_FRAME_ERROR)
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS * 3)
        controller.onTick()

        assertEquals(MindmapRenderState.NoHtml, controller.state)
    }

    @Test
    fun `binding no html leaves the pane on the png or the structure tree`() {
        val token = controller.bind(html)
        val afterNull = controller.bind(null)

        assertEquals(MindmapRenderState.NoHtml, controller.state)
        assertNotEquals(token, afterNull)
        controller.onHtmlReady(token)
        controller.onFailure(token, MindmapRenderFailure.INIT_FAILED)
        assertEquals("nothing was tried, so nothing failed", MindmapRenderState.NoHtml, controller.state)
    }

    // ---------------------------------------------------------------- the manual escape hatch

    @Test
    fun `the static image entry is a manual choice, not a failure`() {
        val token = controller.bind(html)
        controller.onHtmlReady(token)

        controller.requestStaticImage()

        val fallback = controller.state as MindmapRenderState.Fallback
        assertNull("a manual switch must not be logged as a render failure", fallback.reason)
        assertTrue(controller.showsStaticImage)
    }

    @Test
    fun `the static image entry does nothing once the html was already given up on`() {
        val token = controller.bind(html)
        controller.onFailure(token, MindmapRenderFailure.RENDER_PROCESS_GONE)

        controller.requestStaticImage()

        assertEquals(
            "the automatic reason must survive the manual switch",
            MindmapRenderState.Fallback(token, MindmapRenderFailure.RENDER_PROCESS_GONE),
            controller.state,
        )
    }

    @Test
    fun `retry after the static image starts a fresh generation that can reach ready again`() {
        val first = controller.bind(html)
        controller.onHtmlReady(first)
        controller.requestStaticImage()
        assertTrue(controller.showsStaticImage)

        val second = controller.retry()

        assertNotEquals(first, second)
        assertEquals(MindmapRenderState.LoadingHtml(second), controller.state)
        assertFalse(controller.showsStaticImage)

        controller.onHtmlReady(second)
        assertEquals(MindmapRenderState.HtmlReady(second), controller.state)

        // The first generation is dead: its callbacks are dropped.
        controller.onFailure(first, MindmapRenderFailure.PROBE_FAILED)
        assertEquals(MindmapRenderState.HtmlReady(second), controller.state)
    }

    @Test
    fun `retry after an automatic failure re-runs the whole handshake`() {
        val first = controller.bind(html)
        controller.onFailure(first, MindmapRenderFailure.INIT_FAILED)

        val second = controller.retry()

        assertEquals(MindmapRenderState.LoadingHtml(second), controller.state)
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS)
        controller.onTick()
        assertEquals(MindmapRenderState.Fallback(second, MindmapRenderFailure.RENDER_TIMEOUT), controller.state)
    }

    @Test
    fun `retry rebinds the deadline so a stale tick cannot fire immediately`() {
        val first = controller.bind(html)
        controller.onFailure(first, MindmapRenderFailure.PROBE_FAILED)
        clock.advance(MINDMAP_HTML_RENDER_TIMEOUT_MS)

        val second = controller.retry()
        controller.onTick()

        assertEquals(MindmapRenderState.LoadingHtml(second), controller.state)
    }

    @Test
    fun `retry with nothing bound is a no-op`() {
        val before = controller.generation
        controller.retry()
        assertEquals(before, controller.generation)
        assertEquals(MindmapRenderState.NoHtml, controller.state)
    }

    // ---------------------------------------------------------------- the positive handshake

    @Test
    fun `a tree without non leaf nodes needs three conditions`() {
        val expectation = MindmapExpectation(nodeCount = 4, edgeCount = 3, hasNonLeafNodes = false)

        assertEquals(
            setOf(
                MindmapHandshakeSignal.PAGE_FINISHED,
                MindmapHandshakeSignal.DOM_PROBE,
                MindmapHandshakeSignal.VISUAL_STATE,
            ),
            expectation.expectedSignals,
        )
    }

    @Test
    fun `a tree with non leaf nodes also needs the click to collapse and restore`() {
        val expectation = MindmapExpectation(nodeCount = 6, edgeCount = 5, hasNonLeafNodes = true)

        assertTrue(expectation.expectedSignals.contains(MindmapHandshakeSignal.TOGGLE_PROBE))
        assertEquals(4, expectation.expectedSignals.size)
    }

    @Test
    fun `page finished alone is not rendered`() {
        val handshake = MindmapHandshake(MindmapExpectation(4, 3, true).expectedSignals)

        assertFalse(handshake.satisfy(MindmapHandshakeSignal.PAGE_FINISHED))
        assertFalse(handshake.isComplete)
    }

    @Test
    fun `only the condition that completes the set reports ready, and it reports once`() {
        val handshake = MindmapHandshake(MindmapExpectation(4, 3, true).expectedSignals)

        assertFalse(handshake.satisfy(MindmapHandshakeSignal.PAGE_FINISHED))
        assertFalse(handshake.satisfy(MindmapHandshakeSignal.DOM_PROBE))
        assertFalse(handshake.satisfy(MindmapHandshakeSignal.TOGGLE_PROBE))
        assertFalse("three of four is still not rendered", handshake.isComplete)

        assertTrue(handshake.satisfy(MindmapHandshakeSignal.VISUAL_STATE))
        assertTrue(handshake.isComplete)

        MindmapHandshakeSignal.entries.forEach {
            assertFalse("ready must be emitted exactly once", handshake.satisfy(it))
        }
    }

    @Test
    fun `an unexpected extra condition does not complete a smaller set early`() {
        val handshake = MindmapHandshake(MindmapExpectation(4, 3, true).expectedSignals)

        handshake.satisfy(MindmapHandshakeSignal.PAGE_FINISHED)
        handshake.satisfy(MindmapHandshakeSignal.DOM_PROBE)
        handshake.satisfy(MindmapHandshakeSignal.VISUAL_STATE)

        assertFalse("the toggle probe is still outstanding", handshake.isComplete)
    }

    // ---------------------------------------------------------------- probe replies

    @Test
    fun `a probe reply that reports nothing usable parses to null`() {
        assertNull(MindmapProbeReply.parse(null))
        assertNull(MindmapProbeReply.parse(""))
        assertNull(MindmapProbeReply.parse("   "))
        assertNull(MindmapProbeReply.parse("null"))
        assertNull(MindmapProbeReply.parse("undefined"))
        assertNull(MindmapProbeReply.parse("{not json"))
    }

    @Test
    fun `a probe reply carries counts only, never study content`() {
        val reply = MindmapProbeReply.parse("""{"ok":true,"nodes":6,"edges":5,"toggle":1,"contractVersion":2}""")

        assertEquals(6, reply?.nodes)
        assertEquals(5, reply?.edges)
        assertEquals(2, reply?.contractVersion)
        assertTrue(reply!!.ok)
    }

    @Test
    fun `a rejected probe carries its code and no counts`() {
        val reply = MindmapProbeReply.parse("""{"ok":false,"code":"nodes-invisible"}""")

        assertFalse(reply!!.ok)
        assertEquals("nodes-invisible", reply.code)
        assertEquals(-1, reply.nodes)
        assertEquals(-1, reply.edges)
    }

    @Test
    fun `unknown probe fields are ignored so the page cannot break the parser`() {
        val reply = MindmapProbeReply.parse("""{"ok":true,"nodes":4,"edges":3,"label":"光合作用"}""")

        assertEquals(4, reply?.nodes)
    }

    // ---------------------------------------------------------------- what the probe is told to check

    @Test
    fun `the toggle probe records the aria state before and after the click so it can be restored`() {
        val reply = MindmapProbeReply.parse("""{"ok":true,"before":"true","after":"false","nodes":2,"edges":1}""")

        assertEquals("true", reply?.before)
        assertEquals("false", reply?.after)
        assertNotEquals(reply?.before, reply?.after)
    }

    // ---------------------------------------------------------------- the interaction verdict

    @Test
    fun `a click that collapses descendants passes when it started expanded`() {
        // Baseline: 6 nodes / 5 edges visible, the first toggle started at aria-expanded="true" and
        // the click hid two descendants and their two edges.
        assertTrue(mindmapToggleMovedAsDeclared(6, 5, "false", 4, 3))
    }

    @Test
    fun `a click that reveals descendants passes when it started collapsed`() {
        // The generation contract does not fix the initial aria-expanded, so the probe must accept
        // the reveal direction too rather than demanding the counts always shrink.
        assertTrue(mindmapToggleMovedAsDeclared(4, 3, "true", 6, 5))
    }

    @Test
    fun `a click that flips the attribute but changes nothing visible is a broken interaction`() {
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "false", 6, 5))
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "true", 6, 5))
    }

    @Test
    fun `a click that moves only nodes, or only edges, is not a working toggle`() {
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "false", 4, 5))
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "false", 6, 3))
    }

    @Test
    fun `a click that moves the wrong way for its own aria state is rejected`() {
        // Says "false" (collapsed) yet more became visible: the attribute and the DOM disagree.
        assertFalse(mindmapToggleMovedAsDeclared(4, 3, "false", 6, 5))
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "true", 4, 3))
    }

    @Test
    fun `a missing aria state is rejected rather than assumed`() {
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, null, 4, 3))
        assertFalse(mindmapToggleMovedAsDeclared(4, 3, null, 6, 5))
        assertFalse(mindmapToggleMovedAsDeclared(6, 5, "TRUE", 8, 7))
        assertFalse(mindmapToggleMovedAsDeclared(4, 3, "maybe", 6, 5))
        assertFalse(mindmapToggleMovedAsDeclared(4, 3, "", 6, 5))
    }

    @Test
    fun `the second click must restore the counts exactly, not merely move them back`() {
        assertTrue(mindmapToggleRestoredToBaseline(6, 5, 6, 5))
        assertFalse(mindmapToggleRestoredToBaseline(6, 5, 5, 5))
        assertFalse(mindmapToggleRestoredToBaseline(6, 5, 6, 4))
        assertFalse(mindmapToggleRestoredToBaseline(6, 5, 7, 6))
    }

    @Test
    fun `a page that draws fewer nodes or edges than the manifest declared is rejected`() {
        // The probe counts only *visible* elements, so correct markup with display:none on half of
        // it — or an SVG laid out at zero size — fails here instead of being revealed.
        val expectation = MindmapExpectation(nodeCount = 6, edgeCount = 5, hasNonLeafNodes = true)

        assertTrue(mindmapCountsMatchManifest(expectation, MindmapProbeReply(ok = true, nodes = 6, edges = 5)))
        assertFalse(mindmapCountsMatchManifest(expectation, MindmapProbeReply(ok = true, nodes = 3, edges = 5)))
        assertFalse(mindmapCountsMatchManifest(expectation, MindmapProbeReply(ok = true, nodes = 6, edges = 2)))
        assertFalse(mindmapCountsMatchManifest(expectation, MindmapProbeReply(ok = true, nodes = 0, edges = 0)))
    }

    @Test
    fun `a probe that measured nothing is rejected rather than treated as a match`() {
        // `-1` is the "this step produced no count" default, and must never equal a real expectation.
        val expectation = MindmapExpectation(nodeCount = 6, edgeCount = 5, hasNonLeafNodes = true)

        assertFalse(mindmapCountsMatchManifest(expectation, MindmapProbeReply()))
        assertFalse(mindmapCountsMatchManifest(expectation, MindmapProbeReply(ok = false, code = "svg-missing")))
    }

    @Test
    fun `state changes are reported to the ui in order`() {
        recording().bind(html)
        controller.onHtmlReady(controller.generation)

        assertEquals(
            listOf(
                MindmapRenderState.LoadingHtml(1),
                MindmapRenderState.HtmlReady(1),
            ),
            seen,
        )
    }
}
