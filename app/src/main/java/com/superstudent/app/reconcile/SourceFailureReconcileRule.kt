package com.superstudent.app.reconcile

import android.content.Context
import com.superstudent.app.features.packages.upload.SourceAccess
import com.superstudent.core.repository.PackageRepository
import com.superstudent.core.upload.SourceErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gives the rows ZLQ-105 stranded an exit again.
 *
 * Before the read stage normalized its exceptions, a deleted or moved original was recorded as
 * `NETWORK_UNAVAILABLE`, spent its whole automatic budget and landed in `FAILED / retryable=0` with no
 * entry but 删除. The stored code cannot be used to find those rows — it *is* the wrong code — so every
 * candidate is re-probed instead: a handle that no longer opens becomes `LOCAL_ONLY` with
 * "重新选择文件", while a handle that still opens keeps its network failure untouched, because resetting
 * that one would drag a genuinely exhausted retry back into an automatic loop (ZLQ-110 §3.4).
 */
class SourceFailureReconcileRule(
    private val context: Context,
    private val packageRepository: PackageRepository,
) : StartupReconcileRule {

    override val name = "source-failure-rows"

    override suspend fun reconcile(): Unit = withContext(Dispatchers.IO) {
        if (!hasValidatedNetwork(context)) return@withContext
        packageRepository.listExhaustedTransientFailures().forEach { row ->
            if (!SourceAccess.canRead(context, row)) {
                packageRepository.markLocalOnly(
                    sourceId = row.sourceId,
                    errorCode = SourceErrorCode.URI_PERMISSION_REQUIRED,
                    message = MESSAGE,
                )
            }
        }
    }

    companion object {
        /** The classifier's own wording, so a repaired row reads exactly like a fresh one. */
        const val MESSAGE = "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件"
    }
}
