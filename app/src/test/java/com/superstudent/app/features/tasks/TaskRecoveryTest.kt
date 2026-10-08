package com.superstudent.app.features.tasks

import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ZLQ-103 §3 recovery decision table, as an exhaustive cross product rather than a list of
 * examples: state × hasSession × heldByLiveSubmit. Every cell is asserted, so adding a `TaskState`
 * or reordering the guards fails here instead of silently changing what a cold start does to an
 * installed user's rows.
 */
class TaskRecoveryTest {

    private val midRun = listOf(
        TaskState.QUEUED,
        TaskState.RUNNING,
        TaskState.RETRY_WAIT,
        TaskState.UNKNOWN,
        TaskState.CANCEL_REQUESTED,
    )

    @Test
    fun `every state is classified and the table covers the whole enum`() {
        assertEquals(
            "the table must be exhaustive over TaskState",
            TaskState.values().toSet(),
            midRun.toSet() + TaskRunner.TERMINAL_STATES,
        )
        // No mid-run row may fall through to NONE: NONE means "leave it alone", and a sessionless
        // mid-run row left alone is exactly the permanent orphan this issue is about.
        midRun.forEach { state ->
            listOf(true, false).forEach { hasSession ->
                listOf(true, false).forEach { held ->
                    assertFalse(
                        "$state/session=$hasSession/held=$held was left unrecovered",
                        TaskRecovery.decide(state.name, hasSession, held) == RecoveryAction.NONE,
                    )
                }
            }
        }
    }

    // ---- the orphan: sessionless and unowned --------------------------------------------------

    @Test
    fun `a sessionless unowned mid-run row converges to a retryable terminal`() {
        listOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.RETRY_WAIT, TaskState.UNKNOWN).forEach {
            assertEquals(
                "$it with no Session and no owner is the ZLQ-102 orphan",
                RecoveryAction.CONVERGE_START_INTERRUPTED,
                TaskRecovery.decide(it.name, hasSession = false, heldByLiveSubmit = false),
            )
        }
        assertEquals("START_INTERRUPTED", TaskRecovery.ERROR_CODE_START_INTERRUPTED)
        assertTrue(TaskRecovery.isConverge(RecoveryAction.CONVERGE_START_INTERRUPTED))
    }

    @Test
    fun `a cancel recorded before any Session existed is honoured as a cancel, not as a failure`() {
        assertEquals(
            RecoveryAction.CONVERGE_CANCELED,
            TaskRecovery.decide(TaskState.CANCEL_REQUESTED.name, hasSession = false, heldByLiveSubmit = false),
        )
        assertTrue(TaskRecovery.isConverge(RecoveryAction.CONVERGE_CANCELED))
    }

    // ---- the live submit critical section -----------------------------------------------------

    @Test
    fun `a sessionless row a live submit still owns is left alone, whatever its state`() {
        midRun.forEach {
            assertEquals(
                "$it is mid-submit in this process; converging it would conclude a run that is starting",
                RecoveryAction.WAIT_FOR_SUBMIT,
                TaskRecovery.decide(it.name, hasSession = false, heldByLiveSubmit = true),
            )
        }
        assertFalse(TaskRecovery.isConverge(RecoveryAction.WAIT_FOR_SUBMIT))
    }

    // ---- rows that already have a Session -----------------------------------------------------

    @Test
    fun `a queued row with a Session replays instead of creating a second one`() {
        // The kill landed after the sessionId write but before sendEvents. Recovery must deliver the
        // same idempotent events; a new Session here is the double-charge the design forbids.
        assertEquals(
            RecoveryAction.REPLAY_AND_OBSERVE,
            TaskRecovery.decide(TaskState.QUEUED.name, hasSession = true, heldByLiveSubmit = false),
        )
        assertEquals(
            RecoveryAction.REPLAY_AND_OBSERVE,
            TaskRecovery.decide(TaskState.QUEUED.name, hasSession = true, heldByLiveSubmit = true),
        )
        assertFalse(TaskRecovery.isConverge(RecoveryAction.REPLAY_AND_OBSERVE))
    }

    @Test
    fun `any other active row with a Session only re-attaches its observer`() {
        listOf(TaskState.RUNNING, TaskState.RETRY_WAIT, TaskState.UNKNOWN, TaskState.CANCEL_REQUESTED).forEach {
            listOf(false, true).forEach { held ->
                assertEquals(
                    "$it already has a cloud owner",
                    RecoveryAction.OBSERVE,
                    TaskRecovery.decide(it.name, hasSession = true, held),
                )
            }
        }
        assertFalse(TaskRecovery.isConverge(RecoveryAction.OBSERVE))
    }

    // ---- nothing to recover -------------------------------------------------------------------

    @Test
    fun `a terminal row is never touched, session or not`() {
        TaskRunner.TERMINAL_STATES.forEach {
            listOf(true, false).forEach { hasSession ->
                listOf(true, false).forEach { held ->
                    assertEquals(
                        "$it is already concluded; rewriting it would lose its usage",
                        RecoveryAction.NONE,
                        TaskRecovery.decide(it.name, hasSession, held),
                    )
                }
            }
        }
        assertFalse(TaskRecovery.isConverge(RecoveryAction.NONE))
    }

    @Test
    fun `an unparseable or missing state is ignored rather than converged`() {
        // A row written by a newer build, or a corrupt one, must not be concluded by an older client.
        listOf(null, "", "NOT_A_STATE", "queued", "Succeeded").forEach {
            assertEquals(
                "state `$it` is not something this build understands",
                RecoveryAction.NONE,
                TaskRecovery.decide(it, hasSession = false, heldByLiveSubmit = false),
            )
        }
    }

    @Test
    fun `converging is only ever into a terminal state`() {
        // The two converge actions are the whole of recovery's write surface, and both must land on a
        // state the D-2 guard does not count — otherwise the orphan would still block the next start.
        val convergeActions = RecoveryAction.values().filter { TaskRecovery.isConverge(it) }
        assertEquals(
            setOf(RecoveryAction.CONVERGE_START_INTERRUPTED, RecoveryAction.CONVERGE_CANCELED),
            convergeActions.toSet(),
        )
        listOf(TaskState.FAILED_RETRYABLE, TaskState.CANCELED).forEach {
            assertTrue("$it must be terminal", it in TaskRunner.TERMINAL_STATES)
        }
    }

    // ---- the package status reset -------------------------------------------------------------

    @Test
    fun `a converged run resets its package only under all four guards`() {
        val resets = { status: String, latestTaskId: String?, isLatest: Boolean, others: Int ->
            TaskRecovery.resetsPackage(status, latestTaskId, "task_1", isLatest, others)
        }
        assertTrue(resets("GENERATING", "task_1", true, 0))

        assertFalse("a READY package was never generating", resets("READY", "task_1", true, 0))
        assertFalse("a package that moved on to a newer task keeps its status", resets("GENERATING", "task_2", true, 0))
        assertFalse("a package with no task recorded is not this run's", resets("GENERATING", null, true, 0))
        assertFalse("an older attempt must not reset a package a newer attempt owns", resets("GENERATING", "task_1", false, 0))
        assertFalse("another active run of the same package is still generating", resets("GENERATING", "task_1", true, 1))
        assertFalse(resets("GENERATING", "task_1", true, 2))
    }
}
