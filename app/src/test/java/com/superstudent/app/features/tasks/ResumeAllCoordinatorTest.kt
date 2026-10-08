package com.superstudent.app.features.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * ZLQ-120 §5.2's single-flight guard, driven for real: the coordinator is pure JVM once the pid and
 * log sinks are injected, so nothing in this file is a source pin.
 *
 * What the coordinator cannot answer on the JVM is whether the pass it guards builds exactly one
 * intent — that needs a `Context` — so the "unique intent constructor, exactly three real call forms"
 * half of §4.2 E-1 is pinned in `ResumeAllWiringTest`, the way `TaskRunnerUsagePathsTest` pins its
 * call sites.
 */
class ResumeAllCoordinatorTest {

    private val reasons = listOf(
        ResumeReason.PROCESS_START,
        ResumeReason.LOGIN_COMPLETED,
        ResumeReason.NETWORK_RECOVERED,
    )

    /** One coordinator plus everything an assertion needs to read back off it. */
    private class Harness {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val passes: MutableList<List<ResumeReason>> = Collections.synchronizedList(mutableListOf())
        val coordinator = ResumeAllCoordinator(pid = { 4242 }, log = { lines += it })

        val dispatched: List<String> get() = lines.filter { it.startsWith("resume_dispatch") }
        val requested: List<String> get() = lines.filter { it.startsWith("resume_request") }

        fun operation(): String = requested.last().substringAfter("operation=").substringBefore(" ")
    }

    /**
     * Releases [release] once every one of [count] triggers has recorded its `resume_request`, which is
     * the situation §5.2 is actually about: the application pass is mid-flight when the login callback
     * and the network worker land on it. Waiting on the log rather than on a fixed number of yields is
     * what makes the "one merged follow-up" bound below a statement about the coordinator instead of
     * about the scheduler happening to interleave a particular way.
     */
    private suspend fun releaseOnceAllRegistered(h: Harness, count: Int, release: CompletableDeferred<Unit>) {
        while (h.requested.size < count) yield()
        release.complete(Unit)
    }

    // ---- §8.1 item 8: a hundred concurrent triggers are one dispatch ---------------------------

    @Test
    fun `a hundred concurrent triggers run one pass plus at most one merged follow-up`() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val results = Collections.synchronizedList(mutableListOf<String?>())
        val covered = Collections.synchronizedList(mutableListOf<ResumeReason>())
        var first = true

        suspend fun pass(operation: ResumeOperation): String {
            h.passes += operation.reasons
            covered += operation.reasons
            operation.rowCount = 7
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            return "ran"
        }

        val all = (0 until 100).map { i -> async { h.coordinator.request(reasons[i % 3], ::pass) } }
        launch {
            entered.await()
            releaseOnceAllRegistered(h, 100, release)
        }.join()
        all.forEach { results += it.await() }

        assertEquals("every trigger must return, none left awaiting a drain that already ended", 100, results.size)
        assertTrue("one pass plus at most one merged follow-up, got ${h.passes.size}", h.passes.size <= 2)
        assertEquals(
            "exactly one trigger owns the drain and reports a result; the other 99 were merged into it",
            1,
            results.count { it != null },
        )
        assertEquals("one resume_dispatch line per pass", h.passes.size, h.dispatched.size)
        assertEquals(
            "the follow-up must cover every reason that arrived mid-pass, not just the first of them",
            reasons.toSet(),
            covered.toSet(),
        )
        assertEquals(100, h.requested.size)
        assertEquals(
            "a merged request is logged as coalesced, the owning one is not",
            1,
            h.requested.count { it.contains("coalesced=false") },
        )
    }

    // ---- §8.1 item 9: the dispatch count does not scale with the trigger count -----------------

    @Test
    fun `one trigger dispatches once and three concurrent triggers still dispatch once`() = runTest {
        val one = Harness()
        assertEquals("ran", one.coordinator.request(ResumeReason.PROCESS_START) { it.rowCount = 1; "ran" })
        assertEquals(1, one.dispatched.size)

        val three = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val merged = reasons.map { reason ->
            async {
                three.coordinator.request(reason) { op ->
                    three.passes += op.reasons
                    op.rowCount = 1
                    if (first) {
                        first = false
                        entered.complete(Unit)
                        release.await()
                    }
                    "ran"
                }
            }
        }
        launch {
            entered.await()
            releaseOnceAllRegistered(three, 3, release)
        }.join()
        merged.forEach { it.await() }

        assertTrue(
            "three concurrent triggers must not become three dispatches, got ${three.dispatched.size}",
            three.dispatched.size <= 2,
        )
        assertEquals(
            "all three reasons are covered between them",
            reasons.toSet(),
            three.passes.flatten().toSet(),
        )
    }

    @Test
    fun `sequential triggers are not merged - coalescing must not become dropping`() = runTest {
        val h = Harness()
        reasons.forEach { reason ->
            assertEquals("ran-$reason", h.coordinator.request(reason) { it.rowCount = 0; "ran-$reason" })
        }
        // Nothing was in flight, so there is nothing to merge into. Dropping these would mean a login
        // that lands after the cold-start pass finished resumes nothing at all.
        assertEquals(3, h.dispatched.size)
        assertEquals(0, h.requested.count { it.contains("coalesced=true") })
    }

    // ---- §8.1 item 11: a pass that dies must not pin the coordinator in RUNNING ----------------

    @Test
    fun `a pass that throws releases the coordinator for the next trigger`() = runTest {
        val h = Harness()
        assertTrue(
            runCatching { h.coordinator.request(ResumeReason.PROCESS_START) { error("pass blew up") } }
                .isFailure,
        )
        // The failure has to stay local to the pass that caused it. If RUNNING survived it, every later
        // trigger would merge into a drain nobody was running and no cold start would ever dispatch
        // again until the process died — so the next trigger must come back with its own result.
        assertEquals("recovered", h.coordinator.request(ResumeReason.LOGIN_COMPLETED) { it.rowCount = 2; "recovered" })
        // One line, from the pass that completed: a pass that threw never reached its dispatch log, so
        // `rows=` is never written for work that did not happen.
        assertEquals(1, h.dispatched.size)
        assertEquals(2, h.requested.size)
    }

    @Test
    fun `a pass whose caller is cancelled releases the coordinator for the next trigger`() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val stuck = launch {
            h.coordinator.request(ResumeReason.NETWORK_RECOVERED) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        stuck.cancelAndJoin()

        assertEquals("recovered", h.coordinator.request(ResumeReason.PROCESS_START) { it.rowCount = 1; "recovered" })
        assertEquals("the cancelled pass wrote no dispatch line, the recovery pass wrote one", 1, h.dispatched.size)
    }

    @Test
    fun `a trigger merged into a pass that throws comes back instead of awaiting forever`() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ownerFailed = CompletableDeferred<Boolean>()
        launch {
            // Caught inside the launched coroutine: an exception escaping a child of the test scope
            // would fail the test before it reached the assertions, and the point here is what the
            // *merged* caller sees.
            ownerFailed.complete(
                runCatching {
                    h.coordinator.request(ResumeReason.PROCESS_START) {
                        entered.complete(Unit)
                        release.await()
                        error("pass blew up")
                    }
                }.isFailure,
            )
        }
        entered.await()
        val merged = async { h.coordinator.request(ResumeReason.LOGIN_COMPLETED) { "unreachable" } }
        releaseOnceAllRegistered(h, 2, release)

        assertTrue(ownerFailed.await())
        // The merged caller's contract is "somebody else owns this reason". When that owner dies the
        // caller still has to return, or the login callback hangs inside its own launch forever.
        assertNull(merged.await())
    }

    // ---- §7.3's log contract ------------------------------------------------------------------

    @Test
    fun `the log lines carry the contract fields and nothing that could identify a credential`() =
        runTest {
            val h = Harness()
            h.coordinator.request(ResumeReason.LOGIN_COMPLETED) { it.rowCount = 3; "ran" }

            val operation = h.operation()
            assertEquals("resume_request operation=$operation reason=LOGIN coalesced=false", h.requested.single())
            assertEquals("resume_dispatch operation=$operation rows=3 pid=4242", h.dispatched.single())
            // Both lines are matched in full above, which is the redaction proof: §7.3 forbids a PAT,
            // an Authorization header, ciphertext or a full local URI, and a line that equals the
            // contract has no room left for any of them. The two checks below are the part of that a
            // reader can see without diffing against the format.
            h.lines.forEach { line ->
                assertFalse("no log line may carry an Authorization header", line.contains("Bearer"))
                assertFalse("no log line may carry a full local URI", line.contains("/"))
            }
        }

    @Test
    fun `a pass that finds no rows is still logged, because rows=0 is the proof no intent went out`() =
        runTest {
            val h = Harness()
            h.coordinator.request(ResumeReason.PROCESS_START) { it.rowCount = 0; "ran" }
            assertEquals("resume_dispatch operation=${h.operation()} rows=0 pid=4242", h.dispatched.single())
        }

    @Test
    fun `each of the three triggers is recorded under its own reason`() = runTest {
        val h = Harness()
        h.coordinator.request(ResumeReason.PROCESS_START) { "ran" }
        h.coordinator.request(ResumeReason.LOGIN_COMPLETED) { "ran" }
        h.coordinator.request(ResumeReason.NETWORK_RECOVERED) { "ran" }
        assertEquals(
            listOf("PROCESS_START", "LOGIN", "NETWORK"),
            h.requested.map { it.substringAfter("reason=").substringBefore(" ") },
        )
        // §5.2 keeps SERVICE_HANDOFF as the reserved fourth spelling; `ResumeAllWiringTest` pins that
        // nothing in production sends it yet.
        assertEquals(4, ResumeReason.entries.size)
    }
}
