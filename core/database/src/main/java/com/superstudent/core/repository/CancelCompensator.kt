package com.superstudent.core.repository

import android.util.Log
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.TaskState
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.RemoteCancelConverger
import com.superstudent.core.network.RemoteCancelOutcome
import kotlinx.coroutines.CancellationException

/**
 * Whether one compensation pass finished the attempt's terminal work (ZLQ-114 §5.4).
 *
 * [RETRY] never means "give up": every step is idempotent, so a retry only re-proves what already
 * held — the Session is still terminal, the tmp subtree is still absent, the manifest still matches
 * Room. There is no path on which a retry restarts a task or touches another attempt's directory.
 */
enum class CompensationOutcome { COMPLETED, RETRY }

/**
 * Finishes a canceled attempt's terminal compensation: remote Session confirmed stopped, this
 * attempt's tmp subtree deleted, and the resulting Room state projected onto `package.json` /
 * `index.json` (ZLQ-114 §5.4).
 *
 * Shared by the foreground cancel path — which runs it right after the atomic transaction commits, to
 * shorten the time the cloud keeps billing — and by the app's `CancelCompensationWorker`, which runs
 * it again whenever the foreground pass could not finish. Same function, same order, so the two
 * cannot drift.
 *
 * Order is load-bearing: remote cancel *before* the tmp delete, because a Session still running would
 * recreate the objects just deleted; manifest publish *last*, so the projection never claims a state
 * the cleanup did not reach. Identity always comes from the `task_run → learning_package` join, never
 * from the logged-in account — after an account switch those differ, and compensating under the wrong
 * one would delete another tenant's objects.
 *
 * This class only reports. Deciding what a [CompensationOutcome.RETRY] means belongs to the caller:
 * the Worker returns `Result.retry()` and lets WorkManager reschedule the same unique work, while the
 * foreground path enqueues that work.
 */
class CancelCompensator(
    private val store: CancelCompensationStore,
    private val drive: DriveRepository,
    private val manifestWriter: ManifestWriter,
    private val remoteCancel: RemoteCancelConverger,
    /** Seam for JVM tests: `android.util.Log` is not stubbed in unit tests. */
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {

    suspend fun compensate(taskId: String, attempt: Int): CompensationOutcome {
        // Step 1 — the exact row. Absent (or its package deleted, which the join also yields) means
        // there is nothing left to compensate; so does a row that is not a pending CANCELED, which
        // covers both "already cleared by an earlier pass" and "moved on without us".
        val found = store.findAttemptWithOwner(taskId, attempt) ?: return CompensationOutcome.COMPLETED
        val run = found.run
        if (run.state != TaskState.CANCELED.name || !run.cleanupPending) {
            return CompensationOutcome.COMPLETED
        }
        val identityId = found.ownerIdentityId

        // Step 2 — remote terminal before anything is deleted. An unproven cancel keeps the marker:
        // this is the whole point of the fix, since an offline cancel is exactly when the cloud is
        // still running the turn and still billing for it.
        val sessionId = run.sessionId
        if (sessionId != null && remoteCancel.ensureCanceled(sessionId) != RemoteCancelOutcome.CONVERGED) {
            log("compensate: $taskId#$attempt remote cancel unconfirmed, keeping marker")
            return CompensationOutcome.RETRY
        }

        // Step 3 — this attempt's tmp subtree only. The path is derived from this row's own
        // (packageId, taskId, attempt) and DriveRepository.deleteSubtree enforces the scope guards.
        val tmp = DrivePath.tmpDir(run.packageId, "${run.taskId}-${run.attempt}")
        try {
            drive.deleteSubtree(identityId, tmp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QcaException) {
            // Already gone is the goal state, not a failure; anything else keeps the marker.
            if (e.kind != QcaErrorKind.NOT_FOUND) {
                log("compensate: $taskId#$attempt tmp delete failed (${e.kind})")
                return CompensationOutcome.RETRY
            }
        } catch (e: Exception) {
            log("compensate: $taskId#$attempt tmp delete refused (${e.javaClass.simpleName})")
            return CompensationOutcome.RETRY
        }

        // Step 4 — project whatever Room says now. No status argument: the transaction (or the
        // cold-start rule) already decided it, and hard-coding one here would let a stale value
        // overwrite a newer state. Goes through ManifestWriter's single locked path.
        try {
            manifestWriter.publishState(identityId = identityId, packageId = run.packageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("compensate: $taskId#$attempt manifest publish failed (${e.javaClass.simpleName})")
            return CompensationOutcome.RETRY
        }

        // Step 5 — CAS clear. Guarded on state and marker, so a clear can never land on a row this
        // pass did not actually compensate. 0 rows means someone else already cleared it: done.
        store.clearCancelCleanupPending(taskId, attempt)
        return CompensationOutcome.COMPLETED
    }

    private companion object {
        const val TAG = "SsCancelCompensator"
    }
}
