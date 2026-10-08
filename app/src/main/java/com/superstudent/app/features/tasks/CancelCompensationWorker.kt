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
import androidx.work.workDataOf
import com.superstudent.app.appContainer
import com.superstudent.core.repository.CompensationOutcome
import java.util.concurrent.TimeUnit

/**
 * Retries one canceled attempt's terminal compensation until it completes (ZLQ-114 §5.4).
 *
 * The work is keyed on `(taskId, attempt)` because that pair — not the package, not the account — is
 * what the compensation acts on: it deletes exactly that attempt's tmp subtree and confirms exactly
 * that attempt's Session. `ExistingWorkPolicy.KEEP` is what makes a duplicate enqueue harmless: the
 * cold-start rule and the foreground cancel path can both ask for the same attempt, and the second ask
 * must join the chain already running rather than start a parallel one. Two chains racing on one
 * attempt is how a delete and a manifest publish could interleave.
 *
 * Network is required and the backoff is exponential because the only reason this worker exists is
 * that the cancel could not reach the cloud; retrying that every 10 seconds forever would just burn
 * battery on a device that is still offline.
 */
class CancelCompensationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Both keys always travel together; a malformed input has nothing to compensate and must not
        // retry forever on data that will never become valid.
        val taskId = inputData.getString(KEY_TASK_ID)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        if (taskId == null || attempt <= 0) return Result.success()
        // Awaited in full, never detached: returning before the pass finished would tell WorkManager
        // the compensation succeeded while the Session it was supposed to stop keeps billing, and a
        // process death right after would lose the only retry that was scheduled.
        val outcome = runCatching {
            applicationContext.appContainer.cancelCompensator.compensate(taskId, attempt)
        }.getOrElse { return Result.retry() }
        return when (outcome) {
            CompensationOutcome.COMPLETED -> Result.success()
            // Marker still set: the next attempt re-proves the same idempotent steps.
            CompensationOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
        const val KEY_ATTEMPT = "attempt"
        private const val NAME_PREFIX = "ss:cancel-task:"
        private const val BACKOFF_SECONDS = 10L

        /**
         * The one work name for one attempt. Both enqueuers must derive it from here: a second format
         * would silently produce a second chain for the same attempt, and `KEEP` could not dedupe it.
         */
        fun workName(taskId: String, attempt: Int): String = "$NAME_PREFIX$taskId-$attempt"

        fun enqueue(context: Context, taskId: String, attempt: Int) {
            val request = OneTimeWorkRequestBuilder<CancelCompensationWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to taskId, KEY_ATTEMPT to attempt))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(workName(taskId, attempt), ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
