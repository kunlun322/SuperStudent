package com.superstudent.app.features.packages.upload

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.UploadState
import com.superstudent.core.upload.ProcessLeaseRegistry
import com.superstudent.core.upload.SOURCE_NO_PROGRESS_MILLIS
import com.superstudent.core.upload.SourceLeaseEvent
import com.superstudent.core.upload.SourceLeaseReason
import com.superstudent.core.upload.SourceRecoveryLog
import com.superstudent.core.upload.SourceRecoveryTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one place that decides whether an `UPLOADING` row may be taken away from its owner
 * (ZLQ-132 §3.2, §3.4).
 *
 * Before this, a killed process left its row `UPLOADING` for the length of a 15 minute lease with
 * nothing able to say whether the owner was slow or gone; the only startup sweep re-enqueued
 * *resumable* rows, which by definition excluded the stuck one, so the row looked permanently
 * in-flight and offered no entry but 删除.
 *
 * Two rules make this safe rather than merely eager:
 *
 * - Liveness is a kernel fact, not a guess. A lease deadline says *when* an attempt promised to
 *   renew; [ProcessLeaseRegistry] says whether the promiser still exists, because the kernel drops a
 *   `FileLock` on process death. An expired lease with the owner's lock still held is therefore NOT
 *   reclaimable — §3.2 「不得回收」 — and all this pass may do is write `interrupt_requested_at`, which
 *   the owner's next guarded renew reads as "stand down". No second PUT ever starts.
 * - This is not a second process-level startup reconciliation. It runs as one `local` rule inside the
 *   existing [com.superstudent.app.reconcile.StartupReconciler] pass and is woken by runtime events
 *   afterwards; it adds no `runOnce` call site and never issues `RESUME_ALL` (ZLQ-117/ZLQ-122).
 *
 * Nothing here touches the network, an identity or a credential: deciding that an owner is gone is a
 * pure local rule, which is what lets an offline cold start still converge (§3.6). Only the
 * *re-enqueue* half is gated on being able to upload.
 */
class SourceUploadRecoveryCoordinator(
    /**
     * The five reads and writes this pass makes, as seams rather than as the repository itself.
     *
     * `PackageRepository` is final and its `DriveRepository` needs a live HTTP stack, so neither can be
     * faked on the JVM — and the reverse gate on §3.2 forbids proving recovery by seeding an owner that
     * was already dead, which is only avoidable if the *live*-owner branch is testable too. Lambdas are
     * the idiom the four below already use; they cost no interface and let a fake clock drive the real
     * `converge` decision against real OS locks.
     */
    private val listCandidates: suspend (nowMillis: Long) -> List<SourceAssetEntity>,
    private val releaseOrphan: suspend (sourceId: String, nowMillis: Long) -> Boolean,
    private val requestStandDown: suspend (sourceId: String, nowMillis: Long) -> Boolean,
    private val listResumable: suspend (nowMillis: Long) -> List<SourceAssetEntity>,
    private val nextDeadline: suspend () -> Long?,
    private val leases: ProcessLeaseRegistry,
    /** Credential present AND validated network. Gates enqueuing, never the local convergence. */
    private val canUpload: () -> Boolean,
    private val enqueueUpload: (sourceId: String) -> Unit,
    private val enqueueDelete: (sourceId: String) -> Unit,
    /**
     * Unique one-shot fallback for the next deadline. The in-process [delay] below is the fast path,
     * but it dies with the process, and a deadline nobody wakes for is a row that stays stuck until
     * the next cold start.
     */
    private val armDeadline: (delayMillis: Long) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A monitor rather than a `Mutex`, for the same reason `ResumeAllCoordinator` uses one: every
     * critical section is a few field writes, and keeping them non-suspending is what lets the
     * `finally` reset the state on a cancelled pass without a `NonCancellable` wrapper.
     */
    private val lock = Any()

    private var running = false
    private var queued = LinkedHashSet<SourceRecoveryTrigger>()
    private var drain: CompletableDeferred<Unit>? = null
    private var deadlineJob: Job? = null

    /**
     * Runs one pass covering [trigger], or merges it into the single follow-up of a pass already in
     * flight. Merging is why a hundred triggers arriving mid-pass cost two passes and not a hundred:
     * the reasons wait in a set, so "pending" is "the set is non-empty" and *at most one* follow-up is
     * true by construction.
     *
     * Suspends until the pass that covered this trigger has finished, so a caller — the startup rule,
     * a worker reporting `retry()` — never returns having only asked.
     */
    suspend fun request(trigger: SourceRecoveryTrigger) {
        val owner: Boolean
        val gate: CompletableDeferred<Unit>?
        synchronized(lock) {
            queued += trigger
            val inFlight = drain
            if (running && inFlight != null) {
                owner = false
                gate = inFlight
            } else {
                running = true
                owner = true
                gate = CompletableDeferred<Unit>().also { drain = it }
            }
        }
        if (!owner) {
            // The merged pass covers this trigger. Awaiting it rather than detaching is what lets the
            // startup rule below report a convergence that has actually happened.
            gate?.await()
            return
        }
        try {
            while (true) {
                val triggers = synchronized(lock) { queued.toList().also { queued.clear() } }
                runCatching { pass(triggers) }
                val again = synchronized(lock) {
                    if (queued.isEmpty()) {
                        resetLocked()
                        false
                    } else true
                }
                if (!again) break
            }
        } finally {
            // A pass that throws or is cancelled must not leave the coordinator pinned in RUNNING, or
            // every later trigger would merge into a drain nobody is running and nothing would ever
            // recover again until the process died.
            synchronized(lock) { resetLocked() }
            gate?.complete(Unit)
        }
    }

    private suspend fun pass(triggers: List<SourceRecoveryTrigger>) {
        val now = clock()
        val candidates = listCandidates(now)
        var interrupted = 0
        var enqueued = 0

        candidates.forEach { row ->
            when {
                // A tombstone owes a delete, not an upload, and its worker may have died with the
                // process that armed it.
                row.deletePending -> {
                    enqueueDelete(row.sourceId)
                    enqueued++
                }
                row.uploadState == UploadState.UPLOADING.name ->
                    if (converge(row, now)) interrupted++
                canUpload() -> {
                    enqueueUpload(row.sourceId)
                    enqueued++
                }
                // Offline or no credential: the row keeps its actionable FAILED/PROCESS_INTERRUPTED
                // presentation, which is exactly what §3.6 requires — never a fabricated
                // NETWORK_UNAVAILABLE and never a silent drop.
            }
        }

        // Rows released above became `FAILED / PROCESS_INTERRUPTED / retryable=1 / next_retry_at=now`,
        // so they are due in this same round; re-reading is what makes §3.4's "同轮重新 claim" true
        // instead of waiting for the next trigger.
        if (interrupted > 0 && canUpload()) {
            listResumable(clock()).forEach { row ->
                enqueueUpload(row.sourceId)
                enqueued++
            }
        }

        val nextDue = nextDeadline()?.let { due ->
            (due - clock()).coerceAtLeast(0L)
        }
        if (nextDue != null) {
            schedule(nextDue)
        } else {
            synchronized(lock) { deadlineJob?.cancel(); deadlineJob = null }
        }

        triggers.forEach { trigger ->
            log(SourceRecoveryLog.recovery(trigger, candidates.size, interrupted, enqueued, nextDue))
        }
    }

    /**
     * Decides one `UPLOADING` row. Returns true only when the row was actually released — a request to
     * stand down leaves it `UPLOADING`, and counting that as a recovery would claim a convergence the
     * row has not had.
     */
    private suspend fun converge(row: SourceAssetEntity, now: Long): Boolean {
        val sourceId = row.sourceId
        val owner = row.leaseOwnerId
        val leaseExpired = row.leaseUntil?.let { it < now } ?: true
        val noProgress = row.lastProgressAt?.let { now - it > SOURCE_NO_PROGRESS_MILLIS } ?: false

        // §3.2 condition 1 (no owner recorded — a pre-v4 row) and condition 3 (our own id with no live
        // attempt registered against it, i.e. the orphan of a cancelled coroutine).
        val unowned = owner.isNullOrBlank() ||
            (owner == leases.ownerId && leaseExpired && leases.activeAttempt(sourceId) == null)

        // §3.2 condition 2. A successful `tryLock` on another owner's file *is* the proof that owner
        // is gone, because the kernel drops the lock on process death; a PID could never say that.
        val deadOwner = if (!unowned && !owner.isNullOrBlank() && owner != leases.ownerId) {
            leases.tryAcquireOwner(owner)
        } else null

        if (unowned || deadOwner != null) {
            val execution = leases.tryAcquireSource(sourceId)
            if (execution == null) {
                // Somebody is working this source right now. Releasing under them is how two owners
                // end up disagreeing about one row.
                logInterrupt(sourceId, owner, row.attemptToken, SourceLeaseReason.OWNER_ALIVE)
                return false
            }
            try {
                val released = releaseOrphan(sourceId, now)
                if (released) {
                    logInterrupt(
                        sourceId, owner, row.attemptToken,
                        if (unowned) SourceLeaseReason.ORPHAN_NO_OWNER else SourceLeaseReason.ORPHAN_DEAD_OWNER,
                    )
                }
                return released
            } finally {
                // Held across the UPDATE, then released: a process resurrecting mid-statement must not
                // be able to re-acquire and find its row already handed over (§3.2).
                execution.close()
                deadOwner?.close()
            }
        }

        // 「不得回收」. The owner is provably alive, so the only honest write is the interrupt request;
        // the row keeps its token and its `UPLOADING` state, which is what makes a second concurrent
        // PUT impossible rather than merely unlikely.
        logInterrupt(sourceId, owner, row.attemptToken, if (noProgress) SourceLeaseReason.NO_PROGRESS else SourceLeaseReason.OWNER_ALIVE)
        requestStandDown(sourceId, now)
        return false
    }

    private fun logInterrupt(sourceId: String, owner: String?, token: String?, reason: SourceLeaseReason) {
        log(SourceRecoveryLog.lease(sourceId, SourceLeaseEvent.INTERRUPT, owner, token, reason))
    }

    /**
     * Arms the next pass twice: an in-process [delay] for the fast path, and a unique one-shot worker
     * for the case where this process does not survive to it. Both wake the same coordinator through
     * the same single-flight guard, so arming twice costs one pass.
     */
    private fun schedule(delayMillis: Long) {
        armDeadline(delayMillis)
        synchronized(lock) {
            deadlineJob?.cancel()
            deadlineJob = scope.launch {
                delay(delayMillis)
                request(SourceRecoveryTrigger.DEADLINE)
            }
        }
    }

    private fun resetLocked() {
        running = false
        drain = null
    }

    companion object {
        private const val TAG = "SsUpload"

        /**
         * The design's upper bound (§3.4): once a deadline passes, the row must leave its ownerless
         * `UPLOADING` state within 30 s. The in-process delay is exact; this is the slack a
         * WorkManager fallback is allowed before it counts as a miss.
         */
        const val DEADLINE_SLACK_MILLIS = 30_000L
    }
}
