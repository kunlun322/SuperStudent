package com.superstudent.core.repository

import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.PackageDao
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.SourceDao
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.ManifestStore
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.IndexJson
import com.superstudent.core.model.IndexPackage
import com.superstudent.core.model.LearningGoal
import com.superstudent.core.model.PackageJson
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.SourceRefJson
import com.superstudent.core.model.UploadState
import com.superstudent.core.upload.ManifestPublishException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The one source an upload attempt may add to a manifest before its own row says `UPLOADED`
 * (ZLQ-110 §2.1). It is built from the values this attempt already computed — `sourceId`, `drivePath`,
 * `displayName`, `mimeType`, `canonicalType`, `sha256` — and it exists only while the attempt token
 * still owns the row.
 */
data class ManifestCandidate(
    val ref: SourceRefJson,
    val sha256: String,
    val sizeBytes: Long,
    val canonicalType: String,
)

/** Whether a submission actually moved either manifest, so a no-op reconciliation stays silent. */
data class ManifestResult(
    val packageChanged: Boolean,
    val indexChanged: Boolean,
    val revision: Long,
)

/**
 * The single write path for `package.json` and `index.json` (ZLQ-110 §2.3).
 *
 * Every publication — upload commit, generation start/finish, delete, repair reconciliation, package
 * creation — goes through [withPackageLock] and recomputes from the freshest Room rows inside it. No
 * caller hands in a snapshot taken before the lock: that is how a package was being reverted from
 * `GENERATING` to `READY`, and how a source that had just committed was dropped by the next writer.
 *
 * The two cloud files are not written transactionally and the API offers no conditional PUT, so the
 * guarantees here are the ones the design actually promises: in-process serialization, latest-read
 * merge, and a readback check. Cross-device last-write-wins is *not* solved (see §2.4).
 */
class ManifestWriter(
    private val drive: ManifestStore,
    private val packageDao: PackageDao,
    private val sourceDao: SourceDao,
    private val indexStore: IndexStore,
) {

    /** Per-package serial submission, so two sources finishing together commit one after the other. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withPackageLock(
        identityId: String,
        packageId: String,
        block: suspend () -> T,
    ): T = locks.computeIfAbsent("$identityId/$packageId") { Mutex() }.withLock { block() }

    /** The published index, for the reconciliation that compares Room against it (§2.5). */
    suspend fun readIndex(identityId: String): IndexJson = indexStore.read(identityId)

    /**
     * The source IDs a publication of [packageId] would write right now, i.e. the inclusion rule of
     * §2.2 point 2 with no candidate. The startup reconciliation compares `index.json` against this
     * rather than against its own copy of the filter: two filters that drift apart would make the
     * reconciliation either republish on every cold start or never repair anything.
     */
    suspend fun publishedSourceIds(packageId: String): Set<String> =
        projection(packageId, null).map { it.sourceId }.toSet()

    /**
     * The upload commit protocol (ZLQ-110 §2.1): verify the attempt still owns the row, publish the
     * projection including this candidate, then — and only then — mark the row `UPLOADED`.
     *
     * `markUploaded` stays last, because setting it earlier would let Room claim success while the
     * restore truth is still missing (§2.2). It stays *inside* the lock for the opposite reason:
     * releasing the lock first would let the next source recompute a projection that no longer
     * contains this one, reproducing "cannot see itself when publishing" one source later.
     *
     * @return false when the attempt no longer owns the row, or the token-guarded write did not land.
     *   The caller then reports `Skipped` and must not touch the row again. Ownership is
     *   `(attemptToken, attemptOwner)` — a reclaimed attempt gets a fresh token *and* a fresh owner
     *   id, so a coroutine from a process that died mid-PUT cannot land a late commit even if its
     *   token somehow survived (ZLQ-132 §3.2).
     */
    suspend fun commitUploadedSource(
        identityId: String,
        packageId: String,
        candidate: ManifestCandidate,
        attemptToken: String,
        attemptOwner: String,
    ): Boolean = withPackageLock(identityId, packageId) {
        // Re-checked inside the lock: a coroutine whose lease was taken over has no right to publish.
        if (sourceDao.countOwnedAttempt(candidate.ref.sourceId, attemptToken, attemptOwner) != 1) {
            return@withPackageLock false
        }
        val entity = packageDao.find(identityId, packageId) ?: throw PackageNotFoundException(packageId)
        // `status`/`latestTaskId` null: an upload only replaces `sources`. The package's own business
        // state is taken from the row read just now, so a task that moved it to GENERATING while this
        // PUT was in flight is never written back to READY.
        submit(
            identityId = identityId,
            entity = entity,
            sources = projection(packageId, candidate.ref),
            status = null,
            latestTaskId = null,
            resultKinds = null,
            statusWhenEmpty = null,
        )
        sourceDao.markUploaded(
            sourceId = candidate.ref.sourceId,
            token = attemptToken,
            ownerId = attemptOwner,
            drivePath = candidate.ref.drivePath,
            sha256 = candidate.sha256,
            sizeBytes = candidate.sizeBytes,
            canonicalType = candidate.canonicalType,
            now = Instant.now().toString(),
        ) == 1
    }

    /**
     * Publishes business state — generation start/finish, delete, repair reconciliation. Both
     * manifests are recomputed from Room inside the lock.
     *
     * A `status` of null keeps the package's current Room status and a `latestTaskId` of null keeps
     * the current one, which is what the reconciliation wants: it repairs `sources` and nothing else.
     */
    suspend fun publishState(
        identityId: String,
        packageId: String,
        status: PackageStatus? = null,
        latestTaskId: String? = null,
        resultKinds: List<ResultKind>? = null,
        statusWhenEmpty: PackageStatus? = null,
    ): ManifestResult = withPackageLock(identityId, packageId) {
        val entity = packageDao.find(identityId, packageId) ?: throw PackageNotFoundException(packageId)
        submit(
            identityId = identityId,
            entity = entity,
            sources = projection(packageId, null),
            status = status,
            latestTaskId = latestTaskId,
            resultKinds = resultKinds,
            statusWhenEmpty = statusWhenEmpty,
        )
    }

    /**
     * A brand-new package, under the same lock as every other write. Keeps `createPackage`'s original
     * order — remote `package.json`, then Room, then the index — so a half-created package is still
     * visible locally rather than vanishing.
     *
     * No readback check here on purpose: there is no prior manifest for the index to lag behind, and
     * failing package creation on an index hiccup would be a worse outcome than the one §2.4 guards.
     */
    suspend fun create(identityId: String, entity: LearningPackageEntity) {
        val status = statusOf(entity)
        val json = PackageJson(
            packageId = entity.packageId,
            identityId = identityId,
            title = entity.title,
            goal = LearningGoal.valueOf(entity.goal),
            chapterRange = entity.chapterRange,
            status = status,
            sources = emptyList(),
            latestTaskId = null,
            createdAt = entity.createdAt,
            updatedAt = entity.createdAt,
        )
        withPackageLock(identityId, entity.packageId) {
            drive.writeJson(
                identityId,
                DrivePath.packageJson(entity.packageId),
                json,
                PackageJson.serializer(),
            )
            packageDao.upsert(entity)
            indexStore.update(identityId) { idx ->
                mergeEntry(idx, entity, emptyList(), status, null, null, entity.createdAt)
            }
        }
    }

    private suspend fun submit(
        identityId: String,
        entity: LearningPackageEntity,
        sources: List<SourceRefJson>,
        status: PackageStatus?,
        latestTaskId: String?,
        resultKinds: List<ResultKind>?,
        statusWhenEmpty: PackageStatus?,
    ): ManifestResult = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        val effectiveStatus = when {
            statusWhenEmpty != null && sources.isEmpty() -> statusWhenEmpty
            status != null -> status
            else -> statusOf(entity)
        }
        val effectiveTaskId = latestTaskId ?: entity.latestTaskId
        val path = DrivePath.packageJson(entity.packageId)
        val json = PackageJson(
            packageId = entity.packageId,
            identityId = identityId,
            title = entity.title,
            goal = LearningGoal.valueOf(entity.goal),
            chapterRange = entity.chapterRange,
            status = effectiveStatus,
            sources = sources,
            latestTaskId = effectiveTaskId,
            createdAt = entity.createdAt,
            updatedAt = now,
        )

        // Compared ignoring `updatedAt`, which moves on every call by construction: a reconciliation
        // that finds the manifest already correct must not rewrite it (§2.5).
        val remote = drive.readJsonOrNull(identityId, path, PackageJson.serializer())
        val packageChanged = remote == null ||
            remote.copy(updatedAt = "") != json.copy(updatedAt = "")
        if (packageChanged) {
            try {
                drive.writeJson(identityId, path, json, PackageJson.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw ManifestPublishException(t)
            }
            packageDao.upsert(
                entity.copy(
                    status = effectiveStatus.name,
                    latestTaskId = effectiveTaskId,
                    sourceCount = sources.size,
                    updatedAt = now,
                )
            )
        }

        val index = try {
            indexStore.update(identityId) { idx ->
                mergeEntry(idx, entity, sources, effectiveStatus, effectiveTaskId, resultKinds, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The object is on Drive and `package.json` may already be committed, so this is the
            // two-file window of §2.4 rather than an ordinary network failure. Reporting it as one
            // would tell the student to check their network about an upload that already worked.
            throw ManifestPublishException(t)
        }
        verifyIndexReadback(identityId, entity.packageId, sources, index.index.revision)
        ManifestResult(packageChanged, index.changed, index.index.revision)
    }

    /**
     * §2.4: read the index back and require that the revision did not go backwards and that this
     * package's `sourceIds` match the `package.json` just committed. A failed readback becomes
     * `MANIFEST_PUBLISH_FAILED`, which is retryable — the two-file window converges instead of being
     * hidden behind a premature "已上传".
     */
    private suspend fun verifyIndexReadback(
        identityId: String,
        packageId: String,
        sources: List<SourceRefJson>,
        committedRevision: Long,
    ) {
        val expected = sources.map { it.sourceId }.toSet()
        val readBack = indexStore.read(identityId)
        val entry = readBack.packages.firstOrNull { it.packageId == packageId }
        val consistent = readBack.revision >= committedRevision &&
            entry != null &&
            entry.sourceIds.toSet() == expected
        if (!consistent) throw ManifestPublishException()
    }

    /**
     * The manifest's inclusion rule, and nothing looser (§2.2 point 2): the existing
     * `UPLOADED + drivePath != null` rows, plus at most the one candidate whose PUT this attempt just
     * landed. `UPLOADING` may not have been PUT yet and `FAILED` may be a format, permission or hash
     * failure, so neither may be declared to exist on Drive.
     *
     * `delete_pending` rows are excluded even though they are still `UPLOADED`: the tombstone is
     * written *before* the Notebook/Drive delete starts (ZLQ-132 §3.9 item 2), and a manifest that
     * keeps advertising a source whose remote copy is being torn down would hand the generator a
     * citation that resolves to nothing.
     *
     * Sorted by `addedAt` because the candidate would otherwise be appended out of order, and a
     * projection whose order shifts on every call could never be recognized as unchanged.
     */
    private suspend fun projection(
        packageId: String,
        candidate: SourceRefJson?,
    ): List<SourceRefJson> {
        val persisted = sourceDao.listByPackage(packageId)
            .filter {
                it.uploadState == UploadState.UPLOADED.name &&
                    !it.drivePath.isNullOrBlank() &&
                    !it.deletePending
            }
            .map { sourceRef(it) }
        val merged = if (candidate == null) {
            persisted
        } else {
            persisted.filterNot { it.sourceId == candidate.sourceId } + candidate
        }
        return merged.sortedBy { it.addedAt }
    }

    private fun mergeEntry(
        idx: IndexJson,
        entity: LearningPackageEntity,
        sources: List<SourceRefJson>,
        status: PackageStatus,
        latestTaskId: String?,
        resultKinds: List<ResultKind>?,
        now: String,
    ): IndexJson {
        val existing = idx.packages.firstOrNull { it.packageId == entity.packageId }
        val entry = IndexPackage(
            packageId = entity.packageId,
            title = entity.title,
            goal = LearningGoal.valueOf(entity.goal),
            chapterRange = entity.chapterRange,
            status = status,
            sourceIds = sources.map { it.sourceId },
            latestTaskId = latestTaskId,
            resultKinds = resultKinds ?: existing?.resultKinds ?: emptyList(),
            createdAt = existing?.createdAt ?: entity.createdAt,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        // An entry whose content did not move keeps its own `updatedAt`; without this, every
        // reconciliation would look like a change and bump `revision` forever (§2.4).
        val settled = if (existing != null && existing.sameContent(entry)) existing else entry
        return idx.copy(packages = idx.packages.filterNot { it.packageId == entity.packageId } + settled)
    }

    private fun statusOf(entity: LearningPackageEntity): PackageStatus =
        PackageStatus.entries.firstOrNull { it.name == entity.status } ?: PackageStatus.READY

    private fun IndexPackage.sameContent(other: IndexPackage): Boolean =
        copy(updatedAt = "") == other.copy(updatedAt = "")

    companion object {
        /**
         * The one `SourceAssetEntity` → `SourceRefJson` projection. `TaskRunner` used to carry a
         * second copy that fell back to `application/octet-stream`, so the same row could be published
         * with two different MIME types depending on who wrote the manifest last.
         */
        fun sourceRef(row: SourceAssetEntity): SourceRefJson = SourceRefJson(
            sourceId = row.sourceId,
            displayName = row.displayName,
            drivePath = row.drivePath.orEmpty(),
            mimeType = row.mimeType ?: CanonicalType.of(row.canonicalType).mimeType,
            sizeBytes = row.sizeBytes,
            sha256 = row.sha256.orEmpty(),
            kind = row.kind,
            addedAt = row.addedAt,
        )
    }
}

/**
 * Serialized read-modify-write of `index.json` with a monotonic revision.
 *
 * `revision` counts content-changing snapshots, not writes (ZLQ-110 §2.4), so a no-op merge neither
 * rewrites the object nor bumps it — that is what lets the startup reconciliation run on every cold
 * start without inflating the index.
 */
class IndexStore(private val drive: ManifestStore) {
    private val mutex = Mutex()

    /** [changed] is false when the canonical content did not move and nothing was written. */
    data class Update(val index: IndexJson, val changed: Boolean)

    suspend fun read(identityId: String): IndexJson = mutex.withLock {
        withContext(Dispatchers.IO) { load(identityId) }
    }

    suspend fun update(identityId: String, mutate: (IndexJson) -> IndexJson): Update =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val remote = load(identityId)
                val merged = mutate(remote)
                if (sameCanonical(remote.packages, merged.packages)) {
                    return@withContext Update(remote, changed = false)
                }
                val next = merged.copy(
                    identityId = identityId,
                    revision = remote.revision + 1,
                    updatedAt = Instant.now().toString(),
                )
                drive.writeJson(identityId, DrivePath.index(), next, IndexJson.serializer())
                Update(next, changed = true)
            }
        }

    /**
     * Order-insensitive and blind to `updatedAt`: `mergeEntry` appends the touched package last, so a
     * positional comparison would report a change for a set that did not move.
     */
    private fun sameCanonical(left: List<IndexPackage>, right: List<IndexPackage>): Boolean =
        left.sortedBy { it.packageId }.map { it.copy(updatedAt = "") } ==
            right.sortedBy { it.packageId }.map { it.copy(updatedAt = "") }

    private suspend fun load(identityId: String): IndexJson =
        drive.readJsonOrNull(identityId, DrivePath.index(), IndexJson.serializer())
            ?: IndexJson(
                identityId = identityId,
                revision = 0,
                updatedAt = Instant.now().toString(),
                packages = emptyList(),
            )
}
