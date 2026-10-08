package com.superstudent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ZLQ-84 / ZLQ-89 §1: the two seconds counters live directly under `stats`, are `Double`, and an
 * illegal or absent reading stays "not collected" instead of becoming 0.
 */
class SessionUsageSnapshotTest {

    private fun session(json: String): SessionDto = ssJson.decodeFromString(SessionDto.serializer(), json)

    @Test
    fun `fractional seconds decode where a Long field used to throw`() {
        val dto = session(
            """{"id":"s1","stats":{"active_seconds":3828.19,"duration_seconds":4440.28}}"""
        )
        assertEquals(3828.19, dto.stats?.activeSeconds!!, 0.0)
        assertEquals(4440.28, dto.stats?.durationSeconds!!, 0.0)
    }

    @Test
    fun `integer seconds decode into the same Double fields`() {
        val dto = session("""{"id":"s1","stats":{"active_seconds":3828,"duration_seconds":6226}}""")
        val snapshot = SessionUsageSnapshot.from(dto)
        assertEquals(3828.0, snapshot.activeSeconds!!, 0.0)
        assertEquals(6226.0, snapshot.durationSeconds!!, 0.0)
    }

    @Test
    fun `a missing stats block leaves both counters uncollected`() {
        val snapshot = SessionUsageSnapshot.from(session("""{"id":"s1"}"""))
        assertNull(snapshot.activeSeconds)
        assertNull(snapshot.durationSeconds)
    }

    @Test
    fun `a real zero is kept and stays distinguishable from uncollected`() {
        val snapshot = SessionUsageSnapshot.from(
            session("""{"id":"s1","stats":{"active_seconds":0,"duration_seconds":0.0}}""")
        )
        assertEquals(0.0, snapshot.activeSeconds!!, 0.0)
        assertEquals(0.0, snapshot.durationSeconds!!, 0.0)
    }

    @Test
    fun `a negative reading is dropped rather than clamped to zero`() {
        val snapshot = SessionUsageSnapshot.from(
            session("""{"id":"s1","stats":{"active_seconds":-12.5,"duration_seconds":-1}}""")
        )
        assertNull(snapshot.activeSeconds)
        assertNull(snapshot.durationSeconds)
    }

    @Test
    fun `a non-finite reading is dropped rather than stored`() {
        // `ssJson` rejects a non-finite JSON literal at decode time, so this only reaches the guard
        // from an in-process value; the guard is what keeps such a value out of Room.
        val snapshot = SessionUsageSnapshot.from(
            SessionDto(
                id = "s1",
                stats = SessionStats(
                    activeSeconds = Double.NaN,
                    durationSeconds = Double.POSITIVE_INFINITY,
                ),
            )
        )
        assertNull(snapshot.activeSeconds)
        assertNull(snapshot.durationSeconds)
    }

    @Test
    fun `a null session collects nothing`() {
        assertEquals(SessionUsageSnapshot.EMPTY, SessionUsageSnapshot.from(null))
    }

    @Test
    fun `top level credits win over the legacy stats usage path`() {
        val snapshot = SessionUsageSnapshot.from(
            session(
                """{"id":"s1","usage":{"total_credits":22.58},"stats":{"usage":{"total_credits":9.99}}}"""
            )
        )
        assertEquals(22.58, snapshot.totalCredits!!, 0.0)
    }

    @Test
    fun `legacy stats usage credits are still read when the top level is absent`() {
        val snapshot = SessionUsageSnapshot.from(
            session("""{"id":"s1","stats":{"usage":{"total_credits":9.99}}}""")
        )
        assertEquals(9.99, snapshot.totalCredits!!, 0.0)
    }

    @Test
    fun `absent credits stay uncollected instead of defaulting to a real zero`() {
        assertNull(SessionUsageSnapshot.from(session("""{"id":"s1","stats":{}}""")).totalCredits)
    }

    @Test
    fun `seconds are never read from the legacy usage object`() {
        // The old DTO put both counters under `usage`; a response that still does must not revive
        // a second source of truth for durations.
        val dto = session(
            """{"id":"s1","usage":{"active_seconds":111,"duration_seconds":222,"total_credits":1.0}}"""
        )
        val snapshot = SessionUsageSnapshot.from(dto)
        assertNull(snapshot.activeSeconds)
        assertNull(snapshot.durationSeconds)
        assertEquals(1.0, snapshot.totalCredits!!, 0.0)
    }
}
