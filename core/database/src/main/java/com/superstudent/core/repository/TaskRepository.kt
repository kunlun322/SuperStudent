package com.superstudent.core.repository

import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.SsDatabase
import com.superstudent.core.database.TaskRunDao
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.Ids
import com.superstudent.core.model.SessionUsageSnapshot
import com.superstudent.core.model.TaskErrorJson
import com.superstudent.core.model.TaskJson
import com.superstudent.core.model.TaskStage
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.TaskUsageJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant

class TaskRepository(
    private val drive: DriveRepository,
    private val db: SsDatabase,
    private val taskDao: TaskRunDao,
) : CancelCompensationStore {

    private val packageDao = db.packageDao()

    fun observeLatestForPackage(packageId: String): Flow<TaskRunEntity?> =
        taskDao.observeLatestByPackage(packageId)

    fun observeLatestAttempt(taskId: String): Flow<TaskRunEntity?> =
        taskDao.observeLatestAttempt(taskId)

    suspend fun latestForPackage(packageId: String): TaskRunEntity? = withContext(Dispatchers.IO) {
        taskDao.listByPackage(packageId).firstOrNull()
    }

    suspend fun listActive(): List<TaskRunEntity> = withContext(Dispatchers.IO) { taskDao.listActive() }

    suspend fun listActiveWithOwner(): List<ActiveRunRow> = withContext(Dispatchers.IO) {
        taskDao.listActiveWithOwner()
    }

    suspend fun countActiveByIdentity(identityId: String): Int = withContext(Dispatchers.IO) {
        taskDao.countActiveByIdentity(identityId)
    }

    suspend fun findByRunId(runId: String): TaskRunEntity? = withContext(Dispatchers.IO) {
        taskDao.findByRunId(runId)
    }

    suspend fun latestAttempt(taskId: String): TaskRunEntity? = withContext(Dispatchers.IO) {
        taskDao.latestAttempt(taskId)
    }

    suspend fun findBySession(sessionId: String): TaskRunEntity? = withContext(Dispatchers.IO) {
        taskDao.findBySession(sessionId)
    }

    suspend fun get(taskId: String, attempt: Int): TaskRunEntity? = withContext(Dispatchers.IO) {
        taskDao.listAttempts(taskId).firstOrNull { it.attempt == attempt }
    }

    suspend fun listAttempts(taskId: String): List<TaskRunEntity> = withContext(Dispatchers.IO) {
        taskDao.listAttempts(taskId)
    }

    /**
     * New attempt = new runId + (later) new sessionId; taskId is stable across retries.
     *
     * [requestId] is the caller-supplied idempotency token of one start request and is stored as
     * `run_id`: after a process death the redelivered START_NEW intent carries the same requestId,
     * so `findByRunId` finds the attempt it already created instead of creating a second one.
     */
    suspend fun createAttempt(
        packageId: String,
        resumeFromStage: TaskStage?,
        requestId: String? = null,
    ): TaskRunEntity {
        val existing = withContext(Dispatchers.IO) { taskDao.listByPackage(packageId) }
        val taskId = existing.firstOrNull()?.taskId ?: Ids.task()
        val attempt = (existing.maxOfOrNull { it.attempt } ?: 0) + 1
        val now = Instant.now().toString()
        val entity = TaskRunEntity(
            taskId = taskId,
            attempt = attempt,
            packageId = packageId,
            runId = requestId ?: Ids.run(),
            sessionId = null,
            state = TaskState.QUEUED.name,
            stage = TaskStage.UPLOAD.name,
            progress = 0,
            resumeFromStage = resumeFromStage?.name,
            lastEventId = null,
            errorCode = null,
            errorMessage = null,
            credits = 0.0,
            createdAt = now,
            startedAt = null,
            finishedAt = null,
            updatedAt = now,
        )
        withContext(Dispatchers.IO) { taskDao.upsert(entity) }
        return entity
    }

    suspend fun save(entity: TaskRunEntity) = withContext(Dispatchers.IO) {
        taskDao.upsert(entity.copy(updatedAt = Instant.now().toString()))
    }

    /**
     * Recovery terminal writes (ZLQ-103 §3). Both are conditional and return the number of rows they
     * actually moved: 0 means the row already has a Session or already went terminal, in which case
     * the caller must leave it to its observer rather than writing over it.
     */
    suspend fun convergeInterrupted(
        taskId: String,
        attempt: Int,
        errorCode: String,
        errorMessage: String,
    ): Int = withContext(Dispatchers.IO) {
        taskDao.convergeInterrupted(taskId, attempt, errorCode, errorMessage, Instant.now().toString())
    }

    suspend fun convergeCanceled(taskId: String, attempt: Int): Int = withContext(Dispatchers.IO) {
        taskDao.convergeCanceled(taskId, attempt, Instant.now().toString())
    }

    /** Other active runs of the same package — the second half of the package reset guard. */
    suspend fun countOtherActiveForPackage(packageId: String, taskId: String, attempt: Int): Int =
        withContext(Dispatchers.IO) {
            taskDao.countActiveForPackageExcluding(packageId, taskId, attempt)
        }

    /** Local-only `GENERATING` → `READY`; no Drive write, so convergence never needs the network. */
    suspend fun packageBackToReady(packageId: String, taskId: String): Int =
        withContext(Dispatchers.IO) {
            packageDao.backToReadyIfGenerating(packageId, taskId, Instant.now().toString())
        }

    /**
     * Writes the cancel terminal and resets the package in one transaction (ZLQ-114 §5.2), and returns
     * both facts the caller has to act on: the canonical row re-read inside the same transaction, and
     * whether the package was actually moved.
     *
     * `cleanup_pending` is written set, unconditionally. It is the marker that this attempt's
     * compensation — remote Session stopped, its tmp subtree deleted, the terminal projected onto the
     * remote manifest — has not been proven complete yet, and an offline cancel cannot prove any of it
     * from inside the transaction. Clearing is the compensator's compare-and-swap, never this call's.
     *
     * `packageRestored == false` is not a failure: a newer attempt or another active run owns the
     * package now, and the guarded UPDATE declining to touch it is the guard working.
     */
    suspend fun concludeCanceledAndRestorePackage(
        intent: TaskRunEntity,
        snapshot: SessionUsageSnapshot,
    ): CancelConclusion? = withContext(Dispatchers.IO) {
        db.inTransaction {
            val current = taskDao.listAttempts(intent.taskId)
                .firstOrNull { it.attempt == intent.attempt }
                ?: return@inTransaction null
            val now = Instant.now().toString()
            // Same first-write-wins merge as concludeTerminal: a cancel must not overwrite usage the
            // row already froze, and must not lose the snapshot this call was handed.
            val merged = mergeTerminalUsage(current, intent, snapshot).copy(
                state = TaskState.CANCELED.name,
                finishedAt = now,
                updatedAt = now,
                cleanupPending = true,
            )
            taskDao.upsert(merged)
            val restored = packageDao.resetToReadyAfterCancel(
                merged.packageId,
                merged.taskId,
                merged.attempt,
                now,
            ) > 0
            val canonical = taskDao.listAttempts(merged.taskId)
                .firstOrNull { it.attempt == merged.attempt }
            CancelConclusion(row = canonical ?: merged, packageRestored = restored)
        }
    }

    /** The `CANCELED` + package-`GENERATING` mismatch rows a cold start has to converge locally. */
    override suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow> =
        withContext(Dispatchers.IO) { taskDao.listCanceledGeneratingMismatch() }

    override suspend fun resetPackageAfterCancel(packageId: String, taskId: String, attempt: Int): Int =
        withContext(Dispatchers.IO) {
            packageDao.resetToReadyAfterCancel(packageId, taskId, attempt, Instant.now().toString())
        }

    /** Backfills the marker on a session-bearing historical mismatch row; 0 rows is a normal no-op. */
    override suspend fun markCancelCleanupPending(taskId: String, attempt: Int): Int =
        withContext(Dispatchers.IO) {
            taskDao.markCancelCleanupPending(taskId, attempt, Instant.now().toString())
        }

    override suspend fun listCancelCleanupPending(): List<TaskRunEntity> =
        withContext(Dispatchers.IO) { taskDao.listCancelCleanupPending() }

    override suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow? =
        withContext(Dispatchers.IO) { taskDao.findAttemptWithOwner(taskId, attempt) }

    override suspend fun clearCancelCleanupPending(taskId: String, attempt: Int): Int =
        withContext(Dispatchers.IO) {
            taskDao.clearCancelCleanupPending(taskId, attempt, Instant.now().toString())
        }

    /**
     * The one place a run is allowed to reach a terminal state (ZLQ-89 §4). Reads the stored row,
     * merges the usage snapshot first-write-wins, writes, and returns the canonical row read back
     * from the same transaction.
     *
     * The caller must publish `task.json` and finalize from that returned row, never from the one it
     * passed in: [intent] predates the merge, so re-publishing it would drop the snapshot this call
     * just froze. Returns null when the attempt no longer exists, leaving the caller's row untouched.
     */
    suspend fun concludeTerminal(
        intent: TaskRunEntity,
        snapshot: SessionUsageSnapshot,
    ): TaskRunEntity? = withContext(Dispatchers.IO) {
        db.inTransaction {
            val current = taskDao.listAttempts(intent.taskId)
                .firstOrNull { it.attempt == intent.attempt }
                ?: return@inTransaction null
            val merged = mergeTerminalUsage(current, intent, snapshot)
                .copy(updatedAt = Instant.now().toString())
            taskDao.upsert(merged)
            taskDao.listAttempts(merged.taskId).firstOrNull { it.attempt == merged.attempt }
        }
    }

    /**
     * Mirrors the local row into Drive task.json (best effort; Drive is the recovery source).
     * [error] carries the classified failure the caller just produced; without it the row's own
     * code/message are used, which is all a restored or pre-classification row has.
     *
     * All three usage fields come from [entity], so a terminal publish must pass the canonical row
     * [concludeTerminal] returned — passing the pre-merge one would write task.json without the
     * snapshot that was just frozen in Room.
     */
    suspend fun publishTaskJson(
        identityId: String,
        entity: TaskRunEntity,
        error: TaskErrorJson? = null,
    ) {
        withContext(Dispatchers.IO) {
            val json = TaskJson(
                taskId = entity.taskId,
                packageId = entity.packageId,
                attempt = entity.attempt,
                runId = entity.runId,
                sessionId = entity.sessionId,
                state = TaskState.valueOf(entity.state),
                stage = entity.stage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() },
                progressPercent = entity.progress,
                resumeFromStage = entity.resumeFromStage?.let {
                    runCatching { TaskStage.valueOf(it) }.getOrNull()
                },
                lastEventId = entity.lastEventId,
                checkpoints = emptyList(),
                error = error ?: if (entity.errorCode == null && entity.errorMessage == null) {
                    null
                } else {
                    TaskErrorJson(
                        code = entity.errorCode,
                        message = entity.errorMessage,
                        stage = entity.stage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() },
                        attempt = entity.attempt,
                        lastEventId = entity.lastEventId,
                    )
                },
                usage = TaskUsageJson(
                    activeSeconds = entity.activeSeconds,
                    durationSeconds = entity.durationSeconds,
                    totalCredits = entity.credits,
                ),
                createdAt = entity.createdAt,
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
                updatedAt = Instant.now().toString(),
            )
            runCatching {
                drive.writeJson(
                    identityId,
                    DrivePath.taskJson(entity.packageId, entity.taskId),
                    json,
                    TaskJson.serializer(),
                )
            }
        }
    }
}

/**
 * What one `concludeCanceledAndRestorePackage` transaction produced (ZLQ-114 §5.2): the canonical row
 * re-read inside that transaction, and whether the guarded UPDATE moved the package.
 *
 * The caller must publish `task.json` and compensate from [row], never from the intent it passed in —
 * the intent predates the usage merge.
 */
data class CancelConclusion(
    val row: TaskRunEntity,
    val packageRestored: Boolean,
)

/**
 * The cancel-compensation slice of [TaskRepository] (ZLQ-114 §5.4 / §5.5).
 *
 * Narrow on purpose, the same reason `ManifestStore` exists: the compensator and the cold-start rule
 * are plain Kotlin, so a JVM test can drive them against a fake instead of needing a Room database.
 */
interface CancelCompensationStore {
    /** `CANCELED` runs whose package is still `GENERATING`, joined to the owning identity. */
    suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow>

    /** The §5.2 guarded package reset, on its own — for a cold start converging rows it did not write. */
    suspend fun resetPackageAfterCancel(packageId: String, taskId: String, attempt: Int): Int

    /** Backfills the marker on a session-bearing historical mismatch row. Returns rows moved. */
    suspend fun markCancelCleanupPending(taskId: String, attempt: Int): Int

    /** `CANCELED + cleanup_pending = 1`: one row is one compensation enqueue. */
    suspend fun listCancelCleanupPending(): List<TaskRunEntity>

    /**
     * The exact attempt plus its owning identity. Null means the row or its package is gone, which
     * compensation treats as done. Identity is the package owner's, never the logged-in account's.
     */
    suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow?

    /** Compare-and-swap marker clear. Returns rows moved; 0 means someone else already cleared it. */
    suspend fun clearCancelCleanupPending(taskId: String, attempt: Int): Int
}
