package com.superstudent.core.repository

import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.PackageDao
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.SourceDao
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.Ids
import com.superstudent.core.model.LearningGoal
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.SourceFormat
import com.superstudent.core.model.SourceRefJson
import com.superstudent.core.model.UploadState
import com.superstudent.core.security.Hashing
import com.superstudent.core.upload.ProcessLeaseRegistry
import com.superstudent.core.upload.SOURCE_MAX_ATTEMPTS
import com.superstudent.core.upload.SOURCE_NO_PROGRESS_MILLIS
import com.superstudent.core.upload.SourceCancelledException
import com.superstudent.core.upload.SourceDeleteEvent
import com.superstudent.core.upload.SourceErrorCode
import com.superstudent.core.upload.SourceFailure
import com.superstudent.core.upload.SourceFailureClassifier
import com.superstudent.core.upload.SourceHashMismatchException
import com.superstudent.core.upload.SourceLeaseEvent
import com.superstudent.core.upload.SourceLeaseReason
import com.superstudent.core.upload.SourceNoCredentialException
import com.superstudent.core.upload.SourceRecoveryLog
import com.superstudent.core.upload.SourceRowPresenter
import com.superstudent.core.upload.SourceUnsupportedFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class PackageNotFoundException(val packageId: String) : Exception("学习包不存在: $packageId")

/** Outcome of one upload attempt. The Room row already reflects it when this returns. */
sealed interface UploadOutcome {
    /** The object is on Drive and `package.json` / `index.json` have been re-published. */
    data object Uploaded : UploadOutcome

    /** Another attempt owns this source (or it is already uploaded); the caller must do nothing. */
    data object Skipped : UploadOutcome

    /**
     * The row still owes an upload, but not to this caller and not right now: it is not yet due, or
     * its lease is alive somewhere else. Distinct from [Skipped] because a worker that reported
     * `success()` here would drop the retry chain and the source would never upload (design §3.5).
     */
    data object Deferred : UploadOutcome

    data class Failed(val failure: SourceFailure) : UploadOutcome

    /** The local file can no longer be read; the row offers "重新选择文件". */
    data class LocalOnly(val failure: SourceFailure) : UploadOutcome
}

class PackageRepository(
    private val drive: DriveRepository,
    private val packageDao: PackageDao,
    private val sourceDao: SourceDao,
    /** Every `package.json` / `index.json` write goes through here; this class owns none of them. */
    private val manifestWriter: ManifestWriter,
    /** Same condition the network layer's auth interceptor uses, so the two can never disagree. */
    private val credentialAvailable: () -> Boolean,
    /**
     * OS-level lease ownership (design §3.1). Injected rather than created here so the fencing tests
     * can drive two registries — two owners — on one JVM without an Android context.
     */
    private val leases: ProcessLeaseRegistry,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Seam for JVM tests: `android.util.Log` is not stubbed in unit tests. */
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
) {

    /** This process's lease owner id, written into `lease_owner_id` on every claim. */
    val ownerId: String get() = leases.ownerId

    fun observePackages(identityId: String): Flow<List<LearningPackageEntity>> =
        packageDao.observeByIdentity(identityId)

    fun observePackage(identityId: String, packageId: String): Flow<LearningPackageEntity?> =
        packageDao.observe(identityId, packageId)

    /**
     * Deliberately unfiltered: a tombstoned row is still shown, as 待清理 with a 重试删除 entry. The
     * filtered read is [listSources], which feeds the manifest and the task runner.
     */
    fun observeSources(packageId: String): Flow<List<SourceAssetEntity>> =
        sourceDao.observeByPackage(packageId)

    suspend fun listPackages(identityId: String): List<LearningPackageEntity> =
        withContext(Dispatchers.IO) { packageDao.listByIdentity(identityId) }

    suspend fun getPackage(identityId: String, packageId: String): LearningPackageEntity =
        withContext(Dispatchers.IO) {
            packageDao.find(identityId, packageId) ?: throw PackageNotFoundException(packageId)
        }

    suspend fun findSource(sourceId: String): SourceAssetEntity? = withContext(Dispatchers.IO) {
        sourceDao.find(sourceId)
    }

    /**
     * The business read: what this package's sources *are*. A tombstoned row is excluded, so neither
     * the manifest projection nor a task run can see a source the student already deleted.
     */
    suspend fun listSources(packageId: String): List<SourceAssetEntity> = withContext(Dispatchers.IO) {
        sourceDao.listByPackage(packageId).filterNot { it.deletePending }
    }

    suspend fun listResumable(nowMillis: Long = clock()): List<SourceAssetEntity> =
        withContext(Dispatchers.IO) { sourceDao.listResumable(nowMillis, MAX_ATTEMPTS) }

    /** Everything the recovery coordinator owes an action on, tombstones included (design §3.4). */
    suspend fun listRecoveryCandidates(nowMillis: Long = clock()): List<SourceAssetEntity> =
        withContext(Dispatchers.IO) {
            sourceDao.listRecoveryCandidates(nowMillis, nowMillis - NO_PROGRESS_MILLIS, MAX_ATTEMPTS)
        }

    /**
     * The earliest *future* moment a row becomes actionable, or null when nothing is pending later than
     * now. Rows due now are excluded by the statement, not by the caller: this pass already acts on
     * them, and a past deadline would arm a follow-up that fires immediately and re-arms itself.
     */
    suspend fun nextRecoveryDeadline(nowMillis: Long = clock()): Long? =
        withContext(Dispatchers.IO) { sourceDao.nextRecoveryDeadline(MAX_ATTEMPTS, nowMillis) }

    /**
     * Failed rows whose auto-retry budget is spent on a code that was believed transient. The startup
     * reconciliation re-probes each one's local handle: a file that can no longer be opened is
     * normalized to `LOCAL_ONLY` so the student gets "重新选择文件" back, while a still-readable file
     * keeps its network failure and is left alone (ZLQ-110 §3.4).
     */
    suspend fun listExhaustedTransientFailures(): List<SourceAssetEntity> =
        withContext(Dispatchers.IO) { sourceDao.listExhaustedTransientFailures() }

    /** Creates the package locally (LOCAL_ONLY) and publishes package.json + index.json. */
    suspend fun createPackage(
        identityId: String,
        title: String,
        goal: LearningGoal,
        chapterRange: String?,
        packageId: String = Ids.pkg(),
    ): LearningPackageEntity = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().take(80)
        require(cleanTitle.isNotEmpty()) { "标题不能为空" }
        val cleanRange = chapterRange?.trim()?.take(40)?.ifEmpty { null }
        val now = Instant.now().toString()

        val entity = LearningPackageEntity(
            packageId = packageId,
            identityId = identityId,
            title = cleanTitle,
            goal = goal.name,
            chapterRange = cleanRange,
            status = PackageStatus.LOCAL_ONLY.name,
            latestTaskId = null,
            sourceCount = 0,
            createdAt = now,
            updatedAt = now,
        )
        manifestWriter.create(identityId, entity)
        entity
    }

    // ---- source lifecycle (design increment §1–§3) ----

    /**
     * Creates the Room row for a picked file. Called from the picker callback, before any read, so
     * the row exists even if the process dies immediately afterwards. `drive_path` stays null until
     * the content has been hashed — there is no placeholder and the display name is never used.
     */
    suspend fun createSource(
        identityId: String,
        packageId: String,
        displayName: String,
        mimeType: String?,
        kind: String,
        sizeBytes: Long,
        localUri: String?,
        localAccessMode: LocalAccessMode,
    ): SourceAssetEntity = withContext(Dispatchers.IO) {
        packageDao.find(identityId, packageId) ?: throw PackageNotFoundException(packageId)
        val now = Instant.now().toString()
        val entity = SourceAssetEntity(
            sourceId = Ids.src(),
            packageId = packageId,
            drivePath = null,
            displayName = displayName,
            mimeType = mimeType,
            kind = kind,
            sizeBytes = sizeBytes,
            sha256 = null,
            uploadState = UploadState.PENDING.name,
            localUri = localUri,
            addedAt = now,
            localAccessMode = localAccessMode.name,
            canonicalType = CanonicalType.UNKNOWN.name,
            updatedAt = now,
        )
        sourceDao.insert(entity)
        entity
    }

    /**
     * One upload attempt for an existing row. Claims it with a CAS update, reads and hashes the
     * content, resolves the canonical type, PUTs to the frozen Drive path and re-publishes the
     * manifest. Every exit path has already written its outcome to Room — the notification is
     * always the second step, never the only record of a failure.
     *
     * A retry reuses the same `sourceId`, `sha256` and `drivePath` and re-requests a fresh presigned
     * URL, so it overwrites one object instead of creating a second one.
     */
    suspend fun uploadSource(
        identityId: String,
        sourceId: String,
        readBytes: suspend (SourceAssetEntity) -> ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val row = sourceDao.find(sourceId) ?: return@withContext UploadOutcome.Skipped
        if (row.deletePending) return@withContext UploadOutcome.Skipped
        if (row.uploadState == UploadState.UPLOADED.name) return@withContext UploadOutcome.Uploaded
        // Cross-identity guard: the package must belong to the identity whose Drive we are writing.
        if (packageDao.find(identityId, row.packageId) == null) return@withContext UploadOutcome.Skipped
        if (row.localUri.isNullOrBlank() && row.localAccessMode == LocalAccessMode.NONE.name) {
            markLocalOnly(
                sourceId,
                SourceErrorCode.URI_PERMISSION_REQUIRED,
                URI_PERMISSION_COPY,
            )
            return@withContext UploadOutcome.LocalOnly(
                SourceFailure(
                    SourceErrorCode.URI_PERMISSION_REQUIRED,
                    URI_PERMISSION_COPY,
                    retryable = false,
                )
            )
        }

        // The per-source execution lock (design §3.2). Held across the whole attempt, including the
        // manifest commit, so that neither a second worker in this process nor a recovery pass can be
        // PUTting the same object while this one is. Not held → somebody is already working the row,
        // which is a deferral, not a success: reporting Skipped here would let the worker finish and
        // drop the retry chain.
        val executionLock = leases.tryAcquireSource(sourceId)
            ?: return@withContext UploadOutcome.Deferred
        val token = Ids.uuidV7()
        try {
            runAttempt(identityId, row, token, readBytes, onProgress)
        } catch (e: SourceLeaseLostException) {
            // The recovery side asked us to stand down and our next renewLease proved it. The row
            // belongs to that pass now; writing anything to it here is how two owners end up
            // disagreeing about one source.
            UploadOutcome.Deferred
        } finally {
            leases.unregisterAttempt(sourceId, token)
            executionLock.close()
            log(SourceRecoveryLog.lease(sourceId, SourceLeaseEvent.RELEASE, ownerId, token, SourceLeaseReason.ATTEMPT_END))
        }
    }

    private suspend fun runAttempt(
        identityId: String,
        row: SourceAssetEntity,
        token: String,
        readBytes: suspend (SourceAssetEntity) -> ByteArray,
        onProgress: ((Long, Long) -> Unit)?,
    ): UploadOutcome = coroutineScope {
        val sourceId = row.sourceId
        val startedAt = clock()
        val claimed = sourceDao.claimForUpload(
            sourceId = sourceId,
            token = token,
            ownerId = ownerId,
            leaseUntilMillis = startedAt + LEASE_MILLIS,
            heartbeatAt = startedAt,
            uploadStartedAt = startedAt,
            lastProgressAt = startedAt,
            nowMillis = startedAt,
            maxAttempts = MAX_ATTEMPTS,
            now = Instant.now().toString(),
        )
        if (claimed != 1) {
            // 0 rows means a guard refused: not due yet, budget spent, tombstoned, interrupted, or
            // still owned by an UPLOADING row. Only the first three are terminal for this caller.
            val current = sourceDao.find(sourceId)
            return@coroutineScope if (current == null || current.deletePending ||
                current.uploadState == UploadState.UPLOADED.name
            ) {
                UploadOutcome.Skipped
            } else {
                UploadOutcome.Deferred
            }
        }
        leases.registerAttempt(sourceId, token)
        log(SourceRecoveryLog.lease(sourceId, SourceLeaseEvent.CLAIM, ownerId, token, SourceLeaseReason.ATTEMPT_START))
        val attemptCount = (row.attemptCount + 1).coerceAtLeast(1)
        // Captured at scope level: inside the `launch` below, `coroutineContext[Job]` would be the
        // heartbeat's own job, and cancelling that stops the renewal without stopping the PUT.
        val attemptJob = coroutineContext[Job]

        // The PUT's progress callback is not a suspend function and runs on OkHttp's own thread, so it
        // only stamps an atomic; the heartbeat coroutine is what turns that stamp into a row write.
        // Flushed on a 15 s / 1 MiB throttle: a byte-level write would turn one PUT into thousands of
        // UPDATEs, and the no-progress rule only needs resolution well under its 10 min timeout.
        val progressAt = AtomicLong(startedAt)
        val flushGateAt = AtomicLong(startedAt)
        val flushGateBytes = AtomicLong(-1L)
        val throttled: ((Long, Long) -> Unit)? = onProgress?.let { outer ->
            { sent, total ->
                val at = clock()
                if (shouldFlushProgress(flushGateAt.get(), flushGateBytes.get(), at, sent)) {
                    flushGateAt.set(at)
                    flushGateBytes.set(sent)
                    progressAt.set(at)
                }
                outer(sent, total)
            }
        }

        // Renewal, not a timer for its own sake: both writes are guarded on token + owner +
        // `interrupt_requested_at IS NULL`, so a 0-row result is the *only* channel through which a
        // recovery pass can tell a live attempt to stop (design §3.2 condition 4). A tick that has new
        // progress carries it, so `last_progress_at` advances only when bytes actually moved.
        val heartbeat = launch {
            var flushedProgressAt = startedAt
            while (true) {
                delay(HEARTBEAT_MILLIS)
                val at = clock()
                val stamped = progressAt.get()
                val carriedProgress = stamped > flushedProgressAt
                val renewed = if (carriedProgress) {
                    flushedProgressAt = stamped
                    sourceDao.recordProgress(
                        sourceId = sourceId,
                        token = token,
                        ownerId = ownerId,
                        progressAt = stamped,
                        heartbeatAt = at,
                        leaseUntilMillis = at + LEASE_MILLIS,
                        now = Instant.now().toString(),
                    )
                } else {
                    sourceDao.renewLease(
                        sourceId = sourceId,
                        token = token,
                        ownerId = ownerId,
                        heartbeatAt = at,
                        leaseUntilMillis = at + LEASE_MILLIS,
                        now = Instant.now().toString(),
                    )
                }
                if (renewed != 1) {
                    // A guarded renew returning 0 rows is the only channel through which a recovery
                    // pass can tell a live attempt to stand down (design §3.2 condition 4).
                    log(
                        SourceRecoveryLog.lease(
                            sourceId, SourceLeaseEvent.INTERRUPT, ownerId, token,
                            SourceLeaseReason.LEASE_LOST,
                        )
                    )
                    attemptJob?.cancel(SourceLeaseLostException())
                    return@launch
                }
                log(
                    SourceRecoveryLog.lease(
                        sourceId, SourceLeaseEvent.HEARTBEAT, ownerId, token,
                        if (carriedProgress) SourceLeaseReason.PROGRESS else SourceLeaseReason.RENEWED,
                    )
                )
            }
        }

        var drivePath: String? = null
        var sha256: String? = null
        var sizeBytes: Long? = null
        var canonicalType: CanonicalType? = null
        try {
            // Checked before any bytes are read: an attempt without a credential cannot reach Drive,
            // and letting it try kills the process (see SourceNoCredentialException).
            if (!credentialAvailable()) throw SourceNoCredentialException()
            val bytes = readBytes(row)
            sizeBytes = bytes.size.toLong()
            sha256 = Hashing.sha256Hex(bytes)
            if (!row.sha256.isNullOrBlank() && row.sha256 != sha256) throw SourceHashMismatchException()
            // §3.3: a stage switch refreshes `last_progress_at`, not only the PUT's byte callback.
            // Reading and hashing 50 MiB is silent work — without this stamp a slow device would sit
            // at the claim's timestamp and the 10-minute no-progress rule would read it as a wedged
            // attempt. The stamp after `uploadBytes` is the same boundary on the other side: it says
            // the object landed, so a manifest publish that then stalls is honestly progress-less.
            // Only a stamp the heartbeat carries into `recordProgress` reaches the row.
            progressAt.set(clock())

            val format = SourceFormat.resolve(bytes, row.mimeType, row.displayName)
            if (format.conflicting || !format.generatable) {
                throw SourceUnsupportedFormatException(format.conflicting)
            }
            canonicalType = format.type

            val path = DrivePath.sourceObject(row.packageId, sourceId, sha256, canonicalType)
            drive.uploadBytes(identityId, path, canonicalType.mimeType, bytes, throttled)
            drivePath = path
            progressAt.set(clock())

            // Commit protocol (ZLQ-110 §2.1): the manifest is published inside the package lock with
            // this attempt as its explicit candidate, and `markUploaded` is the last step of that same
            // locked section. Publishing before the row was UPLOADED is what made a source invisible
            // to its own manifest; publishing outside the lock would let the next source drop it.
            val committed = manifestWriter.commitUploadedSource(
                identityId = identityId,
                packageId = row.packageId,
                candidate = ManifestCandidate(
                    ref = SourceRefJson(
                        sourceId = sourceId,
                        displayName = row.displayName,
                        drivePath = path,
                        mimeType = row.mimeType ?: canonicalType.mimeType,
                        sizeBytes = sizeBytes,
                        sha256 = sha256,
                        kind = row.kind,
                        addedAt = row.addedAt,
                    ),
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    canonicalType = canonicalType.name,
                ),
                attemptToken = token,
                attemptOwner = ownerId,
            )
            if (!committed) return@coroutineScope UploadOutcome.Skipped
            // A row migrated from v1 may still point at a path built from the old naming rule, out of
            // the display name and therefore outside the whitelist. Both objects live under
            // sources/{sourceId}/, so dropping the stale one keeps exactly one — and the prefix test
            // must not validate it, or the upload that just succeeded would be recorded as failed.
            val stale = row.drivePath
            if (!stale.isNullOrBlank() && stale != path &&
                DrivePath.isUnderAllowingLegacy(stale, DrivePath.sourceDir(row.packageId, sourceId))
            ) {
                runCatching { drive.delete(identityId, stale) }
            }
            UploadOutcome.Uploaded
        } catch (e: SourceLeaseLostException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The user cancelled, or WorkManager gave up: record it in a non-cancellable context so
            // the row is never left stuck in UPLOADING with a live lease.
            withContext(NonCancellable) {
                fail(sourceId, token, SourceFailureClassifier.classify(SourceCancelledException()), attemptCount)
            }
            throw e
        } catch (t: Throwable) {
            val failure = SourceFailureClassifier.classify(t)
            // A hash mismatch means the bytes we just read are *not* this source, so they must not
            // overwrite the recorded sha256/size — otherwise the next re-pick compares against the
            // wrong fingerprint and AC-E can never recover.
            val mismatch = failure.code == SourceErrorCode.HASH_MISMATCH
            val wrote = fail(
                sourceId = sourceId,
                token = token,
                failure = failure,
                attemptCount = attemptCount,
                drivePath = drivePath,
                sha256 = sha256.takeUnless { mismatch },
                sizeBytes = sizeBytes.takeUnless { mismatch },
                canonicalType = canonicalType.takeUnless { mismatch },
            )
            when {
                failure.code == SourceErrorCode.URI_PERMISSION_REQUIRED ||
                    failure.code == SourceErrorCode.READ_FAILED ||
                    failure.code == SourceErrorCode.HASH_MISMATCH -> {
                    // AC-E: the row stays visible with "重新选择文件" instead of retrying a file the
                    // app can no longer read, or content that is not the source the row describes.
                    if (wrote) markLocalOnly(sourceId, failure.code, failure.message)
                    UploadOutcome.LocalOnly(failure)
                }
                else -> UploadOutcome.Failed(failure)
            }
        } finally {
            heartbeat.cancel()
        }
    }

    /**
     * A renewal found the row interrupted, tombstoned or handed over, so this attempt must stop
     * reading and leave the row alone. A [kotlinx.coroutines.CancellationException] because it has to
     * unwind an in-flight PUT, and a distinct type because it must not be recorded as a user cancel.
     */
    class SourceLeaseLostException : kotlinx.coroutines.CancellationException("source lease lost")

    private suspend fun fail(
        sourceId: String,
        token: String,
        failure: SourceFailure,
        attemptCount: Int,
        drivePath: String? = null,
        sha256: String? = null,
        sizeBytes: Long? = null,
        canonicalType: CanonicalType? = null,
    ): Boolean {
        val nowMillis = clock()
        val exhausted = attemptCount >= MAX_ATTEMPTS
        val retryable = failure.retryable && !exhausted
        // The persisted text is the matrix copy for the state this write produces, computed by the
        // same mapper the row renders from, so what is stored and what the student reads cannot
        // drift. R5②: the exhausted variants carry no 自动/正在 wording, which the old
        // "（已自动重试 5 次）" suffix did — it promised a scheduler that had already stopped.
        val prospective = (sourceDao.find(sourceId) ?: return false).copy(
            uploadState = UploadState.FAILED.name,
            errorCode = failure.code,
            retryable = retryable,
            errorMessage = failure.message,
        )
        val message = SourceRowPresenter.of(prospective, nowMillis).stateText
        return sourceDao.recordFailure(
            sourceId = sourceId,
            token = token,
            ownerId = ownerId,
            state = UploadState.FAILED.name,
            errorCode = failure.code,
            errorMessage = message,
            retryable = retryable,
            nextRetryAtMillis = nextRetryAt(failure, attemptCount, nowMillis, retryable),
            drivePath = drivePath,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            canonicalType = canonicalType?.name,
            now = Instant.now().toString(),
        ) == 1
    }

    private fun nextRetryAt(
        failure: SourceFailure,
        attemptCount: Int,
        nowMillis: Long,
        retryable: Boolean,
    ): Long? {
        if (!retryable) return null
        val base = failure.retryAfterSeconds?.times(1000)?.coerceAtLeast(BASE_BACKOFF_MILLIS)
            ?: BASE_BACKOFF_MILLIS
        val backoff = (base shl (attemptCount - 1).coerceIn(0, 8)).coerceAtMost(MAX_BACKOFF_MILLIS)
        return nowMillis + backoff
    }

    /** Manual "重试上传": a FAILED row goes back to PENDING for immediate execution. */
    suspend fun requeueForRetry(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        sourceDao.requeueForRetry(sourceId, Instant.now().toString()) == 1
    }

    /**
     * Re-selected file: same row, same `sourceId`, new local handle, fresh retry cycle.
     *
     * `sizeBytes` goes in through the same guarded UPDATE as everything else. The previous shape read
     * the row, copied it and wrote it back with a whole-row `upsert`, which overwrote whatever a
     * concurrent attempt had committed in between — including its token and owner (design §5.4).
     */
    suspend fun bindLocalFile(
        sourceId: String,
        localUri: String?,
        localAccessMode: LocalAccessMode,
        sizeBytes: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        sourceDao.bindLocalFile(
            sourceId = sourceId,
            localUri = localUri,
            localAccessMode = localAccessMode.name,
            sizeBytes = sizeBytes,
            state = UploadState.PENDING.name,
            errorCode = null,
            errorMessage = null,
            retryable = true,
            now = Instant.now().toString(),
        ) == 1
    }

    /**
     * The picked file can no longer be read. The row stays in the list, visible, with an explicit
     * "重新选择文件" action — never a silent drop.
     */
    suspend fun markLocalOnly(sourceId: String, errorCode: String, message: String) =
        withContext(Dispatchers.IO) {
            sourceDao.markLocalOnly(
                sourceId = sourceId,
                localAccessMode = LocalAccessMode.NONE.name,
                errorCode = errorCode,
                errorMessage = message,
                now = Instant.now().toString(),
            )
        }

    // ---- durable delete (design §3.9) ----

    /**
     * Arms the tombstone. This is the *first* write a delete performs, before any network call, and
     * from this moment the row is out of every manifest projection and out of every business read.
     *
     * It also clears `attempt_token`, which is what fences a PUT already in the air: the in-flight
     * attempt's `markUploaded` guard matches on the token, so it now returns 0 rows and the object it
     * just landed is never published. Without the tombstone that attempt would win the race and the
     * deleted source would reappear in `package.json`.
     *
     * Returns false only when the row is already tombstoned or gone — both mean "the delete is
     * already armed", so the caller still enqueues the worker.
     */
    suspend fun beginDelete(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        val armed = sourceDao.beginDelete(sourceId, clock(), Instant.now().toString()) == 1
        if (armed) log(SourceRecoveryLog.delete(sourceId, SourceDeleteEvent.MARK))
        armed
    }

    /**
     * Finishes an armed delete: remote subtree cleanup, manifest re-publish, then the row.
     *
     * Ordered that way because the reverse leaves a window where the object is gone but the manifest
     * still advertises it. Runs under the source execution lock, so it cannot interleave with a PUT
     * that started before the tombstone was armed.
     *
     * A failure keeps the tombstone and returns [DeleteOutcome.Pending] — never a fake success. The
     * row stays visible as 待清理 with a 重试删除 entry until a later pass confirms the remote side.
     */
    suspend fun completeDelete(
        identityId: String,
        packageId: String,
        sourceId: String,
    ): DeleteOutcome = withContext(Dispatchers.IO) {
        val row = sourceDao.find(sourceId) ?: return@withContext DeleteOutcome.Deleted
        if (!row.deletePending) return@withContext deleteRetry(sourceId, DELETE_NOT_ARMED, "NOT_ARMED")
        val lock = leases.tryAcquireSource(sourceId)
            ?: return@withContext deleteRetry(sourceId, DELETE_BUSY, "BUSY")
        try {
            val dir = DrivePath.sourceDir(packageId, sourceId)
            val error = runCatching { drive.deleteSubtree(identityId, dir) }.exceptionOrNull()
            log(SourceRecoveryLog.delete(sourceId, SourceDeleteEvent.REMOTE_DELETE, if (error == null) "OK" else "FAILED"))
            // A delete that reports failure can still have landed, and a retry then finds nothing to
            // remove. Existence — not the status code — is what "the original is gone" means. The
            // reason is a fixed string: a Drive exception message can carry a signed URL.
            val stillThere = error != null &&
                runCatching { drive.exists(identityId, dir) }.getOrDefault(true)
            if (stillThere) return@withContext deleteRetry(sourceId, DELETE_REMOTE_FAILED, "REMOTE_STILL_THERE")
            manifestWriter.publishState(
                identityId = identityId,
                packageId = packageId,
                status = PackageStatus.READY,
                statusWhenEmpty = PackageStatus.LOCAL_ONLY,
            )
            log(SourceRecoveryLog.delete(sourceId, SourceDeleteEvent.MANIFEST))
            sourceDao.finishDelete(sourceId)
            log(SourceRecoveryLog.delete(sourceId, SourceDeleteEvent.DONE))
            DeleteOutcome.Deleted
        } finally {
            lock.close()
        }
    }

    /**
     * A delete pass that stopped short of [DeleteOutcome.Deleted]. The tombstone stays armed, so the
     * row keeps 待清理 and its 重试删除 entry until a later pass confirms the remote side.
     */
    private fun deleteRetry(sourceId: String, copy: String, wire: String): DeleteOutcome.Pending {
        log(SourceRecoveryLog.delete(sourceId, SourceDeleteEvent.RETRY, wire))
        return DeleteOutcome.Pending(copy)
    }

    /**
     * Releases an orphaned `UPLOADING` row (design §3.2).
     *
     * The caller must be the recovery coordinator: it is the only component allowed to probe owner
     * liveness, and it must hold [ProcessLeaseRegistry.tryAcquireSource] across this call. Returns
     * false when the row was not an orphan — already finished, tombstoned, or still owned.
     */
    suspend fun interruptOrphan(sourceId: String, nowMillis: Long = clock()): Boolean =
        withContext(Dispatchers.IO) {
            sourceDao.interruptAttempt(
                sourceId = sourceId,
                nowMillis = nowMillis,
                maxAttempts = MAX_ATTEMPTS,
                now = Instant.now().toString(),
            ) == 1
        }

    /**
     * The 「不得回收」 half of design §3.2: the owner is provably alive, so all a recovery pass may do
     * is ask it to stand down. The row keeps its token and its `UPLOADING` state, which is why no
     * second PUT can start; the owner's next `renewLease` returns 0 and it stops by itself.
     */
    suspend fun requestInterrupt(sourceId: String, nowMillis: Long = clock()): Boolean =
        withContext(Dispatchers.IO) {
            sourceDao.requestInterrupt(sourceId, nowMillis, Instant.now().toString()) == 1
        }

    companion object {
        /** Auto-retry budget per source; a manual retry starts from the row's own state. */
        const val MAX_ATTEMPTS = SOURCE_MAX_ATTEMPTS
        private const val BASE_BACKOFF_MILLIS = 10_000L
        private const val MAX_BACKOFF_MILLIS = 30 * 60_000L

        /**
         * Lease and heartbeat (design §3.3). The previous 15 minute lease *was* the ZLQ-130 defect: a
         * process killed mid-upload left its row `UPLOADING` for a quarter of an hour with nothing
         * able to say whether the owner was slow or gone, and the recovery scan had to guess. Two
         * minutes with a 30 s heartbeat makes an expired lease about four missed heartbeats, which is
         * a signal rather than a coincidence.
         */
        const val LEASE_MILLIS = 120_000L
        const val HEARTBEAT_MILLIS = 30_000L
        const val PROGRESS_FLUSH_MILLIS = 15_000L
        const val PROGRESS_FLUSH_BYTES = 1L * 1024 * 1024
        const val NO_PROGRESS_MILLIS = SOURCE_NO_PROGRESS_MILLIS

        /**
         * §3.3 `PROGRESS_FLUSH_INTERVAL`: 15 s or 1 MiB, whichever comes first.
         *
         * Lifted out of the PUT callback as a pure predicate so its cadence can be pinned without
         * constructing a repository — which a JVM test cannot do, because `DriveRepository` and
         * `PresignedTransfer` are final over a live HTTP stack. The atomics stay at the call site:
         * this decides, it does not remember.
         *
         * The caller seeds `gateBytes` at -1 and `gateAt` at the claim's own timestamp, so the first
         * flush comes from whichever arm trips first — 15 s of transfer or the first full MiB — and
         * not from the first callback. That is safe because the claim already wrote a
         * `last_progress_at`, so the no-progress rule has a baseline to measure from.
         *
         * The time arm also requires that a byte moved. §9's risk table forbids passing off "the
         * coroutine is still alive" as byte progress, and a gate that opened on elapsed time alone
         * would let a callback loop over a wedged socket stamp `last_progress_at` forever — quietly
         * disabling the 10-minute no-progress rule that §3.2 condition 4 depends on.
         */
        fun shouldFlushProgress(gateAt: Long, gateBytes: Long, at: Long, sent: Long): Boolean =
            (at - gateAt >= PROGRESS_FLUSH_MILLIS && sent > gateBytes) ||
                sent - gateBytes >= PROGRESS_FLUSH_BYTES

        const val URI_PERMISSION_COPY = "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件"

        private const val DELETE_NOT_ARMED = "删除尚未开始，请重试"
        private const val DELETE_BUSY = "该资料正在处理中，请稍后重试删除"
        private const val DELETE_REMOTE_FAILED = "云端原件删除失败，请稍后重试"

        private const val TAG = "SsUpload"
    }
}

/** Result of finishing an armed delete. [Pending] keeps the tombstone and the 重试删除 entry. */
sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome
    data class Pending(val reason: String) : DeleteOutcome
}
