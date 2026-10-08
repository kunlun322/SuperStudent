package com.superstudent.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * The exact SQL the sessionless-run reconciliation ships (ZLQ-103 §3).
 *
 * Every terminal write here is a *guarded* UPDATE rather than a whole-row upsert: the guard
 * (`session_id IS NULL AND state IN (...)`) is what makes a late recovery pass harmless against a
 * run that a live submit critical section has already carried past the sessionless window. Each
 * constant is referenced by the `@Query` that ships it, so the JVM tests that run these strings
 * against the exported v3 schema cannot drift from production behaviour.
 *
 * Schema note: this needs no migration — `run_id`, `session_id`, `error_code`, `error_message` and
 * `finished_at` all already exist in v3.
 */
object TaskRunRecoverySql {

    /**
     * Converges a run that is still active with no Session: the process died (or the submit chain
     * was cancelled) between `createAttempt` and the sessionId write. It becomes a user-retryable
     * terminal so the D-2 admission guard stops counting it forever. `CANCEL_REQUESTED` is
     * deliberately absent — a recorded cancel intent converges to `CANCELED` instead.
     */
    const val CONVERGE_INTERRUPTED = """
        UPDATE task_run
        SET state = 'FAILED_RETRYABLE',
            error_code = :errorCode,
            error_message = :errorMessage,
            finished_at = :now,
            updated_at = :now
        WHERE task_id = :taskId
          AND attempt = :attempt
          AND session_id IS NULL
          AND state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN')
    """

    /**
     * Converges a run the user cancelled before a Session existed. There is no remote turn to stop,
     * so the recorded intent is honoured directly instead of being reinterpreted as a failure.
     */
    const val CONVERGE_CANCELED = """
        UPDATE task_run
        SET state = 'CANCELED',
            error_code = NULL,
            error_message = NULL,
            finished_at = :now,
            updated_at = :now
        WHERE task_id = :taskId
          AND attempt = :attempt
          AND session_id IS NULL
          AND state = 'CANCEL_REQUESTED'
    """

    /**
     * Active runs joined to their *owning* identity. Recovery must not resume every active row under
     * the currently logged-in identity: a row belongs to the identity of the package it was started
     * for, and the two differ as soon as an account switch happened while a run was in flight.
     */
    const val LIST_ACTIVE_WITH_OWNER = """
        SELECT r.*,
               p.identity_id AS owner_identity_id,
               p.status AS package_status,
               p.latest_task_id AS package_latest_task_id
        FROM task_run AS r
        INNER JOIN learning_package AS p ON p.package_id = r.package_id
        WHERE r.state IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'RETRY_WAIT', 'UNKNOWN')
        ORDER BY r.updated_at DESC
    """

    /**
     * The D-2 first-build admission count, resolved locally. Counting through the package repository
     * instead made admission depend on a Drive read; the guard must stay decidable offline, otherwise
     * a network blip would let a second first-build through while the first is still creating the
     * Notebook.
     */
    const val COUNT_ACTIVE_BY_IDENTITY = """
        SELECT COUNT(*)
        FROM task_run AS r
        INNER JOIN learning_package AS p ON p.package_id = r.package_id
        WHERE p.identity_id = :identityId
          AND r.state IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'RETRY_WAIT', 'UNKNOWN')
    """

    /** Active rows of one package other than the run being converged — the "no newer active run" half of the package reset guard. */
    const val COUNT_ACTIVE_FOR_PACKAGE_EXCLUDING = """
        SELECT COUNT(*)
        FROM task_run
        WHERE package_id = :packageId
          AND state IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'RETRY_WAIT', 'UNKNOWN')
          AND NOT (task_id = :taskId AND attempt = :attempt)
    """

    /** Intent redelivery looks a START_NEW up by its requestId, which reuses `run_id`. */
    const val FIND_BY_RUN_ID = """
        SELECT * FROM task_run WHERE run_id = :runId ORDER BY attempt DESC LIMIT 1
    """

    /**
     * Puts a `GENERATING` package back to `READY` after its run converged. Guarded on
     * `latest_task_id` so a package that already moved on to a newer task is left alone; the caller
     * supplies the remaining guards (attempt is still the latest, no other active run).
     */
    const val PACKAGE_BACK_TO_READY = """
        UPDATE learning_package
        SET status = 'READY',
            updated_at = :now
        WHERE package_id = :packageId
          AND status = 'GENERATING'
          AND latest_task_id = :taskId
    """

    // ---- offline-cancel compensation (ZLQ-114 §5.2 / §5.5) ----
    //
    // A cancel that could not reach the cloud still has to leave the local tree consistent, so the
    // terminal write and the package reset are one transaction and the reset is one guarded UPDATE.
    // Unlike [PACKAGE_BACK_TO_READY], whose caller supplies the remaining guards from separate reads,
    // every guard lives inside this statement: reading "is another run active?" first and writing
    // second is exactly the race that left a package stuck at `GENERATING` (ZLQ-114).

    /**
     * Puts a `GENERATING` package back to `READY` because its latest attempt was canceled. All four
     * guards are evaluated against the same snapshot the write sees:
     *
     *  1. the package is still `GENERATING` and still points at this task;
     *  2. this attempt really is `CANCELED` in `task_run`;
     *  3. this attempt is still the task's latest — a newer attempt owns the package now;
     *  4. no *other* run of the package is active — resetting would hide live work.
     *
     * Returns 0 whenever any guard fails, which is a normal outcome, not an error: the caller keeps
     * the `cleanup_pending` marker and moves on.
     */
    const val PACKAGE_RESET_AFTER_CANCEL = """
        UPDATE learning_package
        SET status = 'READY',
            updated_at = :now
        WHERE package_id = :packageId
          AND status = 'GENERATING'
          AND latest_task_id = :taskId
          AND EXISTS (
              SELECT 1 FROM task_run
              WHERE task_id = :taskId AND attempt = :attempt AND package_id = :packageId AND state = 'CANCELED'
          )
          AND :attempt = (
              SELECT MAX(attempt) FROM task_run WHERE task_id = :taskId
          )
          AND NOT EXISTS (
              SELECT 1 FROM task_run
              WHERE package_id = :packageId
                AND NOT (task_id = :taskId AND attempt = :attempt)
                AND state IN ('QUEUED','RUNNING','RETRY_WAIT','UNKNOWN','CANCEL_REQUESTED')
          )
    """

    /**
     * Canceled runs whose package is still `GENERATING` — the mismatch a cold start has to converge
     * locally (ZLQ-114 §5.5). Deliberately not filtered on `latest_task_id` or on the attempt: which
     * rows may actually be reset is [PACKAGE_RESET_AFTER_CANCEL]'s decision, and this scan only has to
     * find the candidates. Joined to the *owning* identity for the same reason as
     * [LIST_ACTIVE_WITH_OWNER].
     */
    const val LIST_CANCELED_GENERATING_MISMATCH = """
        SELECT r.*,
               p.identity_id AS owner_identity_id,
               p.status AS package_status,
               p.latest_task_id AS package_latest_task_id
        FROM task_run AS r
        INNER JOIN learning_package AS p ON p.package_id = r.package_id
        WHERE r.state = 'CANCELED'
          AND p.status = 'GENERATING'
        ORDER BY r.updated_at ASC
    """

    /**
     * Backfills the compensation marker on a historical canceled run that still owes remote/tmp/manifest
     * work. Only a session-bearing row can owe it: with no Session there was nothing remote to stop, and
     * a `CANCELED + READY + cleanup_pending = 0` row is already indistinguishable from a fully
     * compensated one (ZLQ-114 §9), so it is left alone rather than guessed at.
     */
    const val MARK_CANCEL_CLEANUP_PENDING = """
        UPDATE task_run
        SET cleanup_pending = 1,
            updated_at = :now
        WHERE task_id = :taskId
          AND attempt = :attempt
          AND state = 'CANCELED'
          AND cleanup_pending = 0
          AND session_id IS NOT NULL
    """

    /**
     * Canceled runs whose terminal compensation did not finish (ZLQ-114 §5.5 job 2). One row is one
     * `CancelCompensationWorker` enqueue, keyed on `(task_id, attempt)`.
     */
    const val LIST_CANCEL_CLEANUP_PENDING = """
        SELECT * FROM task_run
        WHERE state = 'CANCELED' AND cleanup_pending = 1
        ORDER BY updated_at ASC
    """

    /**
     * The exact attempt plus its owning identity, for compensation (ZLQ-114 §5.4). Identity comes from
     * the `task_run → learning_package` join and never from the logged-in account: the package belongs
     * to whoever started the run, and the two differ after an account switch. Not filtered on state or
     * on the marker so the caller can tell "row gone" from "row moved on" and return success for both.
     */
    const val FIND_ATTEMPT_WITH_OWNER = """
        SELECT r.*,
               p.identity_id AS owner_identity_id,
               p.status AS package_status,
               p.latest_task_id AS package_latest_task_id
        FROM task_run AS r
        INNER JOIN learning_package AS p ON p.package_id = r.package_id
        WHERE r.task_id = :taskId AND r.attempt = :attempt
    """

    /**
     * Clears the marker only from the row the compensation actually ran for — a compare-and-swap on
     * `(task_id, attempt, state, cleanup_pending)`. Without the state and marker terms a retry that
     * arrives after a newer attempt reused the row, or after anything else rewrote it, would clear a
     * marker it never earned. Returns 0 when someone else already cleared it: success, not a failure.
     */
    const val CLEAR_CANCEL_CLEANUP_PENDING = """
        UPDATE task_run
        SET cleanup_pending = 0,
            updated_at = :now
        WHERE task_id = :taskId
          AND attempt = :attempt
          AND state = 'CANCELED'
          AND cleanup_pending = 1
    """
}

/** One run plus the package facts a reconcile rule needs to decide and to reset status. */
data class ActiveRunRow(
    @Embedded val run: TaskRunEntity,
    @ColumnInfo(name = "owner_identity_id") val ownerIdentityId: String,
    @ColumnInfo(name = "package_status") val packageStatus: String,
    @ColumnInfo(name = "package_latest_task_id") val packageLatestTaskId: String?,
)
