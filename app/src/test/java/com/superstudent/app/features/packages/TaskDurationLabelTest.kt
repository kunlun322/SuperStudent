package com.superstudent.app.features.packages

import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-13's two durations are different measurements and must stay that way (ZLQ-89 §4): the task card
 * shows how long the student waited, `startedAt → finishedAt`; `usage.duration_seconds` is the
 * cloud's wall-clock for the Session and belongs to task.json only. These pin that the new columns
 * did not become the label's source.
 */
class TaskDurationLabelTest {

    private fun run(
        startedAt: String?,
        finishedAt: String?,
        activeSeconds: Double? = null,
        durationSeconds: Double? = null,
    ) = TaskRunEntity(
        taskId = "task_01",
        attempt = 1,
        packageId = "pkg_01",
        runId = "drun_01",
        sessionId = "sess_01",
        state = TaskState.SUCCEEDED.name,
        stage = null,
        progress = 100,
        resumeFromStage = null,
        lastEventId = null,
        errorCode = null,
        errorMessage = null,
        credits = 12.5,
        activeSeconds = activeSeconds,
        durationSeconds = durationSeconds,
        cleanupPending = false,
        createdAt = "2026-09-30T00:00:00Z",
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = "2026-09-30T00:00:00Z",
    )

    @Test
    fun `the label is unchanged by the session usage counters`() {
        val started = "2026-09-30T01:02:03Z"
        val finished = "2026-09-30T01:08:00Z"
        val before = durationLabel(run(started, finished))
        val after = durationLabel(run(started, finished, activeSeconds = 3828.19, durationSeconds = 4440.28))
        assertEquals("5 分 57 秒", before)
        assertEquals(before, after)
    }

    @Test
    fun `the label renders seconds under a minute and minutes above it`() {
        assertEquals("45 秒", durationLabel(run("2026-09-30T01:00:00Z", "2026-09-30T01:00:45Z")))
        assertEquals("5 分 50 秒", durationLabel(run("2026-09-30T01:00:00Z", "2026-09-30T01:05:50Z")))
        assertEquals("0 秒", durationLabel(run("2026-09-30T01:00:00Z", "2026-09-30T01:00:00Z")))
    }

    @Test
    fun `a run that has not finished, or whose stamps are unusable, shows no duration`() {
        assertNull(durationLabel(run("2026-09-30T01:00:00Z", null)))
        assertNull(durationLabel(run(null, "2026-09-30T01:00:00Z")))
        // A negative span is a broken clock, not a duration; the cloud's counters cannot rescue it
        // because they were never this label's source.
        assertNull(durationLabel(run("2026-09-30T01:08:00Z", "2026-09-30T01:02:03Z", durationSeconds = 4440.28)))
        assertNull(durationLabel(run("not-a-timestamp", "2026-09-30T01:02:03Z")))
    }
}
