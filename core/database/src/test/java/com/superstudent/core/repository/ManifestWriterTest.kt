package com.superstudent.core.repository

import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.PackageDao
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.ManifestStore
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.IndexJson
import com.superstudent.core.model.PackageJson
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.SourceRefJson
import com.superstudent.core.model.UploadState
import com.superstudent.core.model.ssJson
import com.superstudent.core.upload.ManifestPublishException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The write side of ZLQ-104 / ZLQ-110 §2: what a manifest may contain, when it may be rewritten, and
 * what happens in the window where `package.json` is committed but `index.json` is not.
 *
 * The fakes are hand-written rather than mocked because the module has no mocking dependency, and
 * because the interesting behaviour is the *ordering* of Drive writes against Room writes — something a
 * stub that only records calls would let pass unnoticed.
 */
class ManifestWriterTest {

    private val identity = "idt-1"
    private val packageId = "pkg-1"

    /**
     * Every fixture row belongs to this one process owner. Ownership is `(attemptToken, ownerId)`
     * since ZLQ-132 §5.4, so a commit that only supplied a token would now fail its own guard — the
     * tests here are about manifest ordering, and `SourceUploadFencingTest` is where the owner half
     * of the fence is asserted.
     */
    private val owner = "owner-1"

    private val drive = FakeManifestStore()
    private val packageDao = FakePackageDao()
    private val sourceDao = FakeSourceDao()
    private val writer = ManifestWriter(drive, packageDao, sourceDao, IndexStore(drive))

    // ---- AC-C #1: the defect itself ----------------------------------------------

    @Test
    fun `a source appears in the manifest its own attempt publishes`() = runBlocking {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")

        val committed = writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner)

        assertTrue(committed)
        assertEquals(listOf("src-a"), drive.packageJson(packageId)?.sources?.map { it.sourceId })
        assertEquals(listOf("src-a"), drive.index()?.entry(packageId)?.sourceIds)
        val after = sourceDao.find("src-a")!!
        assertEquals(UploadState.UPLOADED.name, after.uploadState)
        assertEquals(row.drivePath, after.drivePath)
        assertNull("the attempt token is released on success", after.attemptToken)
    }

    // ---- AC-C #2: the inclusion rule is not loosened ------------------------------

    @Test
    fun `only uploaded rows with a drive path are projected, plus this attempt's candidate`() =
        runBlocking {
            newPackage()
            val uploaded = pendingSource("src-ok", addedAt = "2026-09-30T10:00:00Z", token = "tok-ok")
            sourceDao.markUploaded(
                ownerId = owner,
                sourceId = "src-ok",
                token = "tok-ok",
                drivePath = uploaded.drivePath!!,
                sha256 = uploaded.sha256!!,
                sizeBytes = uploaded.sizeBytes,
                canonicalType = uploaded.canonicalType,
                now = "2026-09-30T10:01:00Z",
            )
            // None of these may be declared to exist on Drive: the PUT may not have happened, may have
            // failed, or may have been a format/permission/hash failure.
            pendingSource("src-uploading", addedAt = "2026-09-30T09:00:00Z", token = "tok-u")
            sourceDao.upsert(
                pendingSource("src-failed", addedAt = "2026-09-30T08:00:00Z", token = null)
                    .copy(uploadState = UploadState.FAILED.name)
            )
            sourceDao.upsert(
                pendingSource("src-nopath", addedAt = "2026-09-30T07:00:00Z", token = null)
                    .copy(uploadState = UploadState.UPLOADED.name, drivePath = null)
            )
            sourceDao.upsert(
                pendingSource("src-local", addedAt = "2026-09-30T06:00:00Z", token = null)
                    .copy(uploadState = UploadState.LOCAL_ONLY.name)
            )

            val row = pendingSource("src-new", addedAt = "2026-09-30T11:00:00Z", token = "tok-new")
            writer.commitUploadedSource(identity, packageId, candidate(row), "tok-new", owner)

            assertEquals(
                listOf("src-ok", "src-new"),
                drive.packageJson(packageId)?.sources?.map { it.sourceId },
            )
        }

    @Test
    fun `a candidate replaces its own stale row instead of appearing twice`() = runBlocking {
        newPackage()
        // The retry path: the first attempt got the object onto Drive, then failed to publish, so the
        // row is already UPLOADED-shaped in Room while the attempt re-runs with a fresh token.
        val stale = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-1")
        sourceDao.markUploaded(
            ownerId = owner,
            sourceId = "src-a",
            token = "tok-1",
            drivePath = "superstudent/v1/packages/pkg-1/sources/src-a/old.pdf",
            sha256 = stale.sha256!!,
            sizeBytes = stale.sizeBytes,
            canonicalType = stale.canonicalType,
            now = "2026-09-30T10:01:00Z",
        )
        sourceDao.upsert(stale.copy(uploadState = UploadState.UPLOADING.name, attemptToken = "tok-2"))

        writer.commitUploadedSource(identity, packageId, candidate(stale), "tok-2", owner)

        val sources = drive.packageJson(packageId)!!.sources
        assertEquals(listOf("src-a"), sources.map { it.sourceId })
        assertEquals(stale.drivePath, sources.single().drivePath)
    }

    // ---- AC-C #3: the lock, and what it protects ---------------------------------

    @Test
    fun `two sources committing at the same time both end up in the manifest`() = runBlocking {
        newPackage()
        val a = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        val b = pendingSource("src-b", addedAt = "2026-09-30T10:00:01Z", token = "tok-b")
        // Widens the window between "manifest written" and "row marked UPLOADED". If `markUploaded`
        // ever moves outside the package lock, the second attempt recomputes a projection that does
        // not contain the first one and the last write drops a source that is already on Drive.
        sourceDao.markUploadedDelayMillis = 150

        listOf(a, b).map { row ->
            async(Dispatchers.Default) {
                writer.commitUploadedSource(identity, packageId, candidate(row), row.attemptToken!!, owner)
            }
        }.awaitAll()

        assertEquals(
            listOf("src-a", "src-b"),
            drive.packageJson(packageId)?.sources?.map { it.sourceId },
        )
        assertEquals(listOf("src-a", "src-b"), drive.index()?.entry(packageId)?.sourceIds)
        assertEquals(UploadState.UPLOADED.name, sourceDao.find("src-a")!!.uploadState)
        assertEquals(UploadState.UPLOADED.name, sourceDao.find("src-b")!!.uploadState)
    }

    @Test
    fun `an upload commit never moves a generating package back to ready`() = runBlocking {
        newPackage()
        // A task started while this PUT was in flight; Room is the only business-state source, and the
        // writer re-reads it inside the lock rather than trusting a snapshot from before the PUT.
        packageDao.upsert(packageDao.find(identity, packageId)!!.let {
            it.copy(status = PackageStatus.GENERATING.name, latestTaskId = "task-9")
        })
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")

        writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner)

        val published = drive.packageJson(packageId)!!
        assertEquals(PackageStatus.GENERATING, published.status)
        assertEquals("task-9", published.latestTaskId)
        assertEquals(PackageStatus.GENERATING.name, packageDao.find(identity, packageId)!!.status)
    }

    @Test
    fun `a superseded attempt publishes nothing at all`() = runBlocking {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        // The lease expired and a newer attempt claimed the row.
        sourceDao.upsert(row.copy(attemptToken = "tok-newer"))
        val settled = drive.writeLog.size

        val committed = writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner)

        assertFalse(committed)
        assertEquals("neither manifest is touched by an attempt that no longer owns the row",
            settled, drive.writeLog.size)
        assertEquals(emptyList<String>(), drive.packageJson(packageId)!!.sources.map { it.sourceId })
        assertEquals(UploadState.UPLOADING.name, sourceDao.find("src-a")!!.uploadState)
    }

    /**
     * The fourth of the four client-side fencing tests ZLQ-136 C5 names verbatim — hence the identifier
     * instead of this file's backtick prose. Its result may only be reported as
     * 「本地 fencing 闭合（AC-5a）」: the server-side manifest revision CAS is C1 out of scope for this
     * batch and is reported 「不可执行」. What is decidable here is the client half — a superseded
     * attempt never reaches `IndexStore`, so it cannot make it compute a revision at all.
     */
    @Test
    fun staleGenerationCannotAdvanceManifestRevision() = runBlocking {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        // The lease expired and a newer attempt claimed the row, taking both halves of the ownership
        // pair with it.
        sourceDao.upsert(row.copy(attemptToken = "tok-newer", leaseOwnerId = "owner-2"))
        val revision = drive.index()!!.revision
        val settled = drive.writeLog.size

        assertFalse(writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner))
        assertEquals("a superseded attempt advanced the index revision",
            revision, drive.index()!!.revision)
        assertEquals("a superseded attempt wrote to Drive", settled, drive.writeLog.size)

        // The attempt that does own the row advances the revision by exactly one, and publishes the
        // source it uploaded — the fence refuses a stale writer without stalling the live one.
        assertTrue(writer.commitUploadedSource(identity, packageId, candidate(row), "tok-newer", "owner-2"))
        assertEquals(revision + 1, drive.index()!!.revision)
        assertEquals(listOf("src-a"), drive.packageJson(packageId)!!.sources.map { it.sourceId })
        assertEquals(UploadState.UPLOADED.name, sourceDao.find("src-a")!!.uploadState)
    }

    // ---- AC-C #5: revision counts content changes, not writes ---------------------

    @Test
    fun `revision grows once per content change and not on a no-op`() = runBlocking {
        newPackage()
        assertEquals(1L, drive.index()!!.revision)

        val a = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        writer.commitUploadedSource(identity, packageId, candidate(a), "tok-a", owner)
        assertEquals(2L, drive.index()!!.revision)

        val before = drive.writeLog.size
        val again = writer.publishState(identity, packageId)
        assertFalse("a repair pass over unchanged state is silent", again.packageChanged)
        assertFalse(again.indexChanged)
        assertEquals(2L, again.revision)
        assertEquals(before, drive.writeLog.size)

        val b = pendingSource("src-b", addedAt = "2026-09-30T10:00:01Z", token = "tok-b")
        writer.commitUploadedSource(identity, packageId, candidate(b), "tok-b", owner)
        assertEquals(3L, drive.index()!!.revision)
    }

    // ---- AC-C #7: cold-start reconciliation is idempotent ------------------------

    @Test
    fun `a second reconciliation pass over an unchanged package rewrites nothing`() = runBlocking {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner)
        val settled = drive.writeLog.size
        val revision = drive.index()!!.revision
        val updatedAt = drive.index()!!.entry(packageId)!!.updatedAt

        repeat(3) { writer.publishState(identity, packageId) }

        assertEquals(settled, drive.writeLog.size)
        assertEquals(revision, drive.index()!!.revision)
        assertEquals("the entry keeps its own timestamp, so it never looks changed",
            updatedAt, drive.index()!!.entry(packageId)!!.updatedAt)
    }

    @Test
    fun `the reconciliation compares against exactly what a publication would write`() = runBlocking {
        newPackage()
        val ok = pendingSource("src-ok", addedAt = "2026-09-30T10:00:00Z", token = "tok-ok")
        sourceDao.markUploaded(
            ownerId = owner,
            sourceId = "src-ok",
            token = "tok-ok",
            drivePath = ok.drivePath!!,
            sha256 = ok.sha256!!,
            sizeBytes = ok.sizeBytes,
            canonicalType = ok.canonicalType,
            now = "2026-09-30T10:01:00Z",
        )
        pendingSource("src-uploading", addedAt = "2026-09-30T09:00:00Z", token = "tok-u")
        sourceDao.upsert(
            pendingSource("src-nopath", addedAt = "2026-09-30T08:00:00Z", token = null)
                .copy(uploadState = UploadState.UPLOADED.name, drivePath = null)
        )

        // The startup rule decides "does index.json need repairing?" from this set. If it were a
        // second copy of the filter, the two could drift into republishing on every cold start.
        assertEquals(setOf("src-ok"), writer.publishedSourceIds(packageId))

        val row = pendingSource("src-new", addedAt = "2026-09-30T11:00:00Z", token = "tok-new")
        writer.commitUploadedSource(identity, packageId, candidate(row), "tok-new", owner)

        val published = writer.publishedSourceIds(packageId)
        assertEquals(setOf("src-ok", "src-new"), published)
        assertEquals("so a settled package is recognized as needing no repair",
            published, drive.index()!!.entry(packageId).sourceIds.toSet())
        val again = writer.publishState(identity, packageId)
        assertFalse(again.packageChanged)
        assertFalse(again.indexChanged)
    }

    @Test
    fun `statusWhenEmpty applies only to a package whose projection is empty`() = runBlocking {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner)

        writer.publishState(
            identity,
            packageId,
            status = PackageStatus.READY,
            statusWhenEmpty = PackageStatus.LOCAL_ONLY,
        )
        assertEquals(PackageStatus.READY, drive.packageJson(packageId)!!.status)

        sourceDao.delete("src-a")
        writer.publishState(
            identity,
            packageId,
            status = PackageStatus.READY,
            statusWhenEmpty = PackageStatus.LOCAL_ONLY,
        )
        assertEquals(PackageStatus.LOCAL_ONLY, drive.packageJson(packageId)!!.status)
    }

    // ---- §2.1 / §2.4: the two-file window converges instead of being hidden ------

    @Test
    fun `an index write that fails leaves the row uncommitted so a retry converges`() {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        drive.failWritesEndingWith = "index.json"

        val failure = assertThrows(ManifestPublishException::class.java) {
            runBlocking { writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner) }
        }

        // Walked rather than read straight off `cause`: an exception crossing `withContext` is
        // re-wrapped by coroutine stacktrace recovery, with the original as its own cause.
        assertTrue(
            "the transport failure must stay reachable so the classifier still reports " +
                "MANIFEST_PUBLISH_FAILED instead of a network outage",
            generateSequence<Throwable>(failure) { it.cause }.any { it is IOException },
        )
        assertEquals("package.json is already committed — the window is real, not rolled back",
            listOf("src-a"), drive.packageJson(packageId)?.sources?.map { it.sourceId })
        // The row must not claim success: the retry re-PUTs the same object and republishes.
        val mid = sourceDao.peek("src-a")!!
        assertEquals(UploadState.UPLOADING.name, mid.uploadState)
        assertEquals("tok-a", mid.attemptToken)

        drive.failWritesEndingWith = null
        runBlocking {
            assertTrue(writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner))
        }

        assertEquals(listOf("src-a"), drive.index()?.entry(packageId)?.sourceIds)
        assertEquals(UploadState.UPLOADED.name, sourceDao.peek("src-a")!!.uploadState)
        assertEquals("the resent publication is idempotent, not additive",
            1, drive.packageJson(packageId)!!.sources.size)
    }

    @Test
    fun `an index that reads back stale fails the commit instead of reporting success`() {
        newPackage()
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = "tok-a")
        // The PUT was acked but the object did not move — a lost write, or another device
        // overwriting the index between our write and our readback (§2.4).
        drive.dropWritesEndingWith = "index.json"

        assertThrows(ManifestPublishException::class.java) {
            runBlocking { writer.commitUploadedSource(identity, packageId, candidate(row), "tok-a", owner) }
        }

        assertEquals(UploadState.UPLOADING.name, sourceDao.peek("src-a")!!.uploadState)
    }

    // ---- one projection, one MIME type -------------------------------------------

    @Test
    fun `a persisted row without a recorded mime type publishes its canonical type`() = runBlocking {
        newPackage()
        // The projection is built from Room, not from what the caller hands in, so this is the path
        // where `TaskRunner`'s old second copy used to publish `application/octet-stream` for a row
        // whose picker never reported a MIME type.
        sourceDao.upsert(
            pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = null).copy(
                uploadState = UploadState.UPLOADED.name,
                mimeType = null,
                canonicalType = CanonicalType.PNG.name,
            )
        )

        writer.publishState(identity, packageId)

        assertEquals(
            CanonicalType.PNG.mimeType,
            drive.packageJson(packageId)!!.sources.single().mimeType,
        )
    }

    @Test
    fun `the shared projection is what the writer publishes`() {
        val row = pendingSource("src-a", addedAt = "2026-09-30T10:00:00Z", token = null)
            .copy(
                uploadState = UploadState.UPLOADED.name,
                mimeType = null,
                canonicalType = CanonicalType.PNG.name,
            )

        assertEquals(
            SourceRefJson(
                sourceId = "src-a",
                displayName = row.displayName,
                drivePath = row.drivePath!!,
                mimeType = CanonicalType.PNG.mimeType,
                sizeBytes = row.sizeBytes,
                sha256 = row.sha256!!,
                kind = row.kind,
                addedAt = row.addedAt,
            ),
            ManifestWriter.sourceRef(row),
        )
    }

    // ---- fixtures ----------------------------------------------------------------

    private fun newPackage(status: PackageStatus = PackageStatus.DRAFT) {
        val now = "2026-09-30T09:00:00Z"
        runBlocking {
            writer.create(
                identity,
                LearningPackageEntity(
                    packageId = packageId,
                    identityId = identity,
                    title = "高一数学",
                    goal = "PREVIEW",
                    chapterRange = "1-3",
                    status = status.name,
                    latestTaskId = null,
                    sourceCount = 0,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    private fun pendingSource(
        sourceId: String,
        addedAt: String,
        token: String?,
    ): SourceAssetEntity {
        val row = SourceAssetEntity(
            sourceId = sourceId,
            packageId = packageId,
            drivePath = "superstudent/v1/packages/$packageId/sources/$sourceId/" +
                "aaaaaaaaaaaa.pdf",
            displayName = "$sourceId.pdf",
            mimeType = "application/pdf",
            kind = "FILE",
            sizeBytes = 2048,
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            uploadState = UploadState.UPLOADING.name,
            localUri = "content://downloads/$sourceId",
            addedAt = addedAt,
            canonicalType = CanonicalType.PDF.name,
            attemptToken = token,
            leaseOwnerId = if (token == null) null else owner,
        )
        runBlocking { sourceDao.upsert(row) }
        return row
    }

    private fun candidate(row: SourceAssetEntity) = ManifestCandidate(
        ref = ManifestWriter.sourceRef(row),
        sha256 = row.sha256!!,
        sizeBytes = row.sizeBytes,
        canonicalType = row.canonicalType,
    )
}

private fun IndexJson.entry(packageId: String) = packages.first { it.packageId == packageId }

/**
 * In-memory Drive. Two sabotage hooks model the only two interesting transport outcomes for the
 * two-file window: the write throws, and the write is acked but does not stick.
 *
 * The three doubles below are `internal` rather than `private` because `CancelCompensatorTest`
 * (ZLQ-114 §5.4) drives a real `ManifestWriter` over the same ones instead of keeping a second copy.
 */
internal class FakeManifestStore : ManifestStore {
    val objects = mutableMapOf<String, String>()
    val writeLog = mutableListOf<String>()
    var failWritesEndingWith: String? = null
    var dropWritesEndingWith: String? = null

    override suspend fun <T> readJsonOrNull(
        identityId: String,
        path: String,
        serializer: KSerializer<T>,
    ): T? {
        val text = synchronized(objects) { objects[path] } ?: return null
        return ssJson.decodeFromString(serializer, text)
    }

    override suspend fun <T> writeJson(
        identityId: String,
        path: String,
        value: T,
        serializer: KSerializer<T>,
    ) {
        failWritesEndingWith?.let {
            if (path.endsWith(it)) throw IOException("simulated transport failure for $path")
        }
        val encoded = ssJson.encodeToString(serializer, value)
        synchronized(objects) {
            writeLog += path
            dropWritesEndingWith?.let { if (path.endsWith(it)) return }
            objects[path] = encoded
        }
    }

    fun packageJson(packageId: String): PackageJson? =
        synchronized(objects) { objects[DrivePath.packageJson(packageId)] }
            ?.let { ssJson.decodeFromString(PackageJson.serializer(), it) }

    fun index(): IndexJson? = synchronized(objects) { objects[DrivePath.index()] }
        ?.let { ssJson.decodeFromString(IndexJson.serializer(), it) }
}

internal fun unsupported(): Nothing = throw UnsupportedOperationException("not used by ManifestWriter")

internal class FakePackageDao : PackageDao {
    private val rows = mutableMapOf<String, LearningPackageEntity>()

    /** Non-suspend read for assertions, so a test body does not need a second `runBlocking`. */
    fun peek(packageId: String): LearningPackageEntity? = synchronized(rows) { rows[packageId] }

    override suspend fun upsert(pkg: LearningPackageEntity) {
        synchronized(rows) { rows[pkg.packageId] = pkg }
    }

    override suspend fun upsertAll(pkgs: List<LearningPackageEntity>) = pkgs.forEach { upsert(it) }

    override fun observeByIdentity(identityId: String): Flow<List<LearningPackageEntity>> = unsupported()

    override suspend fun listByIdentity(identityId: String): List<LearningPackageEntity> =
        synchronized(rows) { rows.values.filter { it.identityId == identityId }.toList() }

    override suspend fun find(identityId: String, packageId: String): LearningPackageEntity? =
        synchronized(rows) { rows[packageId]?.takeIf { it.identityId == identityId } }

    override fun observe(identityId: String, packageId: String): Flow<LearningPackageEntity?> =
        unsupported()

    override suspend fun delete(identityId: String, packageId: String) {
        synchronized(rows) { rows.remove(packageId) }
    }

    // ZLQ-106 recovery write, reached only through TaskRepository/TaskRunner — never through
    // ManifestWriter, which is what this fake stands in for.
    override suspend fun backToReadyIfGenerating(packageId: String, taskId: String, now: String): Int =
        unsupported()

    // ZLQ-114 §5.2 cancel reset. Same reason: only TaskRepository's cancel transaction calls it.
    override suspend fun resetToReadyAfterCancel(
        packageId: String,
        taskId: String,
        attempt: Int,
        now: String,
    ): Int = unsupported()
}
