package com.superstudent.core.upload

import com.superstudent.core.security.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three §5.5 log lines, as a contract rather than as a formatting convenience.
 *
 * These strings are what a human reads back after a field failure to decide which of §3.2's four
 * recovery conditions fired, so two properties are load-bearing and both are asserted below: the field
 * list is complete and in a fixed order, and the two values that identify a device install never appear
 * in it raw. The enums' `wire` values are pinned as literal lists for the same reason ZLQ-128 pins its
 * state labels — a reflected `name` is not a contract, because R8 is free to rename it (ZLQ-136 reverse
 * gate 6).
 *
 * What is *not* proven here: that a caller passes honest numbers. `scanned` / `interrupted` /
 * `enqueued` come from `SourceUploadRecoveryCoordinator.pass`, and
 * `SourceUploadRecoveryCoordinatorTest` is where those counts are asserted against a fake clock.
 */
class SourceRecoveryLogTest {

    private fun moduleFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.exists() }

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    /** Every shipped Kotlin source in the app and core modules — tests are excluded on purpose. */
    private fun productionSources(): List<File> =
        (listOf(File(repoRoot, "app/src/main")) +
            (File(repoRoot, "core").listFiles()?.filter { File(it, "src/main").isDirectory }
                ?.map { File(it, "src/main") } ?: emptyList()))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    // ---- the exact lines -------------------------------------------------------------------------

    /**
     * The hash is recomputed from [Hashing] rather than read back from `hash8`, so the line assertion
     * is not a tautology: it pins both the field order *and* that the two identifying fields are the
     * truncated SHA-256 the KDoc promises.
     */
    private fun expect8(value: String): String = Hashing.sha256Hex(value).take(8)

    @Test
    fun `the three line shapes are the contract, field for field`() {
        assertEquals(
            "source_recovery trigger=PROCESS_START scanned=12 interrupted=1 enqueued=3 nextDueMs=90000",
            SourceRecoveryLog.recovery(SourceRecoveryTrigger.PROCESS_START, 12, 1, 3, 90_000),
        )
        assertEquals(
            "source_lease sourceId=src_1 event=INTERRUPT owner=${expect8(OWNER)}" +
                " attempt=${expect8(TOKEN)} reason=ORPHAN_DEAD_OWNER",
            SourceRecoveryLog.lease("src_1", SourceLeaseEvent.INTERRUPT, OWNER, TOKEN, SourceLeaseReason.ORPHAN_DEAD_OWNER),
        )
        assertEquals(
            "source_delete sourceId=src_1 event=REMOTE_DELETE reason=NOT_FOUND",
            SourceRecoveryLog.delete("src_1", SourceDeleteEvent.REMOTE_DELETE, "NOT_FOUND"),
        )
        // The optional tail is omitted whole, not emitted empty: `reason=` with nothing after it is a
        // field a grep cannot distinguish from a field that failed to resolve.
        assertEquals(
            "source_delete sourceId=src_1 event=DONE",
            SourceRecoveryLog.delete("src_1", SourceDeleteEvent.DONE),
        )
        assertEquals(
            "source_delete sourceId=src_1 event=DONE",
            SourceRecoveryLog.delete("src_1", SourceDeleteEvent.DONE, "  "),
        )
    }

    @Test
    fun `a missing id or a missing deadline reads as a dash, never as a value`() {
        assertEquals(
            "source_recovery trigger=DEADLINE scanned=0 interrupted=0 enqueued=0 nextDueMs=-",
            SourceRecoveryLog.recovery(SourceRecoveryTrigger.DEADLINE, 0, 0, 0, null),
        )
        assertEquals(
            "source_lease sourceId=src_1 event=RELEASE owner=- attempt=- reason=ORPHAN_NO_OWNER",
            SourceRecoveryLog.lease("src_1", SourceLeaseEvent.RELEASE, null, null, SourceLeaseReason.ORPHAN_NO_OWNER),
        )
        // Blank collapses to the same marker as absent, which is what a v3 row with no owner and a
        // cleared token both are. Nothing present ever hashes to it, because SHA-256 of "" is not blank.
        assertEquals("-", SourceRecoveryLog.hash8(""))
        assertEquals("-", SourceRecoveryLog.hash8(" "))
        assertEquals("-", SourceRecoveryLog.hash8(null))
    }

    // ---- §5.5: nothing identifying is printed raw -------------------------------------------------

    @Test
    fun `a lease line carries neither the owner uuid nor the attempt token`() {
        val line = SourceRecoveryLog.lease("src_1", SourceLeaseEvent.CLAIM, OWNER, TOKEN, SourceLeaseReason.ATTEMPT_START)

        assertFalse("the raw owner id reached the log", line.contains(OWNER))
        assertFalse("the raw attempt token reached the log", line.contains(TOKEN))
        // Nor a prefix of either, which is what a truncated-but-still-identifying value would be.
        assertFalse(line.contains(OWNER.take(13)))
        assertFalse(line.contains(TOKEN.take(12)))
        assertTrue(line.contains(expect8(OWNER)))
        assertTrue(line.contains(expect8(TOKEN)))
    }

    @Test
    fun `hash8 is eight hex characters, stable, and separates one owner from the next`() {
        val hash = SourceRecoveryLog.hash8(OWNER)
        assertEquals(8, hash.length)
        assertTrue("`$hash` is not hex", hash.matches(Regex("[0-9a-f]{8}")))
        assertEquals(hash, SourceRecoveryLog.hash8(OWNER))
        assertEquals(expect8(OWNER), hash)
        assertFalse(
            "two owners on one device must not read as one in the log",
            SourceRecoveryLog.hash8(OWNER) == SourceRecoveryLog.hash8(OWNER_2),
        )
        assertFalse(
            "two attempts by one owner must not read as one in the log",
            SourceRecoveryLog.hash8(TOKEN) == SourceRecoveryLog.hash8(TOKEN_2),
        )
    }

    @Test
    fun `no production source builds one of the three lines by hand`() {
        // §5.5 bans a full local URI, a presigned URL, an `Authorization` header and file bytes from
        // these lines. The formatter cannot carry any of them — it has no parameter for one — so the
        // only way a secret reaches a line is a call site that interpolates instead of calling. That is
        // a source-level fact, and this is the gate on it.
        val prefixes = listOf("source_recovery", "source_lease", "source_delete")
        val formatter = moduleFile("src/main/java/com/superstudent/core/upload/SourceRecoveryLog.kt")
        val offenders = productionSources()
            .filter { it.absolutePath != formatter.absolutePath }
            .filter { file -> prefixes.any { file.readText().contains("\"$it ") } }
            .map { it.relativeTo(repoRoot).path }
        assertEquals(
            "these lines are formatted in exactly one place, so the field list cannot drift and no " +
                "call site can interpolate a URI, a URL or a header into one",
            emptyList<String>(),
            offenders,
        )
    }

    // ---- the closed sets -------------------------------------------------------------------------

    @Test
    fun `every trigger, event and reason has an explicit uppercase wire value`() {
        assertEquals(
            listOf("PROCESS_START", "LOGIN", "NETWORK", "OUTCOME", "DEADLINE", "PAGE"),
            SourceRecoveryTrigger.entries.map { it.wire },
        )
        assertEquals(
            listOf("CLAIM", "HEARTBEAT", "INTERRUPT", "RELEASE"),
            SourceLeaseEvent.entries.map { it.wire },
        )
        assertEquals(
            listOf("MARK", "CANCEL", "REMOTE_DELETE", "MANIFEST", "DONE", "RETRY"),
            SourceDeleteEvent.entries.map { it.wire },
        )
        assertEquals(
            listOf(
                "ATTEMPT_START", "RENEWED", "PROGRESS", "LEASE_LOST", "ORPHAN_NO_OWNER",
                "ORPHAN_DEAD_OWNER", "NO_PROGRESS", "OWNER_ALIVE", "ATTEMPT_END",
            ),
            SourceLeaseReason.entries.map { it.wire },
        )

        val all = SourceRecoveryTrigger.entries.map { it.wire } +
            SourceLeaseEvent.entries.map { it.wire } +
            SourceDeleteEvent.entries.map { it.wire } +
            SourceLeaseReason.entries.map { it.wire }
        all.forEach { wire ->
            assertTrue("`$wire` is not greppable", wire.matches(Regex("[A-Z][A-Z_]*")))
        }
        assertEquals("a wire value is reused across two enums, so a grep cannot tell them apart",
            all.size, all.distinct().size)
    }

    @Test
    fun `the reasons distinguish all four recovery conditions from the branch that must not recover`() {
        // §3.2 has four conditions and one 「不得回收」 branch. If two of them shared a reason the log
        // could no longer show *which* one released a row, and a release from a bare `lease_until < now`
        // — the defect this design removes — would be indistinguishable from a legitimate one.
        val reasons = SourceLeaseReason.entries.map { it.wire }.toSet()
        assertTrue(reasons.containsAll(listOf(
            "ORPHAN_NO_OWNER",   // conditions 1 and 3
            "ORPHAN_DEAD_OWNER", // condition 2
            "NO_PROGRESS",       // condition 4
            "OWNER_ALIVE",       // 「不得回收」
        )))
    }

    // ---- reverse gate 6: no reflected name is a contract value ------------------------------------

    @Test
    fun `the log path never derives a wire value from a reflected name`() {
        val source = moduleFile("src/main/java/com/superstudent/core/upload/SourceRecoveryLog.kt").readText()
        listOf("simpleName", "qualifiedName", "javaClass", ".name}", ".name)").forEach { pattern ->
            assertFalse(
                "SourceRecoveryLog.kt reads a reflected name into a log line via `$pattern`; " +
                    "R8 may rename it, so the field would silently change in a release build",
                source.contains(pattern),
            )
        }
        // The positive half: every enum reference in a formatted string goes through `wire`.
        assertEquals(4, Regex("\\$\\{\\w+\\.wire}").findAll(source).count())
    }

    private companion object {
        /** Shaped like the real thing — a UUIDv7 process id and a UUIDv7 attempt token. */
        const val OWNER = "018f3c2a-7d41-7e00-9a2b-1c2d3e4f5a6b"
        const val OWNER_2 = "018f3c2a-7d41-7e00-9a2b-1c2d3e4f5a6c"
        const val TOKEN = "0190aa11-2233-7444-8555-66778899aabb"
        const val TOKEN_2 = "0190aa11-2233-7444-8555-66778899aabc"
    }
}
