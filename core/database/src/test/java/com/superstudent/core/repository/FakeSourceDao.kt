package com.superstudent.core.repository

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.SourceDao
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.UploadState
import com.superstudent.core.upload.SourceErrorCode
import kotlinx.coroutines.flow.Flow

/**
 * An in-memory [SourceDao] that emulates every guarded UPDATE's WHERE clause, not just its SET list.
 *
 * The guards *are* the fencing design (ZLQ-132 §5.4): a fake that returns 1 unconditionally would
 * let a reclaimed attempt commit, a tombstoned row be claimed, and a live owner's lease be renewed
 * after an interrupt — the three things this batch exists to stop. So each method below re-derives
 * its predicate from the same columns the SQL names, and the fencing tests assert on the returned
 * row count rather than on a recorded call.
 *
 * This is a JVM fake of client-side SQL. It is deliberately **not** evidence about server-side
 * atomicity: `remote_generation` fencing, manifest revision CAS and the delete tombstone on the
 * backend are C1/C5 out of scope for this batch and are reported as 「不可执行」.
 */
internal class FakeSourceDao : SourceDao {
    private val rows = mutableMapOf<String, SourceAssetEntity>()

    /** Stands in for the real disk-and-network latency between the two writes. */
    var markUploadedDelayMillis: Long = 0

    /** Non-suspend read for assertions, so a test body does not need a second `runBlocking`. */
    fun peek(sourceId: String): SourceAssetEntity? = synchronized(rows) { rows[sourceId] }

    /** Test seeding, bypassing every guard on purpose: a fixture is not an attempt. */
    fun seed(row: SourceAssetEntity) {
        synchronized(rows) { rows[row.sourceId] = row }
    }

    fun snapshot(): List<SourceAssetEntity> = synchronized(rows) { rows.values.toList() }

    private fun mutate(sourceId: String, guard: (SourceAssetEntity) -> Boolean, apply: (SourceAssetEntity) -> SourceAssetEntity): Int =
        synchronized(rows) {
            val current = rows[sourceId] ?: return 0
            if (!guard(current)) return 0
            rows[sourceId] = apply(current)
            return 1
        }

    private fun isDue(row: SourceAssetEntity, nowMillis: Long) =
        row.nextRetryAt == null || row.nextRetryAt <= nowMillis

    private fun inBudget(row: SourceAssetEntity, maxAttempts: Int) = row.attemptCount < maxAttempts

    private fun clearLease(row: SourceAssetEntity) = row.copy(
        attemptToken = null,
        leaseOwnerId = null,
        leaseUntil = null,
        leaseHeartbeatAt = null,
        uploadStartedAt = null,
        lastProgressAt = null,
        interruptRequestedAt = null,
    )

    override suspend fun upsert(source: SourceAssetEntity) {
        synchronized(rows) { rows[source.sourceId] = source }
    }

    override suspend fun upsertAll(sources: List<SourceAssetEntity>) = sources.forEach { upsert(it) }

    override suspend fun insert(source: SourceAssetEntity): Long {
        upsert(source)
        return 1
    }

    override fun observeByPackage(packageId: String): Flow<List<SourceAssetEntity>> = unsupported()

    override suspend fun listByPackage(packageId: String): List<SourceAssetEntity> =
        synchronized(rows) {
            rows.values.filter { it.packageId == packageId }.sortedBy { it.addedAt }.toList()
        }

    override suspend fun find(sourceId: String): SourceAssetEntity? =
        synchronized(rows) { rows[sourceId] }

    override suspend fun findByPath(drivePath: String): SourceAssetEntity? =
        synchronized(rows) { rows.values.firstOrNull { it.drivePath == drivePath } }

    override suspend fun claimForUpload(
        sourceId: String,
        token: String,
        ownerId: String,
        leaseUntilMillis: Long,
        heartbeatAt: Long,
        uploadStartedAt: Long,
        lastProgressAt: Long,
        nowMillis: Long,
        maxAttempts: Int,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row ->
            !row.deletePending &&
                row.interruptRequestedAt == null &&
                (
                    (row.uploadState == UploadState.PENDING.name && isDue(row, nowMillis)) ||
                        (row.uploadState == UploadState.FAILED.name && row.retryable &&
                            inBudget(row, maxAttempts) && isDue(row, nowMillis))
                    )
        },
    ) { row ->
        row.copy(
            uploadState = UploadState.UPLOADING.name,
            attemptToken = token,
            leaseOwnerId = ownerId,
            leaseUntil = leaseUntilMillis,
            leaseHeartbeatAt = heartbeatAt,
            uploadStartedAt = uploadStartedAt,
            lastProgressAt = lastProgressAt,
            attemptCount = row.attemptCount + 1,
            errorCode = null,
            errorMessage = null,
            nextRetryAt = null,
            interruptRequestedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun renewLease(
        sourceId: String,
        token: String,
        ownerId: String,
        heartbeatAt: Long,
        leaseUntilMillis: Long,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row ->
            row.attemptToken == token && row.leaseOwnerId == ownerId &&
                row.uploadState == UploadState.UPLOADING.name && !row.deletePending &&
                row.interruptRequestedAt == null
        },
    ) { row ->
        row.copy(leaseHeartbeatAt = heartbeatAt, leaseUntil = leaseUntilMillis, updatedAt = now)
    }

    override suspend fun recordProgress(
        sourceId: String,
        token: String,
        ownerId: String,
        progressAt: Long,
        heartbeatAt: Long,
        leaseUntilMillis: Long,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row ->
            row.attemptToken == token && row.leaseOwnerId == ownerId &&
                row.uploadState == UploadState.UPLOADING.name && !row.deletePending &&
                row.interruptRequestedAt == null
        },
    ) { row ->
        row.copy(
            lastProgressAt = progressAt,
            leaseHeartbeatAt = heartbeatAt,
            leaseUntil = leaseUntilMillis,
            updatedAt = now,
        )
    }

    override suspend fun countOwnedAttempt(sourceId: String, token: String, ownerId: String): Int =
        synchronized(rows) {
            rows.values.count {
                it.sourceId == sourceId && it.attemptToken == token &&
                    it.leaseOwnerId == ownerId && !it.deletePending
            }
        }

    override suspend fun markUploaded(
        sourceId: String,
        token: String,
        ownerId: String,
        drivePath: String,
        sha256: String,
        sizeBytes: Long,
        canonicalType: String,
        now: String,
    ): Int {
        if (markUploadedDelayMillis > 0) Thread.sleep(markUploadedDelayMillis)
        return mutate(
            sourceId,
            guard = { row ->
                row.attemptToken == token && row.leaseOwnerId == ownerId && !row.deletePending
            },
        ) { row ->
            clearLease(
                row.copy(
                    uploadState = UploadState.UPLOADED.name,
                    drivePath = drivePath,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    canonicalType = canonicalType,
                    localUri = null,
                    localAccessMode = LocalAccessMode.NONE.name,
                    errorCode = null,
                    errorMessage = null,
                    retryable = false,
                    nextRetryAt = null,
                    updatedAt = now,
                )
            )
        }
    }

    override suspend fun recordFailure(
        sourceId: String,
        token: String,
        ownerId: String,
        state: String,
        errorCode: String,
        errorMessage: String,
        retryable: Boolean,
        nextRetryAtMillis: Long?,
        drivePath: String?,
        sha256: String?,
        sizeBytes: Long?,
        canonicalType: String?,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row ->
            row.attemptToken == token && row.leaseOwnerId == ownerId && !row.deletePending
        },
    ) { row ->
        clearLease(
            row.copy(
                uploadState = state,
                errorCode = errorCode,
                errorMessage = errorMessage,
                retryable = retryable,
                nextRetryAt = nextRetryAtMillis,
                drivePath = drivePath ?: row.drivePath,
                sha256 = sha256 ?: row.sha256,
                sizeBytes = sizeBytes ?: row.sizeBytes,
                canonicalType = canonicalType ?: row.canonicalType,
                updatedAt = now,
            )
        )
    }

    override suspend fun markLocalOnly(
        sourceId: String,
        localAccessMode: String,
        errorCode: String,
        errorMessage: String,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row -> row.uploadState != UploadState.UPLOADED.name && !row.deletePending },
    ) { row ->
        clearLease(
            row.copy(
                uploadState = UploadState.LOCAL_ONLY.name,
                localAccessMode = localAccessMode,
                errorCode = errorCode,
                errorMessage = errorMessage,
                retryable = false,
                nextRetryAt = null,
                updatedAt = now,
            )
        )
    }

    override suspend fun requestInterrupt(sourceId: String, nowMillis: Long, now: String): Int =
        mutate(
            sourceId,
            guard = { row ->
                row.uploadState == UploadState.UPLOADING.name && !row.deletePending &&
                    row.interruptRequestedAt == null
            },
        ) { row -> row.copy(interruptRequestedAt = nowMillis, updatedAt = now) }

    override suspend fun interruptAttempt(
        sourceId: String,
        nowMillis: Long,
        maxAttempts: Int,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row -> row.uploadState == UploadState.UPLOADING.name && !row.deletePending },
    ) { row ->
        // `attempt_count` is deliberately not incremented: reclaiming an orphan must not spend the
        // student's budget, or a crash loop would exhaust it without ever uploading (design §3.2).
        val withinBudget = row.attemptCount < maxAttempts
        clearLease(
            row.copy(
                uploadState = UploadState.FAILED.name,
                errorCode = SourceErrorCode.PROCESS_INTERRUPTED,
                errorMessage = "上传中断，请重试",
                retryable = withinBudget,
                nextRetryAt = if (withinBudget) nowMillis else null,
                updatedAt = now,
            )
        )
    }

    override suspend fun beginDelete(sourceId: String, nowMillis: Long, now: String): Int =
        mutate(sourceId, guard = { row -> !row.deletePending }) { row ->
            row.copy(
                deletePending = true,
                deleteRequestedAt = nowMillis,
                interruptRequestedAt = nowMillis,
                attemptToken = null,
                updatedAt = now,
            )
        }

    override suspend fun finishDelete(sourceId: String): Int = synchronized(rows) {
        val current = rows[sourceId] ?: return 0
        if (!current.deletePending) return 0
        rows.remove(sourceId)
        return 1
    }

    override suspend fun requeueForRetry(sourceId: String, now: String): Int = mutate(
        sourceId,
        guard = { row -> row.uploadState == UploadState.FAILED.name && !row.deletePending },
    ) { row ->
        clearLease(
            row.copy(
                uploadState = UploadState.PENDING.name,
                errorCode = null,
                errorMessage = null,
                retryable = true,
                attemptCount = 0,
                nextRetryAt = null,
                updatedAt = now,
            )
        )
    }

    override suspend fun bindLocalFile(
        sourceId: String,
        localUri: String?,
        localAccessMode: String,
        sizeBytes: Long?,
        state: String,
        errorCode: String?,
        errorMessage: String?,
        retryable: Boolean,
        now: String,
    ): Int = mutate(
        sourceId,
        guard = { row -> row.uploadState != UploadState.UPLOADED.name && !row.deletePending },
    ) { row ->
        clearLease(
            row.copy(
                localUri = localUri,
                localAccessMode = localAccessMode,
                sizeBytes = sizeBytes ?: row.sizeBytes,
                uploadState = state,
                errorCode = errorCode,
                errorMessage = errorMessage,
                retryable = retryable,
                attemptCount = 0,
                nextRetryAt = null,
                updatedAt = now,
            )
        )
    }

    override suspend fun listResumable(nowMillis: Long, maxAttempts: Int): List<SourceAssetEntity> =
        synchronized(rows) {
            rows.values
                .filter { row ->
                    !row.deletePending &&
                        (
                            (row.uploadState == UploadState.PENDING.name && isDue(row, nowMillis)) ||
                                (row.uploadState == UploadState.FAILED.name && row.retryable &&
                                    inBudget(row, maxAttempts) && isDue(row, nowMillis))
                            )
                }
                .sortedBy { it.addedAt }
                .toList()
        }

    override suspend fun listRecoveryCandidates(
        nowMillis: Long,
        noProgressBefore: Long,
        maxAttempts: Int,
    ): List<SourceAssetEntity> = synchronized(rows) {
        rows.values
            .filter { row ->
                row.deletePending ||
                    (row.uploadState == UploadState.UPLOADING.name &&
                        (row.leaseUntil == null || row.leaseUntil < nowMillis)) ||
                    (row.uploadState == UploadState.UPLOADING.name &&
                        row.lastProgressAt?.let { it < noProgressBefore } == true) ||
                    (row.uploadState == UploadState.PENDING.name && isDue(row, nowMillis)) ||
                    (row.uploadState == UploadState.FAILED.name && row.retryable &&
                        inBudget(row, maxAttempts) && isDue(row, nowMillis))
            }
            .sortedBy { it.addedAt }
            .toList()
    }

    override suspend fun nextRecoveryDeadline(maxAttempts: Int, nowMillis: Long): Long? =
        synchronized(rows) {
            rows.values.mapNotNull { row ->
                when {
                    row.deletePending -> row.deleteRequestedAt
                    row.uploadState == UploadState.UPLOADING.name -> row.leaseUntil
                    row.uploadState == UploadState.FAILED.name && row.retryable &&
                        inBudget(row, maxAttempts) -> row.nextRetryAt
                    else -> null
                }
                // `due > nowMillis` mirrors the shipped statement: a row actionable now is already
                // being claimed by the pass that is running, and reporting it would arm a follow-up
                // that fires immediately and re-arms itself for as long as the row stays unclaimable.
            }.filter { it > nowMillis }.minOrNull()
        }

    /** `AUTO_RETRYABLE` minus the manifest code, whose object is already on Drive. */
    private val exhaustedTransientCodes =
        SourceErrorCode.AUTO_RETRYABLE - SourceErrorCode.MANIFEST_PUBLISH_FAILED

    override suspend fun listExhaustedTransientFailures(): List<SourceAssetEntity> =
        synchronized(rows) {
            rows.values
                .filter {
                    it.uploadState == UploadState.FAILED.name && !it.retryable &&
                        !it.deletePending && it.errorCode in exhaustedTransientCodes
                }
                .sortedBy { it.addedAt }
                .toList()
        }

    override suspend fun listUploading(packageId: String): List<SourceAssetEntity> =
        synchronized(rows) {
            rows.values
                .filter { it.packageId == packageId && it.uploadState == UploadState.UPLOADING.name }
                .sortedBy { it.addedAt }
                .toList()
        }

    override suspend fun delete(sourceId: String) {
        synchronized(rows) { rows.remove(sourceId) }
    }

    override suspend fun deleteByPackage(packageId: String) {
        synchronized(rows) {
            rows.values.filter { it.packageId == packageId }
                .map { it.sourceId }
                .forEach { rows.remove(it) }
        }
    }
}
