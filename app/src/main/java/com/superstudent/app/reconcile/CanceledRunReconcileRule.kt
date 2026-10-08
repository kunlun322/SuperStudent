package com.superstudent.app.reconcile

import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.TaskState
import com.superstudent.core.repository.CancelCompensationStore

/**
 * The canceled-run half of the one startup pass (ZLQ-114 §5.5): converges a cancel that could not
 * finish, and hands the unfinished ones to WorkManager.
 *
 * It exists because the interesting failure is a *process death between the atomic cancel transaction
 * and the enqueue* — the local tree is already consistent at that point (`CANCELED`, package `READY`),
 * so nothing else in the app ever looks at the row again, while the cloud Session keeps running and
 * keeps billing. Only a scan for the marker finds it.
 *
 * Like [TaskRunRecoveryRule] and unlike the other rules, this one is deliberately NOT gated on
 * `hasValidatedNetwork`: both of its jobs are pure Room, and job 1 is precisely the local convergence
 * that has to happen on an offline cold start — that is the whole point of the fix. WorkManager waits
 * for connectivity on its own through the Worker's `CONNECTED` constraint, so an offline pass still
 * repairs the package and still enqueues.
 *
 * The rule makes no QCA or Drive call. Anything that has to talk to the cloud belongs to the Worker,
 * which is why a compensation failure is retried there rather than here.
 *
 * Both collaborators are injected rather than reached through a `Context`, so the offline path is
 * testable on the JVM: this repo has no Robolectric or instrumented harness.
 */
class CanceledRunReconcileRule(
    private val store: CancelCompensationStore,
    private val enqueue: (taskId: String, attempt: Int) -> Unit,
) : StartupReconcileRule {

    override val name = "canceled-run-reconcile"

    /**
     * Both jobs are pure Room, and job 1 is precisely the `CANCELED` compensation an offline,
     * credential-less cold start still owes (ZLQ-126 §2.4). The cloud half lives in the Worker, which
     * waits for connectivity on its own.
     */
    override val requiresCredential = false

    override suspend fun reconcile() {
        convergeMismatchedPackages()
        enqueuePendingCompensation()
    }

    /**
     * Job 1 — `CANCELED` runs whose package is still `GENERATING`.
     *
     * The reset goes through §5.2's guarded UPDATE rather than a read-then-write, so a package that
     * moved on to a newer attempt, or that has another run in flight, keeps its `GENERATING`: the
     * guard declining is the correct outcome, not an error to work around.
     *
     * The marker is then backfilled on session-bearing rows. A historical mismatch row cannot be
     * distinguished from one whose compensation completed, so the safe reading is that its remote
     * Session may never have been confirmed stopped and its tmp subtree may never have been deleted;
     * every compensation step is idempotent, so guessing "unfinished" costs one no-op pass while
     * guessing "finished" would leave a Session billing forever. Rows with no Session are left alone:
     * they had nothing remote to stop.
     */
    private suspend fun convergeMismatchedPackages() {
        store.listCanceledGeneratingMismatch().forEach { mismatch ->
            val run = mismatch.run
            store.resetPackageAfterCancel(run.packageId, run.taskId, run.attempt)
            if (run.sessionId != null) {
                store.markCancelCleanupPending(run.taskId, run.attempt)
            }
        }
    }

    /**
     * Job 2 — one enqueue per attempt still owing compensation. The unique work name is keyed on the
     * same `(taskId, attempt)` this scan returns, so re-running the rule on every cold start is
     * harmless: `ExistingWorkPolicy.KEEP` makes a duplicate ask join the chain already running.
     */
    private suspend fun enqueuePendingCompensation() {
        store.listCancelCleanupPending()
            .filter { it.state == TaskState.CANCELED.name && it.cleanupPending }
            .forEach { run: TaskRunEntity -> enqueue(run.taskId, run.attempt) }
    }
}
