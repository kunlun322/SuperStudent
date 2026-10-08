package com.superstudent.app.features.packages.upload

import android.content.Context
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.repository.PackageRepository
import com.superstudent.core.repository.UploadOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that performs an upload attempt. The foreground service (user-initiated, run now)
 * and the WorkManager worker (network recovery, backoff) both go through here, so there is exactly
 * one reader of local content and one writer of upload state (design increment §3).
 *
 * Attempts are serialized: each one holds the whole file in memory to hash it, and a multi-select
 * batch of large PDFs would otherwise exhaust the heap.
 */
class UploadExecutor(
    private val context: Context,
    private val packageRepository: PackageRepository,
) {

    private val gate = Mutex()

    suspend fun upload(identityId: String, sourceId: String): UploadOutcome = gate.withLock {
        val row = packageRepository.findSource(sourceId) ?: return@withLock UploadOutcome.Skipped
        UploadProgressBus.begin(sourceId, row.displayName)
        try {
            val outcome = packageRepository.uploadSource(
                identityId = identityId,
                sourceId = sourceId,
                readBytes = { current -> SourceAccess.read(context, current) },
                onProgress = { sent, all -> UploadProgressBus.progress(sourceId, sent, all) },
            )
            if (outcome is UploadOutcome.Uploaded) {
                // The content now lives on Drive; drop the local handle we were holding for it.
                if (LocalAccessMode.of(row.localAccessMode) == LocalAccessMode.PERSISTED_URI) {
                    SourceAccess.releaseGrant(context, row.localUri)
                }
                SourceAccess.deleteStaging(context, sourceId)
            }
            outcome
        } finally {
            UploadProgressBus.clear(sourceId)
        }
    }

    /** Runs a batch in order, stopping only when every row has been attempted once. */
    suspend fun uploadAll(identityId: String, sourceIds: List<String>): List<UploadOutcome> =
        sourceIds.map { upload(identityId, it) }
}
