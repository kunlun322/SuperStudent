package com.superstudent.core.repository

import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.CreateIdentityRequest
import com.superstudent.core.model.CreateSessionRequest
import com.superstudent.core.model.DeleteEntryRequest
import com.superstudent.core.model.DeleteEntryResponse
import com.superstudent.core.model.DownloadUrlRequest
import com.superstudent.core.model.DriveEntriesResponse
import com.superstudent.core.model.EventsResponse
import com.superstudent.core.model.IdentityDto
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.PagedIdentities
import com.superstudent.core.model.PresignedUrlResponse
import com.superstudent.core.model.SendEventsRequest
import com.superstudent.core.model.SessionDto
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.UploadState
import com.superstudent.core.model.UploadUrlRequest
import com.superstudent.core.network.PresignedTransfer
import com.superstudent.core.network.QcaApi
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.RemoteCancelConverger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * ZLQ-114 §8.1 items 5, 6, 7, 8 and 12: what one compensation pass does, in what order, and what it
 * leaves behind when a step cannot finish.
 *
 * Only the Room store is faked. The remote cancel, the tmp delete and the manifest publish all run
 * their production classes — [RemoteCancelConverger], [DriveRepository] (scope guards included) and
 * [ManifestWriter] over the in-memory Drive from `ManifestWriterTest` — against a [FakeQcaApi] that
 * scripts the cloud's answers and records the call sequence. So the assertions are about the real
 * ordering and the real published manifest, not about a stub's own bookkeeping.
 */
class CancelCompensatorTest {

    private val taskId = "task_c1"
    private val attempt = 2
    private val packageId = "pkg-1"

    /** The package owner. Deliberately not the account the app happens to be logged in with (#12). */
    private val owner = "idt-owner"
    private val currentAccount = "idt-someone-else"
    private val sessionId = "sess_c1"

    private val api = FakeQcaApi()
    private val store = FakeStore()
    private val cloud = FakeManifestStore()
    private val packageDao = FakePackageDao()
    private val sourceDao = FakeSourceDao()
    private val writer = ManifestWriter(cloud, packageDao, sourceDao, IndexStore(cloud))
    private val logs = mutableListOf<String>()

    private val compensator = CancelCompensator(
        store = store,
        drive = DriveRepository(api, PresignedTransfer(OkHttpClient())),
        manifestWriter = writer,
        // Zero delay: what is under test is the sequence of calls, not the wall clock between polls.
        remoteCancel = RemoteCancelConverger(api, pollAttempts = 3, pollDelayMillis = 0),
        log = { logs += it },
    )

    /** §5.2's post-commit state: Room is already terminal and already `READY`; only the cloud lags. */
    private fun seed(
        state: String = TaskState.CANCELED.name,
        cleanupPending: Boolean = true,
        sessionId: String? = this.sessionId,
        roomStatus: PackageStatus = PackageStatus.READY,
    ) = runBlocking {
        writer.create(
            owner,
            LearningPackageEntity(
                packageId = packageId,
                identityId = owner,
                title = "初二数学",
                goal = "PREVIEW",
                chapterRange = null,
                status = PackageStatus.DRAFT.name,
                latestTaskId = taskId,
                sourceCount = 1,
                createdAt = "2026-09-30T09:00:00Z",
                updatedAt = "2026-09-30T09:00:00Z",
            ),
        )
        packageDao.upsert(packageDao.find(owner, packageId)!!.copy(status = roomStatus.name))
        sourceDao.upsert(
            SourceAssetEntity(
                sourceId = "src-a",
                packageId = packageId,
                drivePath = DrivePath.sourceObject(packageId, "src-a", "a".repeat(64), CanonicalType.PDF),
                displayName = "src-a.pdf",
                mimeType = "application/pdf",
                kind = "FILE",
                sizeBytes = 2048,
                sha256 = "a".repeat(64),
                uploadState = UploadState.UPLOADED.name,
                localUri = null,
                addedAt = "2026-09-30T09:00:01Z",
                canonicalType = CanonicalType.PDF.name,
                attemptToken = null,
            )
        )
        store.row = ActiveRunRow(
            run = TaskRunEntity(
                taskId = taskId,
                attempt = attempt,
                packageId = packageId,
                runId = "req_c1",
                sessionId = sessionId,
                state = state,
                stage = null,
                progress = 40,
                resumeFromStage = null,
                lastEventId = null,
                errorCode = "canceled",
                errorMessage = "用户取消",
                credits = 0.0,
                cleanupPending = cleanupPending,
                createdAt = "2026-09-30T09:00:02Z",
                startedAt = "2026-09-30T09:00:03Z",
                finishedAt = "2026-09-30T09:10:00Z",
                updatedAt = "2026-09-30T09:10:00Z",
            ),
            ownerIdentityId = owner,
            packageStatus = roomStatus.name,
            packageLatestTaskId = taskId,
        )
    }

    private fun tmpPath() = DrivePath.tmpDir(packageId, "$taskId-$attempt")

    private fun posted() = api.sessionCalls.filter { it.startsWith("POST") }

    // ---- §8.1 #5: an active Session is canceled and waited for --------------------------------

    @Test
    fun `an active session is canceled, waited for, and only then is anything deleted`() = runBlocking {
        seed()
        api.sessionStatuses += "running" // the GET before the POST: the turn is still billing
        api.sessionStatuses += "idle" // the confirmation poll after it

        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        assertEquals(
            "GET first, POST only because the GET said active, then confirm before touching Drive",
            listOf("GET $sessionId", "POST $sessionId", "GET $sessionId"),
            api.sessionCalls,
        )
        assertEquals(listOf(tmpPath()), api.deletedPaths)
        assertTrue("the marker must be cleared once every step landed", store.cleared)
        assertEquals(PackageStatus.READY, cloud.packageJson(packageId)!!.status)
    }

    @Test
    fun `an unconfirmed remote cancel keeps the marker and deletes nothing`() = runBlocking {
        seed()
        // Never goes idle: the POST is accepted but the turn keeps running.
        repeat(5) { api.sessionStatuses += "running" }

        assertEquals(CompensationOutcome.RETRY, compensator.compensate(taskId, attempt))

        assertEquals(listOf("POST $sessionId"), posted())
        assertEquals(
            "§5.3: an unconfirmed cancel must not delete the tmp subtree the cloud may still be writing",
            emptyList<String>(),
            api.deletedPaths,
        )
        assertEquals("the marker is what makes the next Worker attempt happen", false, store.cleared)
        assertTrue(logs.any { "unconfirmed" in it })
    }

    @Test
    fun `a session the cloud no longer knows is already converged and is never POSTed`() = runBlocking {
        seed()
        api.sessionErrors += QcaException(
            QcaErrorKind.SESSION_NOT_FOUND, 404, "session_not_found", null, "gone",
        )

        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        assertEquals(emptyList<String>(), posted())
        assertEquals(listOf(tmpPath()), api.deletedPaths)
        assertTrue(store.cleared)
    }

    @Test
    fun `an offline probe proves nothing, so the pass retries instead of guessing`() = runBlocking {
        seed()
        api.offline = true

        assertEquals(CompensationOutcome.RETRY, compensator.compensate(taskId, attempt))

        assertEquals("no cloud call may be attempted while the network is down", emptyList<String>(), api.sessionCalls)
        assertEquals(emptyList<String>(), api.deletedPaths)
        assertEquals(false, store.cleared)
    }

    // ---- §8.1 #6: a crash between the POST and the marker clear -------------------------------

    @Test
    fun `a replay after a crash before the marker clear GETs first and repeats no business effect`() =
        runBlocking {
            seed()
            api.sessionStatuses += "running"
            api.sessionStatuses += "idle"
            store.crashOnClear = true

            // The process dies at the marker clear: every side effect has already happened.
            assertThrows(IllegalStateException::class.java) {
                runBlocking { compensator.compensate(taskId, attempt) }
            }
            assertEquals(false, store.cleared)
            val writesAfterFirstPass = cloud.writeLog.size
            val revisionAfterFirstPass = cloud.index()!!.revision

            // Restart. The cloud already reports the terminal, so the replay must not cancel again.
            api.sessionStatuses += "idle"
            store.crashOnClear = false
            assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

            assertEquals("the replay GETs the terminal instead of POSTing a second cancel", emptyList<String>(), posted().drop(1))
            assertEquals(
                "re-publishing an unchanged projection must not rewrite either manifest",
                writesAfterFirstPass,
                cloud.writeLog.size,
            )
            assertEquals(revisionAfterFirstPass, cloud.index()!!.revision)
            assertTrue("the replay finishes what the crash interrupted", store.cleared)
        }

    @Test
    fun `a redelivery after the marker was cleared touches nothing`() = runBlocking {
        seed()
        api.sessionStatuses += "idle"
        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))
        val calls = api.sessionCalls.size
        val deletes = api.deletedPaths.size
        val writes = cloud.writeLog.size

        // WorkManager may redeliver a finished unique work; step 1 is what makes that a no-op.
        store.row = store.row!!.let { it.copy(run = it.run.copy(cleanupPending = false)) }
        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        assertEquals(calls, api.sessionCalls.size)
        assertEquals(deletes, api.deletedPaths.size)
        assertEquals(writes, cloud.writeLog.size)
    }

    @Test
    fun `a row that is gone, or is no longer a pending cancel, is done rather than retried`() = runBlocking {
        seed()

        store.row = null // the attempt row — or its package, which the join needs — is gone
        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        seed(state = TaskState.RUNNING.name)
        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        assertEquals("no cloud call for a row this pass does not own", emptyList<String>(), api.sessionCalls)
        assertEquals(emptyList<String>(), api.deletedPaths)
        assertEquals(false, store.cleared)
    }

    // ---- §8.1 #7: the tmp subtree --------------------------------------------------------------

    @Test
    fun `a tmp subtree that is already gone is success, and the delete stays inside this attempt`() =
        runBlocking {
            seed()
            api.sessionStatuses += "idle"
            api.deleteError = QcaException(QcaErrorKind.NOT_FOUND, 404, null, null, "no such path")

            assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

            val deleted = api.deletedPaths.single()
            assertEquals(tmpPath(), deleted)
            assertNotEquals("the package itself must never be the delete scope", DrivePath.packageDir(packageId), deleted)
            assertNotEquals(
                "a sibling attempt's directory is another run's work",
                DrivePath.tmpDir(packageId, "$taskId-${attempt + 1}"),
                deleted,
            )
            assertTrue(store.cleared)

            // The bound is the shipped guard, not a promise made by this test.
            val guarded = DriveRepository(api, PresignedTransfer(OkHttpClient()))
            listOf(DrivePath.ROOT, "superstudent/v1/packages", "").forEach { tooWide ->
                assertThrows("a delete of `$tooWide` must be refused", IllegalArgumentException::class.java) {
                    runBlocking { guarded.deleteSubtree(owner, tooWide) }
                }
            }
            assertEquals("a refused path never reaches the API", 1, api.deletedPaths.size)
        }

    @Test
    fun `a failed tmp delete keeps the marker and never reaches the manifest publish`() = runBlocking {
        seed()
        api.sessionStatuses += "idle"
        api.deleteError = QcaException(QcaErrorKind.RETRYABLE, 503, null, null, "unavailable")
        val writes = cloud.writeLog.size

        assertEquals(CompensationOutcome.RETRY, compensator.compensate(taskId, attempt))

        assertEquals("step 4 must not project a state step 3 did not reach", writes, cloud.writeLog.size)
        assertEquals(false, store.cleared)
    }

    // ---- §8.1 #8: the manifest publish ---------------------------------------------------------

    @Test
    fun `a failed publish leaves Room READY with the marker set, and the retry clears both`() =
        runBlocking {
            seed(roomStatus = PackageStatus.READY)
            repeat(3) { api.sessionStatuses += "idle" }
            cloud.failWritesEndingWith = "package.json"

            assertEquals(CompensationOutcome.RETRY, compensator.compensate(taskId, attempt))

            // A cloud failure does not roll the local convergence back — that is the point of §5.2:
            // the student can regenerate while the projection is still outstanding.
            assertEquals(PackageStatus.READY.name, packageDao.peek(packageId)!!.status)
            assertEquals("the manifest is what lagged, not Room", PackageStatus.DRAFT, cloud.packageJson(packageId)!!.status)
            assertEquals(false, store.cleared)

            // Network back: the same pass, from the top, finishes it.
            cloud.failWritesEndingWith = null
            assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

            assertEquals(PackageStatus.READY, cloud.packageJson(packageId)!!.status)
            assertEquals(listOf("src-a"), cloud.packageJson(packageId)!!.sources.map { it.sourceId })
            assertTrue(store.cleared)
        }

    // ---- §8.1 #12: whose identity the compensation runs under ---------------------------------

    @Test
    fun `compensation runs under the package owner, never under the logged-in account`() = runBlocking {
        seed()
        api.sessionStatuses += "idle"
        assertNotEquals("the fixture only means something if the two identities differ", owner, currentAccount)

        assertEquals(CompensationOutcome.COMPLETED, compensator.compensate(taskId, attempt))

        assertEquals(listOf(owner), api.deletedIdentities)
        // publishState resolves the package by (identity, packageId): had the pass used the signed-in
        // account it would have thrown PackageNotFoundException and returned RETRY instead.
        assertEquals(PackageStatus.READY, cloud.packageJson(packageId)!!.status)
        assertTrue(store.cleared)
    }
}

/**
 * The cloud as one compensation pass meets it: scripted Session statuses plus a record of every call
 * in the order it arrived, so a test can assert sequence and not merely outcome.
 */
private class FakeQcaApi : QcaApi {

    val sessionCalls = mutableListOf<String>()
    val deletedPaths = mutableListOf<String>()
    val deletedIdentities = mutableListOf<String>()

    /** Answered in order; the last one repeats, so a poll loop settles instead of running dry. */
    val sessionStatuses = mutableListOf<String>()
    val sessionErrors = mutableListOf<QcaException>()
    var deleteError: QcaException? = null
    var offline = false

    private fun noNetwork(): Nothing = throw IOException("simulated: no network")

    override suspend fun getSession(sessionId: String): SessionDto {
        if (offline) noNetwork()
        sessionCalls += "GET $sessionId"
        if (sessionErrors.isNotEmpty()) throw sessionErrors.removeAt(0)
        val status = if (sessionStatuses.size > 1) sessionStatuses.removeAt(0) else sessionStatuses.first()
        return SessionDto(id = sessionId, status = status)
    }

    override suspend fun cancelSession(sessionId: String): SessionDto {
        if (offline) noNetwork()
        sessionCalls += "POST $sessionId"
        return SessionDto(id = sessionId, status = sessionStatuses.lastOrNull() ?: "idle")
    }

    override suspend fun deleteDriveEntry(identityId: String, body: DeleteEntryRequest): DeleteEntryResponse {
        if (offline) noNetwork()
        deletedIdentities += identityId
        deletedPaths += body.path
        deleteError?.let { throw it }
        return DeleteEntryResponse(deletedCount = 1)
    }

    override suspend fun listIdentities(externalId: String, limit: Int): PagedIdentities = unsupported()

    override suspend fun createIdentity(idempotencyKey: String, body: CreateIdentityRequest): IdentityDto =
        unsupported()

    override suspend fun getIdentity(identityId: String): IdentityDto = unsupported()

    override suspend fun createSession(idempotencyKey: String, body: CreateSessionRequest): SessionDto =
        unsupported()

    override suspend fun sendEvents(
        sessionId: String,
        idempotencyKey: String,
        body: SendEventsRequest,
    ): EventsResponse = unsupported()

    override suspend fun listEvents(sessionId: String, afterId: String?, limit: Int): EventsResponse =
        unsupported()

    override suspend fun listDriveEntries(
        identityId: String,
        path: String?,
        limit: Int,
        pageToken: String?,
    ): DriveEntriesResponse = unsupported()

    override suspend fun uploadUrl(identityId: String, body: UploadUrlRequest): PresignedUrlResponse =
        unsupported()

    override suspend fun downloadUrl(identityId: String, body: DownloadUrlRequest): PresignedUrlResponse =
        unsupported()
}

/**
 * The Room side of §5.4, reduced to what the compensator reads and writes. [crashOnClear] models the
 * process dying at the worst instant: after every side effect, before the marker clear.
 */
private class FakeStore : CancelCompensationStore {

    var row: ActiveRunRow? = null
    var cleared = false
    var crashOnClear = false

    override suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow? =
        row?.takeIf { it.run.taskId == taskId && it.run.attempt == attempt }

    override suspend fun clearCancelCleanupPending(taskId: String, attempt: Int): Int {
        if (crashOnClear) throw IllegalStateException("simulated process death before the marker clear")
        cleared = true
        return 1
    }

    override suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow> = unsupported()

    override suspend fun resetPackageAfterCancel(packageId: String, taskId: String, attempt: Int): Int =
        unsupported()

    override suspend fun markCancelCleanupPending(taskId: String, attempt: Int): Int = unsupported()

    override suspend fun listCancelCleanupPending(): List<TaskRunEntity> = unsupported()
}
