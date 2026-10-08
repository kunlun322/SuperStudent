package com.superstudent.app.features.tasks

import com.superstudent.core.model.SessionUsageSnapshot
import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The six terminal paths of ZLQ-89 §4, pinned as far as the JVM allows.
 *
 * `observe()` itself cannot be driven here: it needs a Room-backed `TaskRepository`, `android.util.Log`
 * and an SSE transport, and this repo has no instrumented or Robolectric harness. So the paths are
 * covered at the two levels that are real code rather than a description of it — the predicates every
 * path branches on, and a narrow source-level wiring guard for the requirements that are about *which
 * call a path makes*: one Session read per conclusion, and `UNKNOWN` never concluding at all. Each
 * guard reads one brace-matched block, not the whole file.
 */
class TaskRunnerUsagePathsTest {

    private val source = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt") }
        .first { it.exists() }
        .readText()

    /** The text from [marker] to the end of the block its first `{` opens. */
    private fun block(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared from TaskRunner.kt", start >= 0)
        var depth = 0
        for (i in source.indexOf('{', start) until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    /** Whitespace- and trailing-comma-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "").replace(",)", ")")

    // ---- path 1: an event ends the turn -------------------------------------------------------

    @Test
    fun `the event-terminal path concludes through the funnel with a single session read`() {
        val conclude = block("suspend fun conclude(")
        // The elvis is the whole requirement: a caller that already read the Session must not cause
        // a second read, or one conclusion would be built on two different `duration_seconds`.
        assertTrue(
            "conclude() must short-circuit its Session read on the prefetched argument",
            conclude.contains("prefetched ?:"),
        )
        assertEquals("conclude() must read the Session at most once", 1, conclude.split("api.getSession(").size - 1)
        assertTrue(conclude.contains("val snapshot = SessionUsageSnapshot.from("))

        // All three exits — authoritative cloud failure, validated success, failed validation —
        // hand the same snapshot to the same funnel.
        val flat = conclude.flat()
        assertEquals(3, Regex("finishTerminal\\(").findAll(flat).count())
        assertEquals(
            "every conclude() exit must pass the snapshot it just took",
            3,
            Regex(",snapshot\\)").findAll(flat).count(),
        )
    }

    // ---- path 2: 45 seconds without an event --------------------------------------------------

    @Test
    fun `the no-event probe treats idle as over, not just terminated`() {
        assertTrue(TaskRunner.sessionConcluded("idle"))
        assertTrue(TaskRunner.sessionConcluded("terminated"))
        // Anything else means the turn may still be running, so the probe must keep watching rather
        // than conclude on a measurement that is still growing.
        listOf("running", "queued", "starting", "", "IDLE", null).forEach {
            assertFalse("status `$it` must not conclude the run", TaskRunner.sessionConcluded(it))
        }
    }

    @Test
    fun `the no-event probe hands its own session to conclude instead of letting it re-read`() {
        val probe = block("> NO_EVENT_CHECK_MS")
        assertTrue("the probe must conclude on the session it already holds", probe.contains("conclude(null, session)"))
        assertEquals("the probe must read the Session exactly once", 1, probe.split("api.getSession(").size - 1)
        assertFalse(
            "the probe must go through the single funnel, not write a terminal state itself",
            probe.contains("finishTerminal(") || probe.contains("concludeTerminal("),
        )
        // Credits may be refreshed while the run is still going; the seconds counters may not.
        assertTrue(probe.contains("persist(creditsTotal = it)"))
        assertFalse("a mid-run probe must not freeze the seconds counters", probe.contains("activeSeconds"))
        assertFalse(probe.contains("durationSeconds"))
    }

    // ---- path 3: FAILED -----------------------------------------------------------------------

    @Test
    fun `a failed run is a snapshot point and carries its usage into task_json`() {
        assertTrue(TaskRunner.isUsageSnapshotPoint(TaskState.FAILED_RETRYABLE))
        assertTrue(TaskRunner.isUsageSnapshotPoint(TaskState.FAILED_PERMANENT))
        val flat = block("suspend fun conclude(").flat()
        assertTrue(
            "the artifact-validation failure must pass the same snapshot as the success path",
            Regex("finishTerminal\\(TaskState\\.FAILED_RETRYABLE,.+,snapshot\\)").containsMatchIn(flat),
        )
        assertTrue(
            "an authoritative cloud failure must pass it too",
            flat.contains("finishTerminal(report.state,report,snapshot)"),
        )
    }

    // ---- path 4: CANCELED ---------------------------------------------------------------------

    @Test
    fun `the cancel path reads usage only after the remote session settled`() {
        val cancel = block("suspend fun applyCancel()")
        val settle = cancel.indexOf("ensureRemoteCanceled(sessionId)")
        val read = cancel.indexOf("api.getSession(sessionId)")
        assertTrue(settle > 0 && read > 0)
        assertTrue(
            "reading before the cancel settled would snapshot a session still winding down",
            settle < read,
        )
        assertTrue(TaskRunner.isUsageSnapshotPoint(TaskState.CANCELED))
        assertTrue(cancel.contains("concludeCanceled(") && cancel.contains("snapshot = snapshot"))
        // A failed read must not hold up CANCELED: runCatching leaves the snapshot EMPTY.
        assertTrue(cancel.contains("runCatching"))
        // ZLQ-114 §5.3: an *unconfirmed* remote has not settled either. Its turn may still be running
        // and its `duration_seconds` still growing, so freezing it then would store a mid-run
        // measurement as the terminal one — the very defect ZLQ-84 was about. The read is therefore
        // gated on the confirmation, and the unconfirmed branch keeps EMPTY.
        assertTrue(
            "the usage read must be gated on the remote confirmation",
            cancel.flat().contains("if(confirmed)"),
        )
        assertTrue(cancel.contains("SessionUsageSnapshot.EMPTY"))
    }

    // ---- path 5: a run that failed before it had a Session ------------------------------------

    @Test
    fun `the no-session paths fall back to the empty snapshot instead of fabricating zeros`() {
        val empty = SessionUsageSnapshot.from(null)
        assertEquals(SessionUsageSnapshot.EMPTY, empty)
        assertNull(empty.activeSeconds)
        assertNull(empty.durationSeconds)
        assertNull(empty.totalCredits)

        val failRun = block("private suspend fun failRun(")
        assertTrue(failRun.contains("row.sessionId?.let"))
        assertTrue(failRun.contains("?: SessionUsageSnapshot.EMPTY"))

        val requestCancel = block("suspend fun requestCancel(")
        assertTrue(
            "the no-session cancel must still go through the merge, so no CANCELED path bypasses it",
            requestCancel.contains("concludeCanceled(") &&
                requestCancel.contains("snapshot = SessionUsageSnapshot.EMPTY"),
        )
        // There is no Session to converge, so `remoteConfirmed` is not a claim about the cloud — it
        // says "nothing remote is outstanding". ZLQ-114 §5.2 still writes cleanup_pending=1 here,
        // because the attempt may already have a tmp subtree; the flag routes it to §5.4's compensator
        // rather than to a remote cancel.
        assertTrue(
            "the sessionless cancel must skip remote convergence, not pretend it succeeded",
            requestCancel.contains("remoteConfirmed = true"),
        )
        assertFalse(
            "no Session exists, so this branch must not attempt a remote cancel",
            requestCancel.contains("ensureRemoteCanceled"),
        )
    }

    @Test
    fun `every auth-expired exit cannot read usage because there is no usable credential`() {
        // All are terminal, so §4 would have them snapshot; none can, because the local PAT is gone
        // (line 603's branch), was refused by the pre-flight that ZLQ-119 §4.3 moved in front of every
        // session call, or was just rejected by the API (the listEvents branch). EMPTY is not a
        // shortcut here — it is the only reading available.
        listOf(TaskState.AUTH_EXPIRED, TaskState.ACCESS_DENIED, TaskState.IDENTITY_INVALID)
            .forEach { assertTrue(TaskRunner.isUsageSnapshotPoint(it)) }
        assertEquals(
            "exactly the three credential-less exits may rely on the EMPTY default",
            3,
            Regex("finishTerminal\\(\\s*TaskState.AUTH_EXPIRED").findAll(source).count(),
        )
    }

    // ---- path 6: UNKNOWN ----------------------------------------------------------------------

    @Test
    fun `unknown is not a snapshot point and does not conclude the run`() {
        assertFalse(
            "UNKNOWN is still being observed by the recovery worker; freezing it would lose the run",
            TaskRunner.isUsageSnapshotPoint(TaskState.UNKNOWN),
        )
        val unknown = block("> UNKNOWN_AFTER_MS")
        assertTrue(unknown.contains("persist(state = TaskState.UNKNOWN"))
        assertFalse(unknown.contains("finishTerminal("))
        assertFalse(unknown.contains("concludeTerminal("))
        assertFalse("UNKNOWN must stay recoverable, so it cannot set finishedAt", unknown.contains("finished = true"))
    }

    @Test
    fun `no mid-run state freezes the counters`() {
        listOf(
            TaskState.QUEUED,
            TaskState.RUNNING,
            TaskState.RETRY_WAIT,
            TaskState.CANCEL_REQUESTED,
            TaskState.UNKNOWN,
        ).forEach { assertFalse("$it must not snapshot usage", TaskRunner.isUsageSnapshotPoint(it)) }

        assertEquals(
            "adding a state forces an explicit decision about whether it snapshots usage",
            setOf(
                TaskState.SUCCEEDED,
                TaskState.FAILED_RETRYABLE,
                TaskState.FAILED_PERMANENT,
                TaskState.CANCELED,
                TaskState.AUTH_EXPIRED,
                TaskState.ACCESS_DENIED,
                TaskState.IDENTITY_INVALID,
            ),
            TaskRunner.TERMINAL_STATES,
        )
        TaskRunner.TERMINAL_STATES.forEach { assertTrue(TaskRunner.isUsageSnapshotPoint(it)) }
        assertEquals(
            "every TaskState must be classified as either terminal or mid-run",
            TaskState.values().toSet(),
            TaskRunner.TERMINAL_STATES + setOf(
                TaskState.QUEUED,
                TaskState.RUNNING,
                TaskState.RETRY_WAIT,
                TaskState.CANCEL_REQUESTED,
                TaskState.UNKNOWN,
            ),
        )
    }
}
