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
import com.superstudent.app.features.tasks.QmindDeleter
import com.superstudent.core.database.DatabaseBootstrapState
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.repository.DeleteOutcome
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Pushes an armed delete tombstone to completion (design §3.9 item 5).
 *
 * The tombstone is armed synchronously by whoever accepted the student's 删除, so the row leaves every
 * manifest projection and every business read immediately; what is left is remote work, and remote
 * work may not be attempted once and abandoned. This worker is what makes that durable: it survives
 * the process that armed it, retries with backoff, and only lets `completeDelete` remove the Room row
 * — which it does after the object is confirmed gone and the manifest re-published, never before.
 *
 * Unique per source and `KEEP`, so a second 重试删除 taps merges into the attempt already in flight
 * instead of starting a second delete transaction against the same tombstone (R6).
 */
class SourceDeleteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE) ?: return Result.success()
        if (DatabaseBootstrapState.isFailed) return Result.success()
        val container = applicationContext.appContainer
        val row = container.packageRepository.findSource(sourceId) ?: return Result.success()
        if (!row.deletePending) return Result.success()
        val identity = container.accountRepository.currentIdentityId() ?: return Result.retry()

        // The Notebook half goes first, and it is idempotent: a source already forgotten from
        // profile.json reports NOT_INGESTED, so a retry of the durable half does not re-run a
        // six-minute delete session that already converged.
        val retrieval = try {
            container.qmindDeleter.delete(identity, sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.retry()
        }
        if (retrieval.outcome == QmindDeleter.Outcome.DELETE_PENDING) return Result.retry()

        val outcome = try {
            container.packageRepository.completeDelete(identity, row.packageId, sourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The tombstone stays armed either way, so the row keeps 待清理 and its 重试删除 entry.
            return Result.retry()
        }
        if (outcome is DeleteOutcome.Pending) return Result.retry()

        // §3.9 item 8: the local handle was only ever held for this upload. Releasing it after the
        // terminal delete — and not before — is what keeps a failed delete retryable from the same
        // staged bytes.
        if (LocalAccessMode.of(row.localAccessMode) == LocalAccessMode.PERSISTED_URI) {
            SourceAccess.releaseGrant(applicationContext, row.localUri)
        }
        SourceAccess.deleteStaging(applicationContext, sourceId)
        return Result.success()
    }

    companion object {
        const val KEY_SOURCE = "source_id"

        fun workName(sourceId: String): String = "source-delete-$sourceId"

        fun enqueue(context: Context, sourceId: String) {
            val request = OneTimeWorkRequestBuilder<SourceDeleteWorker>()
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
