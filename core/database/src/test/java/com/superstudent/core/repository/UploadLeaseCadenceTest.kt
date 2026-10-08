package com.superstudent.core.repository

import com.superstudent.core.upload.SOURCE_NO_PROGRESS_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §7.2 item 6: 上传 50 MB fake stream 的心跳/进度节流；10 min 无进展取消.
 *
 * What is decidable here, and what is not, is worth stating precisely, because the halves of item 6
 * live in different places:
 *
 * - The **progress throttle** is a pure predicate, `PackageRepository.shouldFlushProgress`, lifted out
 *   of the PUT callback for exactly this reason. Driving a simulated 50 MiB stream through it below is
 *   the real thing rather than a model of it: same function the callback calls, same gate state the
 *   callback keeps.
 * - The **heartbeat cadence** is a `delay(HEARTBEAT_MILLIS)` loop inside `PackageRepository.runAttempt`,
 *   and it cannot be driven from a JVM test: constructing a `PackageRepository` needs a
 *   `DriveRepository` and a `PresignedTransfer`, both final over a live HTTP stack. What is asserted
 *   instead is §3.3's parameter table plus the ratios the recovery rules derive from it — the first
 *   test says why the ratios, not the literals, are the load-bearing part.
 * - The **10-minute no-progress cancel** is not the uploader's job at all. The uploader only stops
 *   stamping; §3.2 condition 4 is evaluated by the recovery coordinator. That half is covered end to
 *   end by `SourceUploadRecoveryCoordinatorTest.liveProcessRechecksAtEarliestLeaseDeadline` (a wedged
 *   row with a *live* owner is stood down, not reclaimed) and at the SQL level by the
 *   `last_progress_at` branch of `LIST_RECOVERY_CANDIDATES` in `SourceRecoverySqlTest`. The last two
 *   tests here close the loop between them: a stalled stream is what produces the row they start from.
 *
 * Not proven anywhere on a JVM, and reported as such rather than as passed: that a real 50 MiB PUT over
 * a real socket emits callbacks at the rate simulated here. That is OkHttp's behaviour and it belongs
 * to the device-side regression.
 */
class UploadLeaseCadenceTest {

    private val total = 50L * 1024 * 1024
    private val chunk = 64L * 1024

    /** Wall clock at the claim, so a stall test can state an absolute `last_progress_at`. */
    private val startedAt = 1_700_000_000_000L

    @Test
    fun `the shipped parameters are the design's, and the ratios are what the recovery rules assume`() {
        assertEquals(120_000L, PackageRepository.LEASE_MILLIS)
        assertEquals(30_000L, PackageRepository.HEARTBEAT_MILLIS)
        assertEquals(15_000L, PackageRepository.PROGRESS_FLUSH_MILLIS)
        assertEquals(1L * 1024 * 1024, PackageRepository.PROGRESS_FLUSH_BYTES)
        assertEquals(10 * 60_000L, PackageRepository.NO_PROGRESS_MILLIS)
        assertEquals(SOURCE_NO_PROGRESS_MILLIS, PackageRepository.NO_PROGRESS_MILLIS)

        // §3.3's 「两次以上心跳丢失才过期」: four renewals fit inside one lease, so a single missed
        // heartbeat is a scheduling hiccup while an expired lease is already ~4 of them. The presenter
        // leans on this — `isInterrupted` may treat an expired lease as interrupted without the owner
        // being provably dead, because being four heartbeats late is already evidence.
        assertEquals(4L, PackageRepository.LEASE_MILLIS / PackageRepository.HEARTBEAT_MILLIS)

        // A progress stamp waits at most one heartbeat to reach the row, so the throttle can never make
        // `last_progress_at` look staler than the no-progress timeout tolerates.
        assertEquals(2L, PackageRepository.HEARTBEAT_MILLIS / PackageRepository.PROGRESS_FLUSH_MILLIS)

        // The ratio that makes §3.2 condition 4 necessary rather than redundant: a wedged PUT whose
        // heartbeat coroutine still runs renews `lease_until` twenty times before the no-progress rule
        // fires, so an expired-lease scan alone would never surface it. This is why
        // LIST_RECOVERY_CANDIDATES carries a separate `last_progress_at` branch.
        assertEquals(20L, PackageRepository.NO_PROGRESS_MILLIS / PackageRepository.HEARTBEAT_MILLIS)
        assertTrue(
            "the no-progress timeout must outlast the lease, or a healthy upload would be interrupted " +
                "before its first renewal",
            PackageRepository.NO_PROGRESS_MILLIS > PackageRepository.LEASE_MILLIS,
        )
    }

    @Test
    fun `a 50 MiB stream on a workable connection flushes on the byte gate, one row write per MiB`() {
        // 256 KiB/s: 50 MiB takes 200 s and 1 MiB takes 4 s, so the byte arm always arrives first.
        // 800 progress callbacks, 50 row writes — §3.3's 「不按每个字节写库」.
        val run = stream(bytesPerSecond = 256L * 1024)

        assertEquals(800, run.callbacks)
        assertEquals(50, run.flushes.size)
        assertTrue(
            "the throttle must collapse callbacks into row writes, not pass them through",
            run.flushes.size * 10 < run.callbacks,
        )
        // The byte arm won: the first flush lands before 15 s of transfer, and only once a MiB moved.
        assertTrue(run.firstFlushAtMillis < PackageRepository.PROGRESS_FLUSH_MILLIS)
        assertEquals(PackageRepository.PROGRESS_FLUSH_BYTES, run.firstFlushBytes)
        // And it keeps winning — every flush is exactly one MiB of transfer apart.
        run.deltas().forEach { (millis, bytes) ->
            assertEquals(PackageRepository.PROGRESS_FLUSH_BYTES, bytes)
            assertTrue(millis < PackageRepository.PROGRESS_FLUSH_MILLIS)
        }
    }

    @Test
    fun `on a weak connection the time gate arrives first, which is what 先到者 means`() {
        // 64 KiB/s: 1 MiB takes 16 s, so the 15 s arm always arrives first. This is the case a byte
        // gate alone would fail — progress evidence would come once every 16 s at best, and never at
        // all for a stream stalled just under a MiB.
        val run = stream(bytesPerSecond = 64L * 1024)

        assertTrue(run.callbacks > run.flushes.size)
        assertTrue(
            "the time arm must be able to fire with less than a MiB moved",
            run.firstFlushBytes < PackageRepository.PROGRESS_FLUSH_BYTES,
        )
        assertTrue(
            "the first flush must come from elapsed time, not from a full MiB",
            run.firstFlushAtMillis >= PackageRepository.PROGRESS_FLUSH_MILLIS,
        )
        run.deltas().forEach { (millis, bytes) ->
            assertTrue("the time arm fired but only $millis ms had elapsed",
                millis >= PackageRepository.PROGRESS_FLUSH_MILLIS)
            assertTrue("a full MiB moved, so this was the byte arm, not the time arm",
                bytes < PackageRepository.PROGRESS_FLUSH_BYTES)
        }
    }

    @Test
    fun `a stalled PUT stops stamping, which is what makes the 10-minute rule reachable`() {
        // The socket wedges after 2 MiB. OkHttp's progress callback only fires when bytes are handed to
        // the sink, so the callbacks stop too: nothing flushes again, `last_progress_at` freezes at the
        // last real movement, and §3.2 condition 4 becomes reachable ten minutes later.
        val run = stream(
            bytesPerSecond = 256L * 1024,
            stallAfterBytes = 2L * 1024 * 1024,
            stallForMillis = 20 * 60_000L,
        )

        assertEquals(2, run.flushes.size)
        assertTrue(
            "a stalled stream must stop stamping progress",
            run.noProgressMillis >= PackageRepository.NO_PROGRESS_MILLIS,
        )

        // The predicate the recovery scan reads, applied to the row this stall produced: not a
        // candidate at one minute short, a candidate at one minute past. `SourceRecoverySqlTest` pins
        // the shipped SQL for the same branch; this is the arithmetic connecting a stalled PUT to it.
        val frozen = startedAt + run.lastFlushAtMillis
        fun candidate(now: Long) = frozen < now - PackageRepository.NO_PROGRESS_MILLIS
        assertFalse(candidate(frozen + PackageRepository.NO_PROGRESS_MILLIS - 1))
        assertTrue(candidate(frozen + PackageRepository.NO_PROGRESS_MILLIS + 1))
    }

    @Test
    fun `a callback loop over a wedged socket cannot fake byte progress`() {
        // The sharper version of the stall: callbacks keep arriving over a `sent` that no longer moves.
        // §9's risk table forbids reading 「协程仍活」 as byte progress, and a time arm that opened on
        // elapsed time alone would stamp `last_progress_at` every 15 s forever — quietly disabling the
        // rule the previous test depends on. At most one late flush is possible, from the gate's own
        // -1 seeding, and after it the gate stays shut for the rest of the stall.
        val run = stream(
            bytesPerSecond = 256L * 1024,
            stallAfterBytes = chunk,
            stallForMillis = 20 * 60_000L,
            keepCallingWhileStalled = true,
        )

        assertTrue(
            "a frozen `sent` must not keep opening the time arm; got ${run.flushes.size} flushes",
            run.flushes.size <= 1,
        )
        assertTrue(
            "the stall still has to look like no progress to the recovery scan",
            run.noProgressMillis >= PackageRepository.NO_PROGRESS_MILLIS,
        )
    }

    /**
     * One simulated attempt, in milliseconds and bytes relative to the claim. Mirrors the gate state
     * `runAttempt` keeps — `flushGateAt` seeded at the claim's timestamp, `flushGateBytes` at -1 — and
     * calls the same predicate the PUT callback calls.
     */
    private class Run(
        val callbacks: Int,
        val flushes: List<Pair<Long, Long>>,
        val clockAtEnd: Long,
    ) {
        val firstFlushAtMillis: Long get() = flushes.first().first
        val firstFlushBytes: Long get() = flushes.first().second
        val lastFlushAtMillis: Long get() = flushes.last().first

        /** How long the row has carried an unchanged `last_progress_at` by the end of the attempt. */
        val noProgressMillis: Long get() = clockAtEnd - lastFlushAtMillis

        fun deltas(): List<Pair<Long, Long>> =
            flushes.zipWithNext { (aMillis, aBytes), (bMillis, bBytes) ->
                (bMillis - aMillis) to (bBytes - aBytes)
            }
    }

    private fun stream(
        bytesPerSecond: Long,
        stallAfterBytes: Long = Long.MAX_VALUE,
        stallForMillis: Long = 0L,
        keepCallingWhileStalled: Boolean = false,
    ): Run {
        val millisPerChunk = chunk * 1000L / bytesPerSecond
        var gateAt = 0L
        var gateBytes = -1L
        var elapsed = 0L
        var sent = 0L
        var callbacks = 0
        val flushes = mutableListOf<Pair<Long, Long>>()

        fun callback() {
            callbacks++
            if (PackageRepository.shouldFlushProgress(gateAt, gateBytes, elapsed, sent)) {
                gateAt = elapsed
                gateBytes = sent
                flushes += elapsed to sent
            }
        }

        while (sent < total) {
            sent += chunk
            elapsed += millisPerChunk
            callback()
            if (sent >= stallAfterBytes) {
                val ticks = (stallForMillis / millisPerChunk).toInt()
                if (keepCallingWhileStalled) {
                    repeat(ticks) {
                        elapsed += millisPerChunk
                        callback()
                    }
                } else {
                    elapsed += stallForMillis
                }
                break
            }
        }
        return Run(callbacks, flushes, elapsed)
    }
}
