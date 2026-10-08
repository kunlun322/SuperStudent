package com.superstudent.core.model

import kotlinx.serialization.Serializable

// ---------- profile.json ----------

/**
 * One ingested qmind source. The sha256 → qmindSourceId mapping drives incremental ingest:
 * an unchanged hash is skipped, a changed one is re-uploaded and recompiled (design §7.2).
 */
@Serializable
data class QmindSourceRefJson(
    val sourceId: String,
    val sourceSha256: String,
    val qmindSourceId: String? = null,
    val displayName: String? = null,
    val ingestedAt: String? = null,
    val deletion: QmindDeletionState = QmindDeletionState.NONE,
)

/**
 * qmind binding. `isolationMode` is always LOGICAL: one Notebook per identity, bound by ID only.
 * This is a naming/mapping convention, NOT platform-enforced permission isolation — a holder of the
 * shared token can list and reach other Notebooks (design §7.2, §6.3).
 */
@Serializable
data class ProfileJson(
    val schemaVersion: Int = 1,
    val identityId: String,
    val externalIdHash: String,
    val displayName: String,
    val isolationMode: String = "LOGICAL",
    val qmindNotebookId: String? = null,
    val qmindNotebookName: String? = null,
    val qmindOwnerUserHash: String? = null,
    val qmindSources: List<QmindSourceRefJson> = emptyList(),
    val qmindDeletion: QmindDeletionState = QmindDeletionState.NONE,
    val deviceId: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    val qmindBound: Boolean get() = !qmindNotebookId.isNullOrBlank()
    val deletePending: Boolean
        get() = qmindDeletion == QmindDeletionState.DELETE_PENDING ||
            qmindSources.any { it.deletion == QmindDeletionState.DELETE_PENDING }
}

// ---------- index.json ----------

@Serializable
data class IndexPackage(
    val packageId: String,
    val title: String,
    val goal: LearningGoal,
    val chapterRange: String? = null,
    val status: PackageStatus = PackageStatus.DRAFT,
    val sourceIds: List<String> = emptyList(),
    val latestTaskId: String? = null,
    val resultKinds: List<ResultKind> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class IndexJson(
    val schemaVersion: Int = 1,
    val identityId: String,
    val revision: Long = 1,
    val updatedAt: String,
    val packages: List<IndexPackage> = emptyList(),
)

// ---------- progress.json ----------

@Serializable
data class PlanProgress(
    val completedTopicIds: List<String> = emptyList(),
    val lastTopicId: String? = null,
)

@Serializable
data class CardProgressJson(
    val cardId: String,
    val mastery: CardMastery = CardMastery.NEW,
    val correctStreak: Int = 0,
    val reviewCount: Int = 0,
    val lastReviewedAt: String? = null,
    val nextReviewAt: String? = null,
    val updatedAt: String,
)

@Serializable
data class ExerciseProgressJson(
    val exerciseId: String,
    val attempts: Int = 0,
    val lastCorrect: Boolean = false,
    val updatedAt: String,
)

@Serializable
data class ProgressPackage(
    val packageId: String,
    val plan: PlanProgress? = null,
    val cards: List<CardProgressJson> = emptyList(),
    val exercises: List<ExerciseProgressJson> = emptyList(),
)

@Serializable
data class ProgressJson(
    val schemaVersion: Int = 1,
    val identityId: String,
    val revision: Long = 1,
    val updatedAt: String,
    val packages: List<ProgressPackage> = emptyList(),
)

// ---------- task.json ----------

@Serializable
data class TaskCheckpoint(
    val stage: TaskStage,
    val state: TaskState,
    val outputSha256: String? = null,
)

/**
 * `message` is user-readable text and the only field the UI may show; `detail` is the cloud's or the
 * exception's raw wording, kept for troubleshooting and never surfaced (FR-13).
 */
@Serializable
data class TaskErrorJson(
    val code: String? = null,
    val message: String? = null,
    val stage: TaskStage? = null,
    val attempt: Int? = null,
    val detail: String? = null,
    val occurredAt: String? = null,
    val lastEventId: String? = null,
    val requestId: String? = null,
)

/**
 * `activeSeconds` is the cloud's `stats.active_seconds`.
 *
 * `durationSeconds` is **not** the task's elapsed time: it is a snapshot of the session's wall-clock
 * span taken at the moment the task reached a terminal state, and it keeps growing on the cloud side
 * afterwards. The UI's duration stays `startedAt → finishedAt` (FR-13).
 *
 * Both are nullable so `explicitNulls = false` can tell "not collected" (field absent) apart from a
 * real zero (field present as `0.0`). A v1 file carries a forced `0` in both, which readers
 * normalize back to null.
 */
@Serializable
data class TaskUsageJson(
    val activeSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val totalCredits: Double = 0.0,
)

@Serializable
data class TaskJson(
    /**
     * 2 since FR-13 added the two nullable seconds counters to [usage]. Readers accept 1 and 2;
     * historical v1 files are never rewritten just to bump this. This numbering is independent of
     * the Room `SsDatabase` version.
     */
    val schemaVersion: Int = 2,
    val taskId: String,
    val packageId: String,
    val attempt: Int,
    val runId: String,
    val sessionId: String? = null,
    val state: TaskState,
    val stage: TaskStage? = null,
    val progressPercent: Int = 0,
    val resumeFromStage: TaskStage? = null,
    val lastEventId: String? = null,
    val checkpoints: List<TaskCheckpoint> = emptyList(),
    val error: TaskErrorJson? = null,
    val usage: TaskUsageJson = TaskUsageJson(),
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val updatedAt: String,
)

// ---------- package.json ----------

@Serializable
data class SourceRefJson(
    val sourceId: String,
    val displayName: String,
    val drivePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: String = "FILE", // FILE | TEXT
    val addedAt: String,
)

@Serializable
data class PackageJson(
    val schemaVersion: Int = 1,
    val packageId: String,
    val identityId: String,
    val title: String,
    val goal: LearningGoal,
    val chapterRange: String? = null,
    val status: PackageStatus = PackageStatus.DRAFT,
    val sources: List<SourceRefJson> = emptyList(),
    val latestTaskId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
