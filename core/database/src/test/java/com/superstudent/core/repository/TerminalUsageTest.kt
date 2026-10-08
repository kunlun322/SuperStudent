package com.superstudent.core.repository

import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.SessionUsageSnapshot
import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ZLQ-89 §4 idempotency: the first value collected for a `(taskId, attempt)` is the measurement, and
 * the cloud's still-growing `duration_seconds` must never move it afterwards.
 */
class TerminalUsageTest {

    private fun row(
        attempt: Int = 1,
        state: TaskState = TaskState.RUNNING,
        credits: Double = 0.0,
        activeSeconds: Double? = null,
        durationSeconds: Double? = null,
    ) = TaskRunEntity(
        taskId = "task_01",
        attempt = attempt,
        packageId = "pkg_01",
        runId = "run_01",
        sessionId = "sess_01",
        state = state.name,
        stage = null,
        progress = 0,
        resumeFromStage = null,
        lastEventId = null,
        errorCode = null,
        errorMessage = null,
        credits = credits,
        activeSeconds = activeSeconds,
        durationSeconds = durationSeconds,
        createdAt = "2026-09-30T00:00:00Z",
        startedAt = "2026-09-30T00:00:00Z",
        finishedAt = null,
        updatedAt = "2026-09-30T00:00:00Z",
    )

    private fun snapshot(
        activeSeconds: Double? = null,
        durationSeconds: Double? = null,
        totalCredits: Double? = null,
    ) = SessionUsageSnapshot(activeSeconds, durationSeconds, totalCredits)

    /** Folds a sequence of terminal re-entries into the stored row, as the transaction would. */
    private fun replay(vararg snapshots: SessionUsageSnapshot): TaskRunEntity {
        var stored = row()
        for (s in snapshots) {
            val intent = stored.copy(state = TaskState.SUCCEEDED.name, finishedAt = "2026-09-30T01:00:00Z")
            stored = mergeTerminalUsage(stored, intent, s)
        }
        return stored
    }

    @Test
    fun `the same attempt keeps its first duration snapshot across growing re-reads`() {
        val stored = replay(
            snapshot(activeSeconds = 3828.19, durationSeconds = 3828.19),
            snapshot(activeSeconds = 4440.28, durationSeconds = 4440.28),
            snapshot(activeSeconds = 6226.0, durationSeconds = 6226.0),
        )
        assertEquals(3828.19, stored.durationSeconds!!, 0.0)
        assertEquals(3828.19, stored.activeSeconds!!, 0.0)
    }

    @Test
    fun `a second attempt gets its own snapshot`() {
        val first = replay(snapshot(durationSeconds = 3828.19))
        assertEquals(3828.19, first.durationSeconds!!, 0.0)

        var second = row(attempt = 2)
        second = mergeTerminalUsage(
            second,
            second.copy(state = TaskState.SUCCEEDED.name),
            snapshot(durationSeconds = 6226.0),
        )
        assertEquals(6226.0, second.durationSeconds!!, 0.0)
    }

    @Test
    fun `a field the first response lacked is still fillable by a later terminal re-entry`() {
        val stored = replay(
            snapshot(activeSeconds = 3828.19),
            snapshot(activeSeconds = 4440.28, durationSeconds = 4440.28),
        )
        assertEquals(3828.19, stored.activeSeconds!!, 0.0)
        assertEquals(4440.28, stored.durationSeconds!!, 0.0)
    }

    @Test
    fun `a real zero snapshot freezes as zero and is not later overwritten`() {
        val stored = replay(
            snapshot(durationSeconds = 0.0),
            snapshot(durationSeconds = 6226.0),
        )
        assertEquals(0.0, stored.durationSeconds!!, 0.0)
    }

    @Test
    fun `an empty snapshot leaves both counters uncollected and still writes the terminal state`() {
        // A failed usage read must never block the terminal transition.
        val stored = replay(SessionUsageSnapshot.EMPTY)
        assertNull(stored.activeSeconds)
        assertNull(stored.durationSeconds)
        assertEquals(TaskState.SUCCEEDED.name, stored.state)
        assertEquals("2026-09-30T01:00:00Z", stored.finishedAt)
    }

    @Test
    fun `a positive remote total overrides the accumulated credits`() {
        val stored = mergeTerminalUsage(
            row(credits = 12.0),
            row(credits = 12.0).copy(state = TaskState.SUCCEEDED.name),
            snapshot(totalCredits = 22.58),
        )
        assertEquals(22.58, stored.credits, 0.0)
    }

    @Test
    fun `an absent or zero remote total cannot erase the accumulated credits`() {
        val intent = row(credits = 22.58).copy(state = TaskState.SUCCEEDED.name)
        assertEquals(22.58, mergeTerminalUsage(row(credits = 22.58), intent, SessionUsageSnapshot.EMPTY).credits, 0.0)
        assertEquals(
            22.58,
            mergeTerminalUsage(row(credits = 22.58), intent, snapshot(totalCredits = 0.0)).credits,
            0.0,
        )
    }

    @Test
    fun `credits are rounded on the way in`() {
        val stored = mergeTerminalUsage(
            row(),
            row().copy(state = TaskState.SUCCEEDED.name),
            snapshot(totalCredits = 22.579999999999998),
        )
        assertEquals(22.58, stored.credits, 0.0)
    }

    @Test
    fun `a v1 file's forced zeros normalize back to uncollected`() {
        assertNull(restoredSeconds(schemaVersion = 1, value = 0.0))
    }

    @Test
    fun `a v1 file's genuine value is preserved`() {
        assertEquals(3828.19, restoredSeconds(schemaVersion = 1, value = 3828.19)!!, 0.0)
    }

    @Test
    fun `a v2 file maps by field presence, so an explicit zero stays zero`() {
        assertEquals(0.0, restoredSeconds(schemaVersion = 2, value = 0.0)!!, 0.0)
        assertNull(restoredSeconds(schemaVersion = 2, value = null))
    }
}
