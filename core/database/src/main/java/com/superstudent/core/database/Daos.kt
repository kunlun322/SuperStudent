package com.superstudent.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE identity_id = :identityId")
    suspend fun findById(identityId: String): AccountEntity?

    @Query("SELECT * FROM account WHERE external_id = :externalId")
    suspend fun findByExternalId(externalId: String): AccountEntity?

    @Query("SELECT * FROM account")
    suspend fun all(): List<AccountEntity>

    @Query("DELETE FROM account WHERE identity_id = :identityId")
    suspend fun delete(identityId: String)
}

@Dao
interface PackageDao {
    @Upsert
    suspend fun upsert(pkg: LearningPackageEntity)

    @Upsert
    suspend fun upsertAll(pkgs: List<LearningPackageEntity>)

    @Query("SELECT * FROM learning_package WHERE identity_id = :identityId ORDER BY updated_at DESC")
    fun observeByIdentity(identityId: String): Flow<List<LearningPackageEntity>>

    @Query("SELECT * FROM learning_package WHERE identity_id = :identityId ORDER BY updated_at DESC")
    suspend fun listByIdentity(identityId: String): List<LearningPackageEntity>

    @Query("SELECT * FROM learning_package WHERE package_id = :packageId AND identity_id = :identityId")
    suspend fun find(identityId: String, packageId: String): LearningPackageEntity?

    @Query("SELECT * FROM learning_package WHERE package_id = :packageId AND identity_id = :identityId")
    fun observe(identityId: String, packageId: String): Flow<LearningPackageEntity?>

    @Query("DELETE FROM learning_package WHERE package_id = :packageId AND identity_id = :identityId")
    suspend fun delete(identityId: String, packageId: String)

    /** Local-only reset used by recovery; a Drive publish would make convergence network-dependent. */
    @Query(TaskRunRecoverySql.PACKAGE_BACK_TO_READY)
    suspend fun backToReadyIfGenerating(packageId: String, taskId: String, now: String): Int

    /**
     * The cancel-time reset (ZLQ-114 §5.2). Every guard is inside the statement, so unlike
     * [backToReadyIfGenerating] the caller supplies no pre-read counts — a count read before the write
     * is the race this replaces. 0 rows means a guard failed, which the caller treats as "the package
     * moved on", not as an error.
     */
    @Query(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL)
    suspend fun resetToReadyAfterCancel(packageId: String, taskId: String, attempt: Int, now: String): Int
}

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceAssetEntity)

    @Upsert
    suspend fun upsertAll(sources: List<SourceAssetEntity>)

    /** Row creation happens once, inside the picker callback. A retry must never insert. */
    @Insert
    suspend fun insert(source: SourceAssetEntity): Long

    @Query("SELECT * FROM source_asset WHERE package_id = :packageId ORDER BY added_at ASC")
    fun observeByPackage(packageId: String): Flow<List<SourceAssetEntity>>

    @Query("SELECT * FROM source_asset WHERE package_id = :packageId ORDER BY added_at ASC")
    suspend fun listByPackage(packageId: String): List<SourceAssetEntity>

    @Query("SELECT * FROM source_asset WHERE source_id = :sourceId")
    suspend fun find(sourceId: String): SourceAssetEntity?

    @Query("SELECT * FROM source_asset WHERE drive_path = :drivePath")
    suspend fun findByPath(drivePath: String): SourceAssetEntity?

    // ---- conditional state transitions (ZLQ-130 / ZLQ-132 design §5.4) ----
    //
    // Room is the only business-state source. Three rules hold for every statement below:
    //
    // 1. An ownership write matches `source_id + attempt_token + lease_owner_id` together. The token
    //    names one attempt, the owner names one process; substituting either for the other is how a
    //    dead process's late PUT used to be able to commit over a live one.
    // 2. A business update carries `delete_pending = 0`. A tombstoned row owes no upload, no success
    //    and no failure — only the delete worker may write it.
    // 3. Callers act on the returned row count: 0 means a guard failed and the row moved on without
    //    them, which is never an error to retry.
    //
    // Lifecycle paths use these guarded UPDATEs rather than the whole-row `@Upsert` above, which
    // would overwrite a token and an owner another attempt is still holding. `@Upsert` remains for
    // the bulk restore in `RestoreRepository`, which writes rows no attempt owns yet.

    /**
     * Claims one upload attempt.
     *
     * Deliberately has no `UPLOADING` branch. A row stuck in `UPLOADING` is released only by
     * [interruptAttempt], which the recovery coordinator runs while holding the previous owner's OS
     * lock; letting a claim take an expired lease directly is what allowed two concurrent PUTs of one
     * object, and design §3.2's 「不得回收」 case forbids exactly that.
     *
     * Both entry states are budget- and due-checked, so no path reaches an attempt by way of
     * `PENDING` to escape the cap.
     */
    @Query(SourceRecoverySql.CLAIM)
    suspend fun claimForUpload(
        sourceId: String,
        token: String,
        ownerId: String,
        leaseUntilMillis: Long,
        heartbeatAt: Long,
        uploadStartedAt: Long,
        lastProgressAt: Long,
        nowMillis: Long,
        maxAttempts: Int,
        now: String
    ): Int

    /**
     * Extends a live attempt's lease. 0 rows means the attempt was interrupted, tombstoned, or
     * superseded — the owner's signal to stop reading and stand down instead of finishing a PUT
     * nobody will commit. This guard is what makes design §3.2 condition 4 work: the recovery side
     * only writes `interrupt_requested_at`, and the owner cancels itself here.
     */
    @Query(SourceRecoverySql.RENEW_LEASE)
    suspend fun renewLease(
        sourceId: String,
        token: String,
        ownerId: String,
        heartbeatAt: Long,
        leaseUntilMillis: Long,
        now: String
    ): Int

    /** Progress flush: [renewLease]'s guard plus the timestamp the no-progress rule reads. */
    @Query(SourceRecoverySql.RECORD_PROGRESS)
    suspend fun recordProgress(
        sourceId: String,
        token: String,
        ownerId: String,
        progressAt: Long,
        heartbeatAt: Long,
        leaseUntilMillis: Long,
        now: String
    ): Int

    /**
     * Whether this attempt still owns the row, asked inside the manifest lock right before publishing
     * (ZLQ-110 §2.3). A coroutine whose lease expired and was claimed by a newer attempt must not
     * publish a projection of its own; `markUploaded` already guards on the token, but by then the
     * two cloud manifests would have been written.
     *
     * Adding a query does not change the schema: Room's identity hash covers tables, not statements.
     */
    @Query(SourceRecoverySql.COUNT_OWNED_ATTEMPT)
    suspend fun countOwnedAttempt(sourceId: String, token: String, ownerId: String): Int

    /** Terminal success: the object is on Drive and `package.json` has been re-published. */
    @Query(SourceRecoverySql.MARK_UPLOADED)
    suspend fun markUploaded(
        sourceId: String,
        token: String,
        ownerId: String,
        drivePath: String,
        sha256: String,
        sizeBytes: Long,
        canonicalType: String,
        now: String
    ): Int

    /**
     * Writes a classified failure. [drivePath]/[sha256]/[sizeBytes]/[canonicalType] are optional:
     * pass them when the PUT already succeeded (manifest publish failure) so a retry overwrites the
     * same object instead of creating a second one.
     */
    @Query(SourceRecoverySql.RECORD_FAILURE)
    suspend fun recordFailure(
        sourceId: String,
        token: String,
        ownerId: String,
        state: String,
        errorCode: String,
        errorMessage: String,
        retryable: Boolean,
        nextRetryAtMillis: Long?,
        drivePath: String?,
        sha256: String?,
        sizeBytes: Long?,
        canonicalType: String?,
        now: String
    ): Int

    /**
     * The picked file can no longer be read (grant revoked, original deleted, restore after
     * reinstall). Never silent: the row stays visible and offers "重新选择文件".
     */
    @Query(SourceRecoverySql.MARK_LOCAL_ONLY)
    suspend fun markLocalOnly(
        sourceId: String,
        localAccessMode: String,
        errorCode: String,
        errorMessage: String,
        now: String
    ): Int

    /**
     * Asks a live owner to stand down without touching anything else (design §3.2 「不得回收」).
     *
     * This is the whole of what a recovery pass may do when the owner's OS lock is still held and the
     * source execution lock could not be taken: the row keeps its `UPLOADING` state and its token, so
     * no second PUT can start, and the owner's next [renewLease] returns 0.
     */
    @Query(SourceRecoverySql.REQUEST_INTERRUPT)
    suspend fun requestInterrupt(sourceId: String, nowMillis: Long, now: String): Int

    /**
     * Releases an orphaned `UPLOADING` row in one guarded statement, run only while the caller holds
     * the previous owner's OS lock and the source execution lock.
     *
     * `attempt_count` is *not* incremented: the recovery is not an attempt, and counting it would let
     * a crash loop spend the student's retry budget without ever uploading (design §3.2).
     */
    @Query(SourceRecoverySql.INTERRUPT_ATTEMPT)
    suspend fun interruptAttempt(sourceId: String, nowMillis: Long, maxAttempts: Int, now: String): Int

    /**
     * Arms the durable delete tombstone (design §3.9 item 1). One transaction, and the first of the
     * two writes a delete performs — the row itself goes only in [finishDelete], after the remote
     * tombstone and the manifest readback succeeded.
     *
     * `attempt_token` is cleared so an in-flight attempt's `markUploaded` guard returns 0: that is
     * what fences a PUT that was already in the air when the student pressed 删除.
     */
    @Query(SourceRecoverySql.BEGIN_DELETE)
    suspend fun beginDelete(sourceId: String, nowMillis: Long, now: String): Int

    /** The delete worker's last step. Guarded so only a tombstoned row can disappear. */
    @Query(SourceRecoverySql.FINISH_DELETE)
    suspend fun finishDelete(sourceId: String): Int

    /**
     * Manual "重试上传": a failed row goes back to `PENDING` for immediate execution.
     *
     * The attempt counter is reset because a manual retry opens a *new* auto-retry cycle: `retryable`
     * only ever means "the scheduler may retry this", so a student who taps retry after the budget was
     * exhausted must get a full budget again rather than an immediate re-exhaustion (ZLQ-110 §3.3).
     * `sourceId`, `sha256` and `drive_path` are untouched, so the retry still overwrites one object.
     *
     * Only `FAILED` qualifies. An interrupted `UPLOADING` row is not requeued here — it goes through
     * [interruptAttempt] under the owner lock first, so that tapping 重试 can never start a second PUT
     * while the old owner is still alive.
     */
    @Query(SourceRecoverySql.REQUEUE_FOR_RETRY)
    suspend fun requeueForRetry(sourceId: String, now: String): Int

    /**
     * Binds a new local handle to an existing row ("重新选择文件"), opening a fresh retry cycle.
     *
     * `size_bytes` is written by this statement instead of by a read-then-`upsert` in the repository:
     * the old shape let a concurrent attempt's guarded UPDATE land between the read and the write and
     * be clobbered wholesale by the row that was read before it (design §5.4).
     */
    @Query(SourceRecoverySql.BIND_LOCAL_FILE)
    suspend fun bindLocalFile(
        sourceId: String,
        localUri: String?,
        localAccessMode: String,
        sizeBytes: Long?,
        state: String,
        errorCode: String?,
        errorMessage: String?,
        retryable: Boolean,
        now: String
    ): Int

    /** App-start / network-recovery scan for work that still owes an upload. */
    @Query(SourceRecoverySql.LIST_RESUMABLE)
    suspend fun listResumable(nowMillis: Long, maxAttempts: Int): List<SourceAssetEntity>

    /**
     * Everything the recovery coordinator owes an action on: tombstones to push, `UPLOADING` rows
     * whose lease ran out or that stopped reporting progress, and uploads that are due.
     *
     * Separate from [listResumable] because the two have different owners — the sweeper enqueues
     * uploads, while this scan may also interrupt an attempt and re-enqueue a delete.
     *
     * @param noProgressBefore `now - SOURCE_NO_PROGRESS_MILLIS`; design §3.2 condition 4.
     */
    @Query(SourceRecoverySql.LIST_RECOVERY_CANDIDATES)
    suspend fun listRecoveryCandidates(
        nowMillis: Long,
        noProgressBefore: Long,
        maxAttempts: Int,
    ): List<SourceAssetEntity>

    /**
     * The earliest moment any row becomes actionable, used to arm exactly one follow-up pass.
     *
     * A tombstone's own timestamp is included so a delete whose worker died is picked up again
     * instead of waiting for the next process start. Rows that are actionable *now* — an `UPLOADING`
     * with no `lease_until`, left over from before the column existed — contribute nothing here on
     * purpose: the current pass already claims them, and a deadline in the past would arm a follow-up
     * that fires immediately and re-arms itself.
     */
    @Query(SourceRecoverySql.NEXT_RECOVERY_DEADLINE)
    suspend fun nextRecoveryDeadline(maxAttempts: Int, nowMillis: Long): Long?

    /**
     * Rows whose auto-retry budget is spent on a failure that was *believed transient*. These are the
     * only candidates for the startup reconciliation, because they are exactly the rows a local read
     * failure could have been misclassified into before the read stage normalized its exceptions
     * (ZLQ-105): `NETWORK_UNAVAILABLE` used to catch every `IOException`, a deleted original included.
     *
     * Deliberately excludes the genuinely permanent codes — a `FILE_TOO_LARGE` or `UNSUPPORTED_FORMAT`
     * row must keep its own message, not be re-probed into "re-select the file" — and excludes
     * `MANIFEST_PUBLISH_FAILED`, whose object is already on Drive and belongs to the manifest
     * reconciliation instead.
     */
    @Query(SourceRecoverySql.LIST_EXHAUSTED_TRANSIENT_FAILURES)
    suspend fun listExhaustedTransientFailures(): List<SourceAssetEntity>

    @Query(
        "SELECT * FROM source_asset WHERE package_id = :packageId AND upload_state = 'UPLOADING' " +
            "ORDER BY added_at ASC"
    )
    suspend fun listUploading(packageId: String): List<SourceAssetEntity>

    @Query("DELETE FROM source_asset WHERE source_id = :sourceId")
    suspend fun delete(sourceId: String)

    @Query("DELETE FROM source_asset WHERE package_id = :packageId")
    suspend fun deleteByPackage(packageId: String)
}

@Dao
interface TaskRunDao {
    @Upsert
    suspend fun upsert(task: TaskRunEntity)

    @Query("SELECT * FROM task_run WHERE task_id = :taskId ORDER BY attempt DESC")
    suspend fun listAttempts(taskId: String): List<TaskRunEntity>

    @Query("SELECT * FROM task_run WHERE task_id = :taskId ORDER BY attempt DESC LIMIT 1")
    suspend fun latestAttempt(taskId: String): TaskRunEntity?

    @Query("SELECT * FROM task_run WHERE task_id = :taskId ORDER BY attempt DESC LIMIT 1")
    fun observeLatestAttempt(taskId: String): Flow<TaskRunEntity?>

    @Query("SELECT * FROM task_run WHERE package_id = :packageId ORDER BY updated_at DESC")
    suspend fun listByPackage(packageId: String): List<TaskRunEntity>

    @Query("SELECT * FROM task_run WHERE package_id = :packageId ORDER BY updated_at DESC LIMIT 1")
    fun observeLatestByPackage(packageId: String): Flow<TaskRunEntity?>

    @Query("SELECT * FROM task_run WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','RETRY_WAIT','UNKNOWN') ORDER BY updated_at DESC")
    suspend fun listActive(): List<TaskRunEntity>

    @Query("SELECT * FROM task_run WHERE session_id = :sessionId LIMIT 1")
    suspend fun findBySession(sessionId: String): TaskRunEntity?

    @Query("SELECT COUNT(*) FROM task_run WHERE task_id = :taskId")
    suspend fun countAttempts(taskId: String): Int

    // ---- sessionless-run recovery (ZLQ-103 §3) ----
    //
    // A run whose `session_id` is still NULL was never handed to the cloud, so no observer can ever
    // conclude it. These guarded UPDATEs are the only way such a row reaches a terminal state, and
    // the guard is what keeps a concurrent live submit from being overwritten by a stale recovery
    // pass. Callers must act on the returned row count: 0 means the row moved on without them.

    @Query(TaskRunRecoverySql.FIND_BY_RUN_ID)
    suspend fun findByRunId(runId: String): TaskRunEntity?

    @Query(TaskRunRecoverySql.LIST_ACTIVE_WITH_OWNER)
    suspend fun listActiveWithOwner(): List<ActiveRunRow>

    @Query(TaskRunRecoverySql.COUNT_ACTIVE_BY_IDENTITY)
    suspend fun countActiveByIdentity(identityId: String): Int

    @Query(TaskRunRecoverySql.COUNT_ACTIVE_FOR_PACKAGE_EXCLUDING)
    suspend fun countActiveForPackageExcluding(packageId: String, taskId: String, attempt: Int): Int

    @Query(TaskRunRecoverySql.CONVERGE_INTERRUPTED)
    suspend fun convergeInterrupted(
        taskId: String,
        attempt: Int,
        errorCode: String,
        errorMessage: String,
        now: String,
    ): Int

    @Query(TaskRunRecoverySql.CONVERGE_CANCELED)
    suspend fun convergeCanceled(taskId: String, attempt: Int, now: String): Int

    // ---- offline-cancel compensation (ZLQ-114 §5.4 / §5.5) ----
    //
    // `cleanup_pending` on a `CANCELED` row means "this attempt's terminal compensation is not fully
    // complete": remote Session stopped, this attempt's tmp subtree gone, and the terminal state
    // projected onto the remote manifest. It is a marker, not a state — the row is already terminal,
    // so no `TaskState` semantics change and nothing here may resurrect a run.

    @Query(TaskRunRecoverySql.LIST_CANCELED_GENERATING_MISMATCH)
    suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow>

    @Query(TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING)
    suspend fun markCancelCleanupPending(taskId: String, attempt: Int, now: String): Int

    @Query(TaskRunRecoverySql.LIST_CANCEL_CLEANUP_PENDING)
    suspend fun listCancelCleanupPending(): List<TaskRunEntity>

    @Query(TaskRunRecoverySql.FIND_ATTEMPT_WITH_OWNER)
    suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow?

    @Query(TaskRunRecoverySql.CLEAR_CANCEL_CLEANUP_PENDING)
    suspend fun clearCancelCleanupPending(taskId: String, attempt: Int, now: String): Int
}

@Dao
interface ArtifactDao {
    @Upsert
    suspend fun upsert(artifact: ArtifactEntity)

    @Upsert
    suspend fun upsertAll(artifacts: List<ArtifactEntity>)

    @Query("SELECT * FROM artifact WHERE package_id = :packageId AND kind = :kind ORDER BY updated_at DESC LIMIT 1")
    suspend fun findLatest(packageId: String, kind: String): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE package_id = :packageId AND kind = :kind ORDER BY updated_at DESC LIMIT 1")
    fun observeLatest(packageId: String, kind: String): Flow<ArtifactEntity?>

    @Query("SELECT * FROM artifact WHERE package_id = :packageId ORDER BY updated_at DESC")
    suspend fun listByPackage(packageId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifact WHERE package_id = :packageId ORDER BY updated_at DESC")
    fun observeByPackage(packageId: String): Flow<List<ArtifactEntity>>

    @Query("DELETE FROM artifact WHERE package_id = :packageId")
    suspend fun deleteByPackage(packageId: String)
}

@Dao
interface FlashcardProgressDao {
    @Upsert
    suspend fun upsert(progress: FlashcardProgressEntity)

    @Upsert
    suspend fun upsertAll(list: List<FlashcardProgressEntity>)

    @Query(
        "SELECT * FROM flashcard_progress WHERE identity_id = :identityId AND package_id = :packageId"
    )
    fun observeByIdentityPackage(identityId: String, packageId: String): Flow<List<FlashcardProgressEntity>>

    @Query(
        "SELECT * FROM flashcard_progress WHERE identity_id = :identityId AND package_id = :packageId"
    )
    suspend fun list(identityId: String, packageId: String): List<FlashcardProgressEntity>

    @Query(
        "SELECT * FROM flashcard_progress WHERE identity_id = :identityId AND package_id = :packageId AND card_id = :cardId"
    )
    suspend fun find(identityId: String, packageId: String, cardId: String): FlashcardProgressEntity?

    @Query("DELETE FROM flashcard_progress WHERE identity_id = :identityId AND package_id = :packageId")
    suspend fun deleteByPackage(identityId: String, packageId: String)
}

@Dao
interface ExerciseProgressDao {
    @Upsert
    suspend fun upsert(progress: ExerciseProgressEntity)

    @Upsert
    suspend fun upsertAll(list: List<ExerciseProgressEntity>)

    @Query("SELECT * FROM exercise_progress WHERE identity_id = :identityId AND package_id = :packageId")
    fun observeByIdentityPackage(identityId: String, packageId: String): Flow<List<ExerciseProgressEntity>>

    @Query("SELECT * FROM exercise_progress WHERE identity_id = :identityId AND package_id = :packageId")
    suspend fun list(identityId: String, packageId: String): List<ExerciseProgressEntity>

    @Query(
        "SELECT * FROM exercise_progress WHERE identity_id = :identityId AND package_id = :packageId AND exercise_id = :exerciseId"
    )
    suspend fun find(identityId: String, packageId: String, exerciseId: String): ExerciseProgressEntity?

    @Query("DELETE FROM exercise_progress WHERE identity_id = :identityId AND package_id = :packageId")
    suspend fun deleteByPackage(identityId: String, packageId: String)
}
