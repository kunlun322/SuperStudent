package com.superstudent.app.features.tasks

import com.superstudent.core.model.Ids
import kotlinx.coroutines.CompletableDeferred

/**
 * Why a resume pass was asked for (ZLQ-120 §5.2). The trigger is recorded, never the caller: three
 * different components ask for the same pass, and telling them apart in a log is what makes "one
 * dispatch per cold start" checkable after the fact.
 *
 * [wire] is the spelling §7.3's log contract uses, which is shorter than the constant name.
 */
enum class ResumeReason(internal val wire: String) {
    /** The one application pass, from [com.superstudent.app.reconcile.TaskRunRecoveryRule]. */
    PROCESS_START("PROCESS_START"),

    /** The post-login callback in `MainActivity`. Its timing is fixed by ZLQ-126 §3.7 / design §5.3. */
    LOGIN_COMPLETED("LOGIN"),

    /**
     * `RunResumeWorker`, which WorkManager only runs once connectivity is back. This is the network
     * half of the pass's precondition arriving, rather than a probe of it.
     */
    NETWORK_RECOVERED("NETWORK"),

    /** Reserved: an in-service re-issue that does not go through WorkManager. Nothing sends it yet. */
    SERVICE_HANDOFF("HANDOFF"),
}

/**
 * One pass of [ResumeAllCoordinator]. Every pass inside a single drain shares an [id], so a merged
 * follow-up reads in the log as the continuation of the request that caused it rather than as a
 * second, unrelated dispatch.
 *
 * The id is observability-only: §5.2 forbids persisting it, and it is not a key anything is looked up
 * by. After a process death it is simply gone, and the new process mints another.
 */
class ResumeOperation(val id: String, val reasons: List<ResumeReason>) {

    /**
     * Resumable rows the pass found, set by the pass before it returns so the coordinator can write
     * §7.3's `resume_dispatch` line. Zero rows means the pass dispatched no intent at all, which is
     * the fact worth having in the log — it is what separates "nothing to resume" from "resumed".
     */
    var rowCount: Int = 0
}

/**
 * The single-flight guard ZLQ-120 §5.2 requires, application-scoped so every trigger shares it.
 *
 * Before this, three triggers each ran their own pass: `StartupReconciler.done` merged only the
 * Application and identity-ready requests into the *coordinator*, `TaskRunner.startMutex` serialised
 * the local convergence and then released, and the service's `jobs["resume-all"]` deduped only after
 * the intent had already been delivered. Two of the three could therefore read the same resumable rows
 * and each start the foreground service, which is what AMS saw as two `RESUME_ALL` launches for one
 * cold start.
 *
 * The state machine is §5.2's, with one simplification that does not change it: instead of a separate
 * `pending` flag, the reasons waiting for a follow-up are held in a set, so "pending" is "the set is
 * non-empty". That is what makes *at most one* follow-up true by construction — a hundred requests
 * arriving mid-pass drain together into a single second pass, instead of queueing a hundred.
 *
 * There is deliberately no cross-process mechanism here (no file lock, no cross-process
 * `ContentProvider`, no multi-process preferences): the manifest declares no `android:process`, so
 * every trigger runs in this process. A second PID in the field was the system rebuilding the process
 * for a redelivered service intent after ZLQ-119's FATAL, and `ACTION_RESUME_ALL` being non-sticky
 * removes the redelivery that caused it (§5.4).
 *
 * Nothing is restored after a process death either. Room is the recovery truth, so a new process
 * re-derives the resumable set from it and issues one pass — an in-memory operation id surviving a
 * death would only claim coverage this process never gave.
 */
class ResumeAllCoordinator(
    private val pid: () -> Int = { android.os.Process.myPid() },
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    /**
     * A plain monitor rather than a `Mutex`: every critical section here is a few field writes, and
     * keeping them non-suspending is what lets the `finally` below reset the state on a cancelled pass
     * without a `NonCancellable` wrapper.
     */
    private val lock = Any()

    private var running = false
    private var queued = LinkedHashSet<ResumeReason>()
    private var drain: CompletableDeferred<Unit>? = null
    private var operationId: String? = null

    /**
     * Records [reason] and runs one pass covering it.
     *
     * @return the value [pass] produced, or `null` when a pass was already in flight. A `null` is not a
     *   failure: this reason went into that pass's one merged follow-up, so the work is owned and the
     *   caller has nothing to report and nothing to retry. Callers that await their own result —
     *   `RunResumeWorker`, which may only claim work it owned — still block until the drain that
     *   covered them finished, rather than being detached.
     */
    suspend fun <T> request(reason: ResumeReason, pass: suspend (ResumeOperation) -> T): T? {
        val gate: CompletableDeferred<Unit>
        val owner: Boolean
        val operation: String
        synchronized(lock) {
            queued += reason
            val inFlight = drain
            if (running && inFlight != null) {
                gate = inFlight
                owner = false
                operation = operationId!!
            } else {
                running = true
                gate = CompletableDeferred<Unit>().also { drain = it }
                operation = Ids.uuidV7()
                operationId = operation
                owner = true
            }
        }
        // After the state change, so `coalesced=` reports the decision that was actually taken.
        log("resume_request operation=$operation reason=${reason.wire} coalesced=${!owner}")
        if (!owner) {
            gate.await()
            return null
        }
        try {
            var result: T? = null
            var first = true
            while (true) {
                val reasons = synchronized(lock) { queued.toList().also { queued.clear() } }
                val current = ResumeOperation(operation, reasons)
                val value = pass(current)
                if (first) {
                    result = value
                    first = false
                }
                // Written for every pass, including one that found nothing: `rows=0` is the evidence
                // that no intent was dispatched, which is otherwise indistinguishable from a pass that
                // never ran.
                log("resume_dispatch operation=$operation rows=${current.rowCount} pid=${pid()}")
                val again = synchronized(lock) {
                    if (queued.isEmpty()) {
                        resetLocked()
                        false
                    } else {
                        // One merged follow-up, covering every reason that arrived while running.
                        true
                    }
                }
                if (!again) break
            }
            return result
        } finally {
            // §8.1 item 11: a pass that throws or is cancelled must not leave the coordinator pinned in
            // RUNNING. If it did, every later trigger would merge into a drain nobody was running, and
            // no cold start would ever dispatch again until the process died.
            synchronized(lock) { resetLocked() }
            gate.complete(Unit)
        }
    }

    private fun resetLocked() {
        running = false
        drain = null
        operationId = null
    }

    companion object {
        private const val TAG = "SsResume"
    }
}
