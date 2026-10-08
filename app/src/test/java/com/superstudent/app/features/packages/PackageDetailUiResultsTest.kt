package com.superstudent.app.features.packages

import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.TaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDetailUiResultsTest {

    private val fiveKinds = ResultKind.entries.toSet()

    private fun run(state: TaskState) = TaskRunEntity(
        taskId = "task_01",
        attempt = 2,
        packageId = "pkg_01",
        runId = "drun_01",
        sessionId = null,
        state = state.name,
        stage = null,
        progress = 0,
        resumeFromStage = null,
        lastEventId = null,
        errorCode = null,
        errorMessage = null,
        credits = 0.0,
        cleanupPending = false,
        createdAt = "2026-09-30T00:00:00Z",
        startedAt = null,
        finishedAt = null,
        updatedAt = "2026-09-30T00:00:00Z",
    )

    @Test
    fun `published results stay reachable while a new version generates`() {
        PackageDetailUi.RUNNING_STATES.forEach { state ->
            val ui = PackageDetailUi(run = run(TaskState.valueOf(state)), publishedKinds = fiveKinds)
            assertTrue(state, ui.isRunning)
            assertTrue(state, ui.hasResults)
        }
    }

    @Test
    fun `published results stay reachable after cancel and after failure`() {
        listOf(TaskState.CANCELED, TaskState.FAILED_RETRYABLE, TaskState.FAILED_PERMANENT).forEach { state ->
            val ui = PackageDetailUi(run = run(state), publishedKinds = fiveKinds)
            assertFalse(state.name, ui.isRunning)
            assertTrue(state.name, ui.hasResults)
        }
    }

    @Test
    fun `a package that never published results has no results entry`() {
        assertFalse(PackageDetailUi(run = run(TaskState.SUCCEEDED)).hasResults)
        assertFalse(PackageDetailUi(run = run(TaskState.RUNNING)).hasResults)
        assertFalse(PackageDetailUi().hasResults)
    }

    @Test
    fun `a single published kind is enough to open the results page`() {
        assertTrue(PackageDetailUi(publishedKinds = setOf(ResultKind.PLAN)).hasResults)
    }
}
