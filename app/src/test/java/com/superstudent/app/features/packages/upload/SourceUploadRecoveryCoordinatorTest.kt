package com.superstudent.app.features.packages.upload

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.UploadState
import com.superstudent.core.upload.SOURCE_MAX_ATTEMPTS
import com.superstudent.core.upload.SOURCE_NO_PROGRESS_MILLIS
import com.superstudent.core.upload.ProcessLeaseRegistry
import com.superstudent.core.upload.SourceErrorCode
import com.superstudent.core.upload.SourceLeaseEvent
import com.superstudent.core.upload.SourceLeaseReason
import com.superstudent.core.upload.SourceRecoveryLog
import com.superstudent.core.upload.SourceRecoveryTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The §3.2 decision, driven by a fake clock against real OS locks.
 *
 * The point of this file is the branch a test is not allowed to fake its way through: an owner whose
 * lease has expired but whose process is *still alive* must not be reclaimed (ZLQ-136 reverse gate 2 —
 * 「不得播种一个一开始就已死的 owner 来『证明』回收生效」). So [liveProcessRechecksAtEarliestLeaseDeadline]
 * starts from a second [ProcessLeaseRegistry] holding its kernel lock in this same JVM and directory,
 * watches a pass refuse to release the row it owns, and only then closes that registry — which is what a
 * process death actually is, since the kernel drops a `FileLock` — and watches the *same* coordinator
 * release the row on its next pass. Liveness here is a fact about the filesystem, not a boolean a
 * fixture sets.
 *
 * What the row-store fakes below are: in-memory emulations of the guarded statements, so this file tests
 * which branch the coordinator takes and what it then arms. The statements' own predicates are proven
 * separately, against real SQLite built from the exported v4 schema, in `SourceRecoverySqlTest`. Neither
 * file is evidence about server-side atomicity (C1/C5, reported as 「不可执行」).
 */
class SourceUploadRecoveryCoordinatorTest {

    private lateinit var dir: File
    private val open = mutableListOf<ProcessLeaseRegistry>()

    private var now = 1_700_000_000_000L
    private val rows = mutableListOf<SourceAssetEntity>()

    private val released = mutableListOf<String>()
    private val stoodDown = mutableListOf<String>()
    private val uploads = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private val armed = mutableListOf<Long>()
    private val logs = mutableListOf<String>()

    /** How many passes actually read the table — the property the single-flight guard exists for. */
    private var scans = 0

    /** This process's registry — the one the coordinator probes with. */
    private lateinit var leases: ProcessLeaseRegistry

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ss-recovery").toFile()
        leases = registry()
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    /**
     * Every registry shares [dir], because one directory is one device: a probe only proves an owner is
     * gone when it contends for the *same* lock file that owner holds.
     */
    private fun registry(): ProcessLeaseRegistry = ProcessLeaseRegistry(dir).also { open += it }

    // ---- the in-memory row store -----------------------------------------------------------------

    private fun replace(sourceId: String, next: SourceAssetEntity) {
        val at = rows.indexOfFirst { it.sourceId == sourceId }
        if (at >= 0) rows[at] = next
    }

    private fun row(sourceId: String): SourceAssetEntity? = rows.firstOrNull { it.sourceId == sourceId }

    /**
     * `LIST_RECOVERY_CANDIDATES` in memory, including its `last_progress_at` branch: that branch is the
     * only way §3.2 condition 4 is reachable, since a wedged PUT whose heartbeat still renews keeps
     * `lease_until` in the future forever.
     */
    private fun isCandidate(at: Long, row: SourceAssetEntity): Boolean = when {
        row.deletePending -> true
        row.uploadState == UploadState.UPLOADING.name ->
            (row.leaseUntil?.let { it < at } ?: true) ||
                row.lastProgressAt?.let { it < at - SOURCE_NO_PROGRESS_MILLIS } == true
        row.uploadState == UploadState.PENDING.name -> row.nextRetryAt?.let { it <= at } ?: true
        row.uploadState == UploadState.FAILED.name ->
            row.retryable && row.attemptCount < SOURCE_MAX_ATTEMPTS &&
                (row.nextRetryAt?.let { it <= at } ?: true)
        else -> false
    }

    /** `NEXT_RECOVERY_DEADLINE`, including its `due > now` filter: the three families, future only. */
    private fun deadline(): Long? = rows.mapNotNull { row ->
        when {
            row.deletePending -> row.deleteRequestedAt
            row.uploadState == UploadState.UPLOADING.name -> row.leaseUntil
            row.uploadState == UploadState.FAILED.name && row.retryable &&
                row.attemptCount < SOURCE_MAX_ATTEMPTS -> row.nextRetryAt
            else -> null
        }
    }.filter { it > now }.minOrNull()

    private fun coordinator(
        canUpload: () -> Boolean = { false },
        gate: CompletableDeferred<Unit>? = null,
    ) = SourceUploadRecoveryCoordinator(
        listCandidates = { at ->
            gate?.await()
            scans++
            rows.filter { isCandidate(at, it) }.toList()
        },
        releaseOrphan = { id, at ->
            val target = row(id)
            if (target == null || target.deletePending || target.uploadState != UploadState.UPLOADING.name) {
                false
            } else {
                val spent = target.attemptCount >= SOURCE_MAX_ATTEMPTS
                replace(id, target.copy(
                    uploadState = UploadState.FAILED.name,
                    errorCode = SourceErrorCode.PROCESS_INTERRUPTED,
                    errorMessage = "上传中断，请重试",
                    retryable = !spent,
                    nextRetryAt = if (spent) null else at,
                    attemptToken = null,
                    leaseOwnerId = null,
                    leaseUntil = null,
                    leaseHeartbeatAt = null,
                    uploadStartedAt = null,
                    lastProgressAt = null,
                    interruptRequestedAt = null,
                ))
                released += id
                true
            }
        },
        requestStandDown = { id, at ->
            val target = row(id)
            if (target == null || target.deletePending || target.uploadState != UploadState.UPLOADING.name ||
                target.interruptRequestedAt != null
            ) {
                false
            } else {
                replace(id, target.copy(interruptRequestedAt = at))
                stoodDown += id
                true
            }
        },
        listResumable = { at ->
            rows.filter {
                !it.deletePending && it.uploadState != UploadState.UPLOADING.name && isCandidate(at, it)
            }
        },
        nextDeadline = { deadline() },
        leases = leases,
        canUpload = canUpload,
        enqueueUpload = { uploads += it },
        enqueueDelete = { deletes += it },
        armDeadline = { armed += it },
        clock = { now },
        log = { logs += it },
    )

    // ---- fixtures --------------------------------------------------------------------------------

    private fun uploading(
        sourceId: String = "src_1",
        ownerId: String?,
        leaseUntil: Long?,
        lastProgressAt: Long? = now,
        attemptCount: Int = 2,
    ) {
        rows += SourceAssetEntity(
            sourceId = sourceId,
            packageId = "pkg_1",
            drivePath = null,
            displayName = "第三章.pdf",
            mimeType = "application/pdf",
            kind = "FILE",
            sizeBytes = 2048,
            sha256 = null,
            uploadState = UploadState.UPLOADING.name,
            localUri = "content://downloads/$sourceId",
            addedAt = "2026-01-01T00:00:00Z",
            localAccessMode = LocalAccessMode.PERSISTED_URI.name,
            canonicalType = CanonicalType.PDF.name,
            attemptCount = attemptCount,
            attemptToken = "tok_$sourceId",
            leaseUntil = leaseUntil,
            leaseOwnerId = ownerId,
            leaseHeartbeatAt = lastProgressAt,
            uploadStartedAt = lastProgressAt,
            lastProgressAt = lastProgressAt,
        )
    }

    private fun leaseLine(owner: String?, token: String?, reason: SourceLeaseReason) =
        SourceRecoveryLog.lease("src_1", SourceLeaseEvent.INTERRUPT, owner, token, reason)

    private fun recoveryLine(trigger: SourceRecoveryTrigger, scanned: Int, interrupted: Int, enqueued: Int, nextDue: Long?) =
        SourceRecoveryLog.recovery(trigger, scanned, interrupted, enqueued, nextDue)

    // ---- reverse gate 2 / AC-3: a live owner is re-checked, never reclaimed ------------------------

    @Test
    fun `liveProcessRechecksAtEarliestLeaseDeadline`() = runTest {
        // A second process, alive: its registry holds `upload-owner/<id>.lock` for as long as it exists.
        val live = registry()
        val wedged = now - SOURCE_NO_PROGRESS_MILLIS - 1
        uploading(ownerId = live.ownerId, leaseUntil = now - 1, lastProgressAt = wedged)

        coordinator().request(SourceRecoveryTrigger.PROCESS_START)

        // 「不得回收」: the row keeps its state, its token and its owner. What was written is the
        // stand-down request, which the owner's next guarded renew reads as "stop".
        assertEquals("a live owner's row was released", emptyList<String>(), released)
        assertEquals(listOf("src_1"), stoodDown)
        val after = row("src_1")!!
        assertEquals(UploadState.UPLOADING.name, after.uploadState)
        assertEquals("tok_src_1", after.attemptToken)
        assertEquals(live.ownerId, after.leaseOwnerId)
        assertEquals(now, after.interruptRequestedAt)
        assertEquals(
            listOf(
                // Condition 4: a live owner that stopped making progress is asked to stand down.
                leaseLine(live.ownerId, "tok_src_1", SourceLeaseReason.NO_PROGRESS),
                recoveryLine(SourceRecoveryTrigger.PROCESS_START, 1, 0, 0, null),
            ),
            logs,
        )
        // Its `lease_until` is already behind the clock, so the shipped deadline statement contributes
        // nothing and no follow-up is armed at 0 ms — that would be a pass per scheduler tick, forever,
        // against an owner this process may not touch. The next trigger re-examines the row instead.
        assertEquals(emptyList<Long>(), armed)

        // A healthy in-flight row is not scanned at all, but its deadline is exactly what gets armed:
        // the owner promised to renew by then, so that is the earliest moment worth asking again.
        rows.clear(); released.clear(); stoodDown.clear(); logs.clear(); armed.clear(); scans = 0
        uploading(ownerId = live.ownerId, leaseUntil = now + 90_000)
        coordinator().request(SourceRecoveryTrigger.DEADLINE)
        assertEquals(1, scans)
        assertEquals(listOf(90_000L), armed)
        assertEquals(listOf(recoveryLine(SourceRecoveryTrigger.DEADLINE, 0, 0, 0, 90_000)), logs)

        // The clock passing that deadline changes nothing while the owner lives: re-checked, still not
        // reclaimed. This is the assertion a seeded-dead-owner fixture would have skipped.
        now += 90_001
        armed.clear(); logs.clear(); stoodDown.clear()
        coordinator().request(SourceRecoveryTrigger.DEADLINE)
        assertEquals("a live owner was reclaimed once its lease expired", emptyList<String>(), released)
        assertEquals(UploadState.UPLOADING.name, row("src_1")!!.uploadState)
        assertEquals(
            listOf(
                // Same refusal, honest reason: the lease expired but the process is provably there.
                leaseLine(live.ownerId, "tok_src_1", SourceLeaseReason.OWNER_ALIVE),
                recoveryLine(SourceRecoveryTrigger.DEADLINE, 1, 0, 0, null),
            ),
            logs,
        )

        // Process death, as the kernel reports it: the lock is gone, so the very next pass releases the
        // row and — being able to upload — re-enqueues it in the same round (§3.4 「同轮重新 claim」).
        live.close()
        armed.clear(); logs.clear(); stoodDown.clear(); uploads.clear()
        coordinator(canUpload = { true }).request(SourceRecoveryTrigger.DEADLINE)
        assertEquals(listOf("src_1"), released)
        val settled = row("src_1")!!
        assertEquals(UploadState.FAILED.name, settled.uploadState)
        assertEquals(SourceErrorCode.PROCESS_INTERRUPTED, settled.errorCode)
        assertEquals("上传中断，请重试", settled.errorMessage)
        assertEquals("recovery is not an attempt", 2, settled.attemptCount)
        assertNull(settled.attemptToken)
        assertNull(settled.leaseOwnerId)
        assertEquals(listOf("src_1"), uploads)
        assertEquals(
            listOf(
                leaseLine(live.ownerId, "tok_src_1", SourceLeaseReason.ORPHAN_DEAD_OWNER),
                recoveryLine(SourceRecoveryTrigger.DEADLINE, 1, 1, 1, null),
            ),
            logs,
        )
    }

    // ---- §3.6: the local convergence is not gated on being able to upload -------------------------

    @Test
    fun `an offline pass still releases an orphan and enqueues nothing`() = runTest {
        val dead = registry()
        uploading(ownerId = dead.ownerId, leaseUntil = now - 1)
        dead.close()

        coordinator(canUpload = { false }).request(SourceRecoveryTrigger.PROCESS_START)

        assertEquals(listOf("src_1"), released)
        assertEquals("an offline pass must not start work it cannot finish", emptyList<String>(), uploads)
        val settled = row("src_1")!!
        // The row keeps an actionable presentation: retryable, due now, entries intact. It is never
        // rewritten into a fabricated NETWORK_UNAVAILABLE.
        assertEquals(SourceErrorCode.PROCESS_INTERRUPTED, settled.errorCode)
        assertTrue(settled.retryable)
        assertEquals(now, settled.nextRetryAt)
        assertEquals(
            listOf(
                leaseLine(dead.ownerId, "tok_src_1", SourceLeaseReason.ORPHAN_DEAD_OWNER),
                recoveryLine(SourceRecoveryTrigger.PROCESS_START, 1, 1, 0, null),
            ),
            logs,
        )
    }

    // ---- §3.9: a tombstone owes a delete, not an upload -------------------------------------------

    @Test
    fun `a tombstone is re-enqueued as a delete even when uploading is impossible`() = runTest {
        rows += SourceAssetEntity(
            sourceId = "src_tomb",
            packageId = "pkg_1",
            drivePath = "superstudent/v1/x.pdf",
            displayName = "第三章.pdf",
            mimeType = "application/pdf",
            kind = "FILE",
            sizeBytes = 2048,
            sha256 = "a".repeat(64),
            uploadState = UploadState.UPLOADED.name,
            localUri = null,
            addedAt = "2026-01-01T00:00:00Z",
            deletePending = true,
            deleteRequestedAt = now - 60_000,
        )

        coordinator(canUpload = { false }).request(SourceRecoveryTrigger.PROCESS_START)

        assertEquals(listOf("src_tomb"), deletes)
        assertEquals(emptyList<String>(), uploads)
        assertEquals(emptyList<String>(), released)
        assertEquals(listOf(recoveryLine(SourceRecoveryTrigger.PROCESS_START, 1, 0, 1, null)), logs)
    }

    // ---- the single-flight guard ------------------------------------------------------------------

    @Test
    fun `triggers arriving mid-pass cost one follow-up pass, not one each`() = runTest {
        val dead = registry()
        uploading(ownerId = dead.ownerId, leaseUntil = now - 1)
        dead.close()
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(gate = gate)
        val cycle = listOf(
            SourceRecoveryTrigger.OUTCOME,
            SourceRecoveryTrigger.NETWORK,
            SourceRecoveryTrigger.PAGE,
        )

        // The first trigger owns the pass and blocks inside it; the rest must merge into its single
        // follow-up rather than each starting a scan of their own.
        val first = launch { coordinator.request(SourceRecoveryTrigger.PROCESS_START) }
        testScheduler.advanceUntilIdle()
        val fired = (1..50).map { i -> cycle[i % cycle.size] }
        val merged = fired.map { trigger -> async { coordinator.request(trigger) } }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        first.join()
        merged.forEach { it.await() }

        // One pass for the owner, one merged follow-up. Not fifty-one.
        assertEquals(2, scans)
        // The follow-up still scans: the row the first pass released is now `FAILED` and due, and an
        // offline pass owes it the honest "actionable, not enqueued" branch rather than a skip.
        // A trigger is a set, so the three distinct reasons survive the merge and each is still logged,
        // in arrival order — merging passes must not merge the audit trail away, nor reorder it.
        assertEquals(
            listOf(recoveryLine(SourceRecoveryTrigger.PROCESS_START, 1, 1, 0, null)) +
                fired.distinct().map { recoveryLine(it, 1, 0, 0, null) },
            logs.filter { it.startsWith("source_recovery") },
        )
    }
}
