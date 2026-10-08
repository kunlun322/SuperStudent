package com.superstudent.app.features.packages

import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZLQ-114 §5.6 and §8.1 item 11: the UI fallback for a cancel Room recorded while the package row was
 * still `GENERATING`.
 *
 * The point of the item is that the fallback adds no second button set — `CANCELED` is not a running
 * state, so the existing chain already reaches 重新生成 — and that it does not widen the format gate:
 * a package with nothing parseable in it must stay disabled rather than be invited to burn credits by
 * a hint that says otherwise. Both halves are asserted here, the derived property directly and the
 * screen's gating by reading its production source (Compose is not drivable on the JVM in this repo).
 */
class CancelMismatchUiTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    private fun source(
        id: String,
        state: UploadState = UploadState.UPLOADED,
        type: CanonicalType = CanonicalType.PDF,
    ) = SourceAssetEntity(
        sourceId = id,
        packageId = "pkg_01",
        drivePath = if (state == UploadState.UPLOADED) {
            "superstudent/v1/packages/pkg_01/sources/$id/$id-${"a".repeat(12)}.${type.ext}"
        } else {
            null
        },
        displayName = "高等数学第三章 $id.pdf",
        mimeType = type.mimeType,
        kind = "FILE",
        sizeBytes = 2048,
        sha256 = "a".repeat(64),
        uploadState = state.name,
        localUri = null,
        addedAt = "2026-09-30T00:00:00Z",
        canonicalType = type.name,
        retryable = false,
    )

    private fun pkg(status: PackageStatus) = LearningPackageEntity(
        packageId = "pkg_01",
        identityId = "idt_owner",
        title = "初二数学",
        goal = "PREVIEW",
        chapterRange = null,
        status = status.name,
        latestTaskId = "task_01",
        sourceCount = 1,
        createdAt = "2026-09-30T00:00:00Z",
        updatedAt = "2026-09-30T00:00:00Z",
    )

    private fun run(state: TaskState) = TaskRunEntity(
        taskId = "task_01",
        attempt = 1,
        packageId = "pkg_01",
        runId = "run_01",
        sessionId = "sess_01",
        state = state.name,
        stage = null,
        progress = 40,
        resumeFromStage = null,
        lastEventId = null,
        errorCode = "canceled",
        errorMessage = "用户取消",
        credits = 0.0,
        activeSeconds = 12.0,
        durationSeconds = 34.0,
        cleanupPending = true,
        createdAt = "2026-09-30T00:00:01Z",
        startedAt = "2026-09-30T00:00:02Z",
        finishedAt = "2026-09-30T00:10:00Z",
        updatedAt = "2026-09-30T00:10:00Z",
    )

    // ---- item 11, first half: the mismatch with a parseable source ---------------------------

    @Test
    fun `a stale GENERATING package over a canceled run still offers regenerate`() {
        val ui = PackageDetailUi(
            pkg = pkg(PackageStatus.GENERATING),
            sources = listOf(source("src_01")),
            run = run(TaskState.CANCELED),
        )
        assertTrue("the fallback condition is exactly this pair", ui.isCanceledGeneratingMismatch)
        // The existing chain, unchanged: CANCELED is not running, so the button is already reachable.
        assertFalse(ui.isRunning)
        assertTrue(ui.canGenerate)
        assertFalse(ui.blockedByFormat)
    }

    // ---- item 11, second half: the format gate is not widened --------------------------------

    @Test
    fun `the same mismatch with nothing parseable stays disabled, so the fallback burns no credits`() {
        val ui = PackageDetailUi(
            pkg = pkg(PackageStatus.GENERATING),
            sources = listOf(source("src_01", type = CanonicalType.UNKNOWN)),
            run = run(TaskState.CANCELED),
        )
        // The student is still told the status on screen is stale — that is the hint's whole job.
        assertTrue(ui.isCanceledGeneratingMismatch)
        // But the gate the hint points at is untouched: ZLQ-91 P1-4 still refuses a `.bin`-only package.
        assertFalse(ui.canGenerate)
        assertTrue(ui.blockedByFormat)
        assertEquals(0, ui.generatableCount)
        assertEquals("uploaded, just not parseable", 1, ui.uploadedCount)
    }

    @Test
    fun `one parseable source is enough, as in every other gate case`() {
        val ui = PackageDetailUi(
            pkg = pkg(PackageStatus.GENERATING),
            sources = listOf(
                source("src_01", type = CanonicalType.UNKNOWN),
                source("src_02", type = CanonicalType.DOCX),
            ),
            run = run(TaskState.CANCELED),
        )
        assertTrue(ui.canGenerate)
        assertTrue(ui.isCanceledGeneratingMismatch)
        assertFalse(ui.blockedByFormat)
    }

    // ---- the property is specific to the pair it names ---------------------------------------

    @Test
    fun `the property is false for every neighbouring state, so the hint cannot become noise`() {
        val sources = listOf(source("src_01"))
        // Converged — the normal case after the atomic transaction: nothing to explain.
        assertFalse(
            PackageDetailUi(pkg(PackageStatus.READY), sources, run(TaskState.CANCELED))
                .isCanceledGeneratingMismatch
        )
        // GENERATING over a live run is not a mismatch, it is a generation in progress.
        assertFalse(
            PackageDetailUi(pkg(PackageStatus.GENERATING), sources, run(TaskState.RUNNING))
                .isCanceledGeneratingMismatch
        )
        assertFalse(
            PackageDetailUi(pkg(PackageStatus.GENERATING), sources, run(TaskState.CANCEL_REQUESTED))
                .isCanceledGeneratingMismatch
        )
        // No run at all says nothing about a cancel.
        assertFalse(PackageDetailUi(pkg(PackageStatus.GENERATING), sources, null).isCanceledGeneratingMismatch)
        assertFalse(PackageDetailUi(null, sources, run(TaskState.CANCELED)).isCanceledGeneratingMismatch)
    }

    @Test
    fun `a canceled run is not a running state, which is why no second button set was needed`() {
        assertFalse(TaskState.CANCELED.name in PackageDetailUi.RUNNING_STATES)
        assertTrue(TaskState.CANCEL_REQUESTED.name in PackageDetailUi.RUNNING_STATES)
        assertTrue(TaskState.RUNNING.name in PackageDetailUi.RUNNING_STATES)
    }

    // ---- the screen renders the hint and leaves the entry gated -------------------------------

    @Test
    fun `the detail page shows the fallback copy behind its tag and reuses the existing entry`() {
        val screen = read("app/src/main/java/com/superstudent/app/features/packages/PackageDetailScreen.kt")
        val hint = screen.substringAfter("if (ui.isCanceledGeneratingMismatch) {")
            .substringBefore("FlowRow(")
        assertTrue(hint.contains("\"取消已记录，状态正在修复，可重新生成\""))
        assertTrue(hint.contains("Modifier.testTag(\"cancel_mismatch_hint\")"))
        // A hint, not a control: the fallback grew no click target of its own.
        assertFalse(hint.contains("onClick"))
        assertFalse(hint.contains("SsPillButton"))

        // The regenerate entries are still the baseline ones, still enabled by canGenerate — the
        // fallback did not add a third, and did not relax either.
        assertEquals(2, screen.split("enabled = ui.canGenerate").size - 1)
        assertTrue(screen.contains("Modifier.testTag(\"regenerate_button\")"))
        assertTrue(screen.contains("ui.blockedByFormat -> \"资料格式不支持\""))
    }

    @Test
    fun `the list page grew no task-package join for this fallback`() {
        // §5.6: the Room transaction makes the list correct on its own, so no new projection was added.
        val screen = read("app/src/main/java/com/superstudent/app/features/packages/PackageListScreen.kt")
        assertFalse(screen.contains("isCanceledGeneratingMismatch"))
        assertFalse(screen.contains("cancel_mismatch_hint"))
        val viewModel = read("app/src/main/java/com/superstudent/app/features/packages/PackagesViewModel.kt")
        assertEquals(
            "the property is derived in the detail UI state only",
            1,
            viewModel.split("val isCanceledGeneratingMismatch").size - 1,
        )
    }
}
