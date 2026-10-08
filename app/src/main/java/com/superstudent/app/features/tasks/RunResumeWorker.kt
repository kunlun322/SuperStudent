package com.superstudent.app.features.tasks

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superstudent.core.model.TaskState
import java.util.concurrent.TimeUnit

/**
 * One-shot recovery: when the network comes back, converge the runs a dead process left behind and
 * re-attach observation to the ones the cloud is still working on (design §4 — a restart must not
 * resubmit a turn, only resume watching it).
 */
class RunResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Awaited, not detached: returning success before the recovery pass ran would report work
        // this worker never owned, and a process death right after would lose it silently.
        // The bare form is deliberate — `reason` defaults to NETWORK_RECOVERED, which is this caller,
        // and WorkManager only runs it once its CONNECTED constraint is satisfied (ZLQ-120 §5.2).
        val outcome = runCatching { TaskForegroundService.reconcileAndResume(applicationContext) }
            .getOrNull() ?: return Result.retry()
        // A credential that cannot be decrypted is not a transient condition, so this is not the
        // retry path: the pass already stopped at the pre-flight, the runs are converged, and
        // backing off would only re-run a gate that cannot pass until the student logs in again
        // (ZLQ-119 §4.3/§4.4).
        if (outcome.credentialFailure != null) return Result.success()
        // A refused handoff leaves the runs active with nobody watching them; waiting for the next
        // attempt beats claiming they were resumed.
        return if (outcome.serviceStarted) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "ss-resume-runs"

        /**
         * States worth handing to the service again. `FAILED_RETRYABLE` is deliberately absent: it is
         * a terminal the student retries by hand, so scheduling background work for it would restart
         * a generation nobody asked for — including the `START_INTERRUPTED` rows recovery produces.
         */
        private val RESUMABLE = setOf(
            TaskState.QUEUED.name,
            TaskState.RUNNING.name,
            TaskState.RETRY_WAIT.name,
            TaskState.UNKNOWN.name,
            TaskState.CANCEL_REQUESTED.name,
        )

        fun isResumable(state: String?): Boolean = state in RESUMABLE

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RunResumeWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
