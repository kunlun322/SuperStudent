package com.superstudent.app.features.packages.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.superstudent.app.appContainer
import com.superstudent.core.database.DatabaseBootstrapState
import com.superstudent.core.upload.SourceRecoveryTrigger
import java.util.concurrent.TimeUnit

/**
 * The process-survival half of the recovery deadline (design §3.4), and the network-back trigger.
 *
 * [SourceUploadRecoveryCoordinator] waits its own `delay` for the earliest `lease_until` /
 * `next_retry_at`, which is exact but dies with the process. This is the fallback for that: one unique
 * one-shot request per pass, replaced rather than kept, because every pass recomputes the *earliest*
 * remaining deadline and a stale later one must not survive it.
 *
 * The deadline request carries no network constraint on purpose. Deciding that an owner is gone is a
 * pure local rule (§3.6), so an offline device still owes the convergence; only the re-enqueue that
 * follows is gated, and `SourceUploadWorker` has its own `CONNECTED` constraint for that. The
 * network request is the opposite case: it exists *because* connectivity came back, so it waits for
 * `CONNECTED` and is what wakes a row that failed offline without making the student open the page.
 */
class SourceRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** Which §3.4 event woke this pass. The deadline is the default because it is the armed case. */
    private val trigger: SourceRecoveryTrigger
        get() = inputData.getString(KEY_TRIGGER)
            ?.let { wire -> SourceRecoveryTrigger.entries.firstOrNull { it.wire == wire } }
            ?: SourceRecoveryTrigger.DEADLINE

    override suspend fun doWork(): Result {
        if (DatabaseBootstrapState.isFailed) return Result.success()
        applicationContext.appContainer.sourceRecovery.request(trigger)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "source-recovery-deadline"
        private const val NETWORK_WORK_NAME = "source-recovery-network"
        private const val KEY_TRIGGER = "trigger"

        fun arm(context: Context, delayMillis: Long) {
            enqueue(context, WORK_NAME, OneTimeWorkRequestBuilder<SourceRecoveryWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS))
        }

        /**
         * §3.4 `NETWORK`: a separate unique name from the deadline's, so a pending deadline is not
         * replaced by a connectivity event that would have fired later anyway — and vice versa.
         */
        fun onNetwork(context: Context) {
            enqueue(context, NETWORK_WORK_NAME, OneTimeWorkRequestBuilder<SourceRecoveryWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to SourceRecoveryTrigger.NETWORK.wire))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS))
        }

        private fun enqueue(
            context: Context,
            name: String,
            builder: OneTimeWorkRequest.Builder,
        ) {
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, builder.build())
            }
        }
    }
}
