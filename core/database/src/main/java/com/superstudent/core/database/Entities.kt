package com.superstudent.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.LocalAccessMode

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: String,
    @ColumnInfo(name = "external_id", index = true) val externalId: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "profile_revision") val profileRevision: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: String?
)

@Entity(
    tableName = "learning_package",
    indices = [Index(value = ["identity_id", "updated_at"])]
)
data class LearningPackageEntity(
    @PrimaryKey @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "identity_id") val identityId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "goal") val goal: String,
    @ColumnInfo(name = "chapter_range") val chapterRange: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "latest_task_id") val latestTaskId: String?,
    @ColumnInfo(name = "source_count") val sourceCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "source_asset",
    indices = [
        Index(value = ["package_id", "upload_state"]),
        // Named to match MIGRATION_3_4's CREATE INDEX verbatim: an unnamed Room index would derive a
        // different name and the migrated-v4 and fresh-v4 identity hashes would not agree.
        Index(
            value = ["upload_state", "delete_pending", "lease_until", "next_retry_at"],
            name = "index_source_asset_recovery",
        ),
    ]
)
data class SourceAssetEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "package_id") val packageId: String,
    /**
     * Null until the content has been read and hashed. Never a placeholder and never built from
     * [displayName] — the object name comes from `sourceId` + sha256 + canonical extension.
     */
    @ColumnInfo(name = "drive_path", index = true) val drivePath: String?,
    /** The user-chosen file name, stored verbatim. The only name any UI surface may render. */
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "kind") val kind: String, // FILE | TEXT
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "upload_state") val uploadState: String,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    @ColumnInfo(name = "added_at") val addedAt: String,
    @ColumnInfo(name = "local_access_mode") val localAccessMode: String = LocalAccessMode.NONE.name,
    @ColumnInfo(name = "canonical_type") val canonicalType: String = CanonicalType.UNKNOWN.name,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    /** Desensitized, template-built text. Never an exception message, URL or server body. */
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retryable") val retryable: Boolean = false,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    /** CAS claim token: a late write-back must match it or it belongs to a superseded attempt. */
    @ColumnInfo(name = "attempt_token") val attemptToken: String? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    /**
     * Which *process* holds the attempt, as opposed to [attemptToken], which identifies the attempt
     * itself. The two are never substituted for one another. Non-null only while UPLOADING.
     */
    @ColumnInfo(name = "lease_owner_id") val leaseOwnerId: String? = null,
    @ColumnInfo(name = "lease_heartbeat_at") val leaseHeartbeatAt: Long? = null,
    @ColumnInfo(name = "upload_started_at") val uploadStartedAt: Long? = null,
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long? = null,
    /**
     * Set when an attempt's lease expired or it stopped making progress, asking the owner to stand
     * down. Non-null is what makes the row present as interrupted instead of still uploading.
     */
    @ColumnInfo(name = "interrupt_requested_at") val interruptRequestedAt: Long? = null,
    /**
     * Reserved for the server-issued source fencing generation. This build has no server fencing
     * (ZLQ-136 C1), so it stays NULL: a locally invented value would be read as authoritative by the
     * backend later, which is worse than the column being empty.
     */
    @ColumnInfo(name = "remote_generation") val remoteGeneration: Long? = null,
    /** Durable delete tombstone. While set, the row owes no upload entry, only 重试删除. */
    @ColumnInfo(name = "delete_pending", defaultValue = "0") val deletePending: Boolean = false,
    @ColumnInfo(name = "delete_requested_at") val deleteRequestedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String = addedAt
)

@Entity(
    tableName = "task_run",
    primaryKeys = ["task_id", "attempt"],
    indices = [Index(value = ["state", "updated_at"])]
)
data class TaskRunEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "attempt") val attempt: Int,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "stage") val stage: String?,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "resume_from_stage") val resumeFromStage: String?,
    @ColumnInfo(name = "last_event_id") val lastEventId: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "credits") val credits: Double,
    /**
     * Cloud `stats.active_seconds` at the moment this attempt went terminal; null means it was never
     * collected. First write per `(taskId, attempt)` wins, so a later read of the same attempt cannot
     * move it.
     */
    @ColumnInfo(name = "active_seconds") val activeSeconds: Double? = null,
    /**
     * The session's wall-clock span snapshotted when this attempt went terminal — not the task's
     * elapsed time, which the UI derives from `started_at`/`finished_at`. Null means never collected.
     */
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double? = null,
    @ColumnInfo(name = "cleanup_pending") val cleanupPending: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "started_at") val startedAt: String?,
    @ColumnInfo(name = "finished_at") val finishedAt: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "artifact",
    indices = [Index(value = ["package_id", "kind"])]
)
data class ArtifactEntity(
    @PrimaryKey @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "kind") val kind: String, // PLAN | CARDS | CITATIONS ...
    @ColumnInfo(name = "drive_path") val drivePath: String,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    @ColumnInfo(name = "cache_state") val cacheState: String, // NONE | CACHED
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "flashcard_progress",
    primaryKeys = ["identity_id", "package_id", "card_id"]
)
data class FlashcardProgressEntity(
    @ColumnInfo(name = "identity_id") val identityId: String,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "mastery") val mastery: String, // NEW | LEARNING | MASTERED
    @ColumnInfo(name = "correct_streak") val correctStreak: Int,
    @ColumnInfo(name = "review_count") val reviewCount: Int,
    @ColumnInfo(name = "last_reviewed_at") val lastReviewedAt: String?,
    @ColumnInfo(name = "next_review_at") val nextReviewAt: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "exercise_progress",
    primaryKeys = ["identity_id", "package_id", "exercise_id"]
)
data class ExerciseProgressEntity(
    @ColumnInfo(name = "identity_id") val identityId: String,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_correct") val lastCorrect: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)
