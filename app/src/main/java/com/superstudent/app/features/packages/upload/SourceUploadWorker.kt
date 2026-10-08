package com.superstudent.app.features.packages.upload

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
import com.superstudent.core.database.DatabaseBootstrapState
import com.superstudent.core.repository.UploadOutcome
import com.superstudent.core.upload.SourceErrorCode
import com.superstudent.core.upload.SourceRecoveryTrigger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The unattended half of the retry mechanism (design increment §3): network recovery and exponential
 * backoff. The foreground service handles "run it now"; both share [UploadExecutor], so the state
 * transitions and the idempotency guarantees are identical whichever one gets there first.
 *
 * Unique work is keyed per source and kept, never replaced: a manual retry must not cancel an
 * attempt that is already in flight.
 */
class SourceUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE) ?: return Result.success()
        // The schema was opened and rejected, so there is no row to read and no state to write. The
        // blocking page owns the student's attention; retrying would only spin.
        if (DatabaseBootstrapState.isFailed) return Result.success()
        val container = applicationContext.appContainer
        val identity = container.accountRepository.currentIdentityId() ?: return Result.retry()
        val outcome = try {
            container.uploadExecutor.upload(identity, sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // uploadSource already wrote the classified failure to Room; only the schedule is ours.
            return Result.retry()
        }
        // Every terminal outcome wakes the recovery pass: it is what re-arms the next deadline, and
        // without it a row that just failed inside its budget waits for the next cold start (§3.4).
        container.sourceRecovery.request(SourceRecoveryTrigger.OUTCOME)
        if (outcome is UploadOutcome.Failed &&
            outcome.failure.code == SourceErrorCode.NETWORK_UNAVAILABLE
        ) {
            // §3.4 NETWORK. This worker's own retry chain only ever re-runs *this* source, and its
            // backoff is a guess at when the network returns; a CONNECTED-constrained pass is not, and
            // it covers every row the outage stranded rather than the one that reported it.
            SourceRecoveryWorker.onNetwork(applicationContext)
        }
        return when {
            outcome is UploadOutcome.Failed && outcome.failure.retryable -> Result.retry()
            // Not due yet, or its lease is alive elsewhere. `success()` here would drop the retry
            // chain and the source would never upload (design §3.5).
            outcome is UploadOutcome.Deferred -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_SOURCE = "source_id"

        fun workName(sourceId: String): String = "source-upload-$sourceId"

        fun enqueue(context: Context, sourceId: String) {
            val request = OneTimeWorkRequestBuilder<SourceUploadWorker>()
                .setInputData(workDataOf(KEY_SOURCE to sourceId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(workName(sourceId), ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
