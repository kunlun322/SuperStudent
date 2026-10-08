package com.superstudent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZLQ-89 §2: the `task.json` usage contract. `explicitNulls = false` is what makes "never collected"
 * (field absent) distinguishable from a real zero (field present as `0.0`) — the old client encoded
 * its `0` defaults, which is exactly why ZLQ-84 looked like a value instead of a gap.
 */
class TaskUsageJsonContractTest {

    private fun taskJson(usage: String, schemaVersion: Int) = """
        {"schemaVersion":$schemaVersion,"taskId":"t1","packageId":"p1","attempt":1,"runId":"r1",
         "state":"SUCCEEDED","usage":$usage,"createdAt":"2026-09-30T00:00:00Z",
         "updatedAt":"2026-09-30T00:00:00Z"}
    """.trimIndent()

    @Test
    fun `uncollected counters are omitted from the written file`() {
        val encoded = ssJson.encodeToString(
            TaskUsageJson.serializer(),
            TaskUsageJson(totalCredits = 12.5),
        )
        assertFalse(encoded.contains("activeSeconds"))
        assertFalse(encoded.contains("durationSeconds"))
        assertTrue(encoded.contains("\"totalCredits\":12.5"))
    }

    @Test
    fun `a real zero is written explicitly and survives a round trip`() {
        val encoded = ssJson.encodeToString(
            TaskUsageJson.serializer(),
            TaskUsageJson(activeSeconds = 0.0, durationSeconds = 0.0, totalCredits = 0.0),
        )
        assertTrue(encoded.contains("\"activeSeconds\":0.0"))
        assertTrue(encoded.contains("\"durationSeconds\":0.0"))
        val decoded = ssJson.decodeFromString(TaskUsageJson.serializer(), encoded)
        assertEquals(0.0, decoded.activeSeconds!!, 0.0)
        assertEquals(0.0, decoded.durationSeconds!!, 0.0)
    }

    @Test
    fun `newly written task json declares schemaVersion 2`() {
        val encoded = ssJson.encodeToString(
            TaskJson.serializer(),
            TaskJson(
                taskId = "t1",
                packageId = "p1",
                attempt = 1,
                runId = "r1",
                state = TaskState.SUCCEEDED,
                createdAt = "2026-09-30T00:00:00Z",
                updatedAt = "2026-09-30T00:00:00Z",
            ),
        )
        assertTrue(encoded.contains("\"schemaVersion\":2"))
    }

    @Test
    fun `a v1 file with integer zeros decodes without throwing`() {
        val decoded = ssJson.decodeFromString(
            TaskJson.serializer(),
            taskJson("""{"activeSeconds":0,"durationSeconds":0,"totalCredits":7}""", 1),
        )
        assertEquals(1, decoded.schemaVersion)
        assertEquals(0.0, decoded.usage.activeSeconds!!, 0.0)
        assertEquals(7.0, decoded.usage.totalCredits, 0.0)
    }

    @Test
    fun `a v2 file is read as written`() {
        val decoded = ssJson.decodeFromString(
            TaskJson.serializer(),
            taskJson("""{"activeSeconds":3828.19,"durationSeconds":4440.28,"totalCredits":22.58}""", 2),
        )
        assertEquals(3828.19, decoded.usage.activeSeconds!!, 0.0)
        assertEquals(4440.28, decoded.usage.durationSeconds!!, 0.0)
    }

    @Test
    fun `a v2 file with the counters absent reads as uncollected`() {
        val decoded = ssJson.decodeFromString(
            TaskJson.serializer(),
            taskJson("""{"totalCredits":22.58}""", 2),
        )
        assertNull(decoded.usage.activeSeconds)
        assertNull(decoded.usage.durationSeconds)
    }
}
