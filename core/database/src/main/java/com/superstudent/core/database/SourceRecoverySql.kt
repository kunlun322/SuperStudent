package com.superstudent.core.database

/**
 * The exact SQL the upload lease and the source-row lifecycle ship (ZLQ-132 §5.4).
 *
 * Every state transition here is a *guarded* UPDATE rather than a whole-row upsert, and the guards
 * are the design rather than an implementation detail. Three rules hold for all of them:
 *
 * 1. An ownership write matches `source_id + attempt_token + lease_owner_id` together. The token
 *    names one attempt, the owner names one process; substituting either for the other is how a dead
 *    process's late PUT used to be able to commit over a live one.
 * 2. A business update carries `delete_pending = 0`. A tombstoned row owes no upload, no success and
 *    no failure — only the delete worker may write it.
 * 3. Callers act on the returned row count: 0 means a guard failed and the row moved on without them,
 *    which is never an error to retry.
 *
 * Each constant is referenced by the `@Query` that ships it, so `SourceRecoverySqlTest` — which runs
 * these strings against real SQLite built from the exported v4 schema — cannot drift from production
 * behaviour. That is also why they live here instead of inline in the annotations: a fake DAO can only
 * ever prove what the fake decided to implement.
 *
 * Schema note: every column these statements write arrives with `MIGRATION_3_4`.
 */
object SourceRecoverySql {

    /**
     * Claims one upload attempt. Has no `UPLOADING` branch on purpose: a stuck row is released only
     * by [INTERRUPT_ATTEMPT], under the previous owner's OS lock, so a claim can never take an
     * expired lease directly and start a second PUT of the same object (§3.2 「不得回收」).
     */
    const val CLAIM = """
        UPDATE source_asset
        SET upload_state = 'UPLOADING',
            attempt_token = :token,
            lease_owner_id = :ownerId,
            lease_until = :leaseUntilMillis,
            lease_heartbeat_at = :heartbeatAt,
            upload_started_at = :uploadStartedAt,
            last_progress_at = :lastProgressAt,
            attempt_count = attempt_count + 1,
            error_code = NULL,
            error_message = NULL,
            next_retry_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId
          AND delete_pending = 0
          AND interrupt_requested_at IS NULL
          AND (
                (upload_state = 'PENDING'
                 AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis))
             OR (upload_state = 'FAILED' AND retryable = 1 AND attempt_count < :maxAttempts
                 AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis))
          )
    """

    /**
     * Extends a live attempt's lease. 0 rows is the *only* channel through which a recovery pass
     * can tell a live owner to stand down (§3.2 condition 4).
     */
    const val RENEW_LEASE = """
        UPDATE source_asset
        SET lease_heartbeat_at = :heartbeatAt,
            lease_until = :leaseUntilMillis,
            updated_at = :now
        WHERE source_id = :sourceId
          AND attempt_token = :token
          AND lease_owner_id = :ownerId
          AND upload_state = 'UPLOADING'
          AND delete_pending = 0
          AND interrupt_requested_at IS NULL
    """

    /**
     * [RENEW_LEASE]'s guard plus the timestamp the 10-minute no-progress rule reads.
     */
    const val RECORD_PROGRESS = """
        UPDATE source_asset
        SET last_progress_at = :progressAt,
            lease_heartbeat_at = :heartbeatAt,
            lease_until = :leaseUntilMillis,
            updated_at = :now
        WHERE source_id = :sourceId
          AND attempt_token = :token
          AND lease_owner_id = :ownerId
          AND upload_state = 'UPLOADING'
          AND delete_pending = 0
          AND interrupt_requested_at IS NULL
    """

    /**
     * Whether this attempt still owns the row, asked inside the manifest lock right before
     * publishing (ZLQ-110 §2.3). Adding a statement does not change Room's identity hash, which
     * covers tables, not queries.
     */
    const val COUNT_OWNED_ATTEMPT =
        "SELECT COUNT(*) FROM source_asset WHERE source_id = :sourceId AND attempt_token = :token AND lease_owner_id = :ownerId AND delete_pending = 0"

    /**
     * Terminal success: the object is on Drive and `package.json` has been re-published. Ownership
     * is `(attempt_token, lease_owner_id)` together — a reclaimed attempt gets a fresh token *and*
     * a fresh owner id, so a coroutine from a dead process cannot land a late commit even if its
     * token somehow survived.
     */
    const val MARK_UPLOADED = """
        UPDATE source_asset
        SET upload_state = 'UPLOADED',
            drive_path = :drivePath,
            sha256 = :sha256,
            size_bytes = :sizeBytes,
            canonical_type = :canonicalType,
            local_uri = NULL,
            local_access_mode = 'NONE',
            error_code = NULL,
            error_message = NULL,
            retryable = 0,
            next_retry_at = NULL,
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId
          AND attempt_token = :token
          AND lease_owner_id = :ownerId
          AND delete_pending = 0
    """

    /**
     * Writes a classified failure. The four content columns are `COALESCE`d so a retry after a
     * manifest-publish failure overwrites the same object instead of creating a second one.
     */
    const val RECORD_FAILURE = """
        UPDATE source_asset
        SET upload_state = :state,
            error_code = :errorCode,
            error_message = :errorMessage,
            retryable = :retryable,
            next_retry_at = :nextRetryAtMillis,
            drive_path = COALESCE(:drivePath, drive_path),
            sha256 = COALESCE(:sha256, sha256),
            size_bytes = COALESCE(:sizeBytes, size_bytes),
            canonical_type = COALESCE(:canonicalType, canonical_type),
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId
          AND attempt_token = :token
          AND lease_owner_id = :ownerId
          AND delete_pending = 0
    """

    /**
     * The picked file can no longer be read (grant revoked, original deleted, restore after
     * reinstall). Never silent: the row stays visible and offers 重新选择文件.
     */
    const val MARK_LOCAL_ONLY = """
        UPDATE source_asset
        SET upload_state = 'LOCAL_ONLY',
            local_access_mode = :localAccessMode,
            error_code = :errorCode,
            error_message = :errorMessage,
            retryable = 0,
            next_retry_at = NULL,
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId AND upload_state != 'UPLOADED' AND delete_pending = 0
    """

    /**
     * Asks a live owner to stand down without touching anything else — the whole of what a recovery
     * pass may do when the owner's OS lock is still held and the source execution lock could not be
     * taken (§3.2 「不得回收」).
     */
    const val REQUEST_INTERRUPT = """
        UPDATE source_asset
        SET interrupt_requested_at = :nowMillis,
            updated_at = :now
        WHERE source_id = :sourceId
          AND upload_state = 'UPLOADING'
          AND delete_pending = 0
          AND interrupt_requested_at IS NULL
    """

    /**
     * Releases an orphaned `UPLOADING` row, run only while the caller holds the previous owner's OS
     * lock and the source execution lock. `attempt_count` is *not* incremented: a recovery is not
     * an attempt, and counting it would let a crash loop spend the student's budget without ever
     * uploading (§7.2 item 5).
     */
    const val INTERRUPT_ATTEMPT = """
        UPDATE source_asset
        SET upload_state = 'FAILED',
            error_code = 'PROCESS_INTERRUPTED',
            error_message = '上传中断，请重试',
            retryable = CASE WHEN attempt_count < :maxAttempts THEN 1 ELSE 0 END,
            next_retry_at = CASE WHEN attempt_count < :maxAttempts THEN :nowMillis ELSE NULL END,
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId
          AND upload_state = 'UPLOADING'
          AND delete_pending = 0
    """

    /**
     * Arms the durable delete tombstone (§3.9 item 1), in one transaction and before any remote
     * call. `attempt_token` is cleared so an in-flight `MARK_UPLOADED` returns 0: that is what
     * fences a PUT already in the air when the student pressed 删除.
     */
    const val BEGIN_DELETE = """
        UPDATE source_asset
        SET delete_pending = 1,
            delete_requested_at = :nowMillis,
            interrupt_requested_at = :nowMillis,
            attempt_token = NULL,
            updated_at = :now
        WHERE source_id = :sourceId AND delete_pending = 0
    """

    /**
     * The delete worker's last step. Guarded so only a tombstoned row can disappear; a failed
     * delete keeps the tombstone and the row presents as 待清理 (§3.9 item 6).
     */
    const val FINISH_DELETE =
        "DELETE FROM source_asset WHERE source_id = :sourceId AND delete_pending = 1"

    /**
     * Manual 重试上传: only `FAILED` qualifies, and the counter resets because a manual retry opens a
     * *new* auto-retry cycle — `retryable` only ever means the scheduler may retry, so an exhausted
     * row must get a full budget again (ZLQ-110 §3.3). An interrupted `UPLOADING` row goes through
     * [INTERRUPT_ATTEMPT] first, so tapping 重试 can never start a second PUT while the old owner is
     * alive.
     */
    const val REQUEUE_FOR_RETRY = """
        UPDATE source_asset
        SET upload_state = 'PENDING',
            error_code = NULL,
            error_message = NULL,
            retryable = 1,
            attempt_count = 0,
            next_retry_at = NULL,
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId AND upload_state = 'FAILED' AND delete_pending = 0
    """

    /**
     * Binds a new local handle to an existing row (重新选择文件), opening a fresh cycle. `size_bytes` is
     * written here rather than by a read-then-upsert in the repository: the old shape let a
     * concurrent guarded UPDATE land between the read and the write and be clobbered wholesale
     * (§5.4).
     */
    const val BIND_LOCAL_FILE = """
        UPDATE source_asset
        SET local_uri = :localUri,
            local_access_mode = :localAccessMode,
            upload_state = :state,
            size_bytes = COALESCE(:sizeBytes, size_bytes),
            error_code = :errorCode,
            error_message = :errorMessage,
            retryable = :retryable,
            attempt_count = 0,
            next_retry_at = NULL,
            attempt_token = NULL,
            lease_owner_id = NULL,
            lease_until = NULL,
            lease_heartbeat_at = NULL,
            upload_started_at = NULL,
            last_progress_at = NULL,
            interrupt_requested_at = NULL,
            updated_at = :now
        WHERE source_id = :sourceId AND upload_state != 'UPLOADED' AND delete_pending = 0
    """

    /**
     * Uploads that are due. Carries no `UPLOADING` branch: resuming a row another process may still
     * own is exactly the double-PUT this design removes.
     */
    const val LIST_RESUMABLE = """
        SELECT * FROM source_asset
        WHERE delete_pending = 0
          AND ((upload_state = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis))
           OR (upload_state = 'FAILED' AND retryable = 1 AND attempt_count < :maxAttempts
               AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis)))
        ORDER BY added_at ASC
    """

    /**
     * Everything the recovery coordinator owes an action on. Separate from [LIST_RESUMABLE] because
     * the two have different owners — the sweeper enqueues uploads, while this scan may also
     * interrupt an attempt or re-enqueue a delete.
     *
     * The `last_progress_at` branch is design §3.2 condition 4, and it is the only way that condition
     * is reachable: a live owner whose heartbeat coroutine still renews while its PUT is wedged keeps
     * `lease_until` in the future forever, so an expired-lease test alone would never surface it.
     * Being selected here does not make the row reclaimable — the coordinator still has to find the
     * owner dead before it may release, and otherwise only writes `interrupt_requested_at`.
     */
    const val LIST_RECOVERY_CANDIDATES = """
        SELECT * FROM source_asset
        WHERE delete_pending = 1
           OR (upload_state = 'UPLOADING' AND (lease_until IS NULL OR lease_until < :nowMillis))
           OR (upload_state = 'UPLOADING' AND last_progress_at IS NOT NULL
               AND last_progress_at < :noProgressBefore)
           OR (upload_state = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis))
           OR (upload_state = 'FAILED' AND retryable = 1 AND attempt_count < :maxAttempts
               AND (next_retry_at IS NULL OR next_retry_at <= :nowMillis))
        ORDER BY added_at ASC
    """

    /**
     * The earliest moment any row becomes actionable, used to arm exactly one follow-up pass. A
     * tombstone's own timestamp is included so a delete whose worker died is picked up again rather
     * than waiting for the next process start.
     *
     * `due > :nowMillis` is what makes the KDoc's promise true rather than aspirational: rows
     * actionable *now* contribute nothing, because the current pass already claims them. Without it a
     * row this pass could not release — an expired lease whose owner is provably alive and has not yet
     * stood down, §3.2 「不得回收」 — would report a deadline in the past forever, and the coordinator
     * would arm a 0 ms follow-up, run a pass that changes nothing, and arm it again: a hot loop that
     * only ends when that process dies. Excluding the past costs nothing, because the row is re-examined
     * by the trigger that can actually change it (PAGE, LOGIN, NETWORK, PROCESS_START).
     */
    const val NEXT_RECOVERY_DEADLINE = """
        SELECT MIN(due) FROM (
            SELECT lease_until AS due FROM source_asset
             WHERE upload_state = 'UPLOADING' AND delete_pending = 0 AND lease_until IS NOT NULL
            UNION ALL
            SELECT next_retry_at AS due FROM source_asset
             WHERE upload_state = 'FAILED' AND delete_pending = 0 AND retryable = 1
               AND attempt_count < :maxAttempts AND next_retry_at IS NOT NULL
            UNION ALL
            SELECT delete_requested_at AS due FROM source_asset
             WHERE delete_pending = 1 AND delete_requested_at IS NOT NULL
        ) WHERE due > :nowMillis
    """

    /**
     * Rows whose auto-retry budget is spent on a failure that was *believed transient* — exactly
     * the rows a local read failure could have been misclassified into before the read stage
     * normalized its exceptions (ZLQ-105). Genuinely permanent codes are excluded so a
     * `FILE_TOO_LARGE` row keeps its own message, and so is `MANIFEST_PUBLISH_FAILED`, whose object
     * is already on Drive and belongs to the manifest reconciliation.
     */
    const val LIST_EXHAUSTED_TRANSIENT_FAILURES = """
        SELECT * FROM source_asset
        WHERE upload_state = 'FAILED'
          AND retryable = 0
          AND delete_pending = 0
          AND error_code IN ('NETWORK_UNAVAILABLE', 'TIMEOUT', 'SERVER_BUSY',
                             'PRESIGNED_URL_EXPIRED', 'PROCESS_INTERRUPTED', 'UNKNOWN')
        ORDER BY added_at ASC
    """

    /**
     * Every statement above, keyed by its constant name, for the structural gates in
     * `SourceRecoverySqlTest`. That test also asserts the keys still cover every `const val` this
     * object declares, so a new statement cannot be added here and quietly escape the gates.
     */
    val ALL: Map<String, String> = mapOf(
        "CLAIM" to CLAIM,
        "RENEW_LEASE" to RENEW_LEASE,
        "RECORD_PROGRESS" to RECORD_PROGRESS,
        "COUNT_OWNED_ATTEMPT" to COUNT_OWNED_ATTEMPT,
        "MARK_UPLOADED" to MARK_UPLOADED,
        "RECORD_FAILURE" to RECORD_FAILURE,
        "MARK_LOCAL_ONLY" to MARK_LOCAL_ONLY,
        "REQUEST_INTERRUPT" to REQUEST_INTERRUPT,
        "INTERRUPT_ATTEMPT" to INTERRUPT_ATTEMPT,
        "BEGIN_DELETE" to BEGIN_DELETE,
        "FINISH_DELETE" to FINISH_DELETE,
        "REQUEUE_FOR_RETRY" to REQUEUE_FOR_RETRY,
        "BIND_LOCAL_FILE" to BIND_LOCAL_FILE,
        "LIST_RESUMABLE" to LIST_RESUMABLE,
        "LIST_RECOVERY_CANDIDATES" to LIST_RECOVERY_CANDIDATES,
        "NEXT_RECOVERY_DEADLINE" to NEXT_RECOVERY_DEADLINE,
        "LIST_EXHAUSTED_TRANSIENT_FAILURES" to LIST_EXHAUSTED_TRANSIENT_FAILURES,
    )
}
