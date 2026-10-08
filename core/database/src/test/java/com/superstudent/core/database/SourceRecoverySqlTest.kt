package com.superstudent.core.database

import com.superstudent.core.upload.SOURCE_MAX_ATTEMPTS
import com.superstudent.core.upload.SOURCE_NO_PROGRESS_MILLIS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The upload-lease SQL, executed on the JVM against real SQLite built from the exported v4 schema
 * (ZLQ-132 §7.2 items 2 and 5, and the SQL half of item 8).
 *
 * The statements under test are the shipped [SourceRecoverySql] constants, referenced by the same
 * `@Query` annotations that ship them, so what is proven here is the part a fake DAO cannot show:
 * that a claim really cannot take a row another process is still `UPLOADING`, that the
 * `(attempt_token, lease_owner_id)` pair really fences a late commit, that `interrupt_requested_at`
 * really is the only channel a live owner learns to stand down through, and that reclaiming an orphan
 * really does leave `attempt_count` alone.
 *
 * The harness mirrors `CancelCompensationSqlTest`'s on purpose, so this fix does not require
 * re-reviewing the accepted ZLQ-114 behaviour that file pins.
 *
 * What this is **not**: evidence about server-side atomicity. `remote_generation` fencing, manifest
 * revision CAS and the durable backend tombstone are C1/C5 out of scope for this batch and are
 * reported as 「不可执行」 in the delivery comment, not as passing here.
 */
class SourceRecoverySqlTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun moduleFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.exists() }

    private fun schema(version: Int): JsonObject = json
        .parseToJsonElement(moduleFile("schemas/${SsDatabase::class.java.name}/$version.json").readText())
        .jsonObject.getValue("database").jsonObject

    private fun withV4Database(body: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                schema(4).getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    st.execute(
                        entity.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table)
                    )
                    entity["indices"]?.jsonArray?.forEach { index ->
                        st.execute(
                            index.jsonObject.getValue("createSql").jsonPrimitive.content
                                .replace("\${TABLE_NAME}", table)
                        )
                    }
                }
                st.execute("PRAGMA user_version = 4")
            }
            body(conn)
        }
    }

    /** Runs a shipped constant, binding its Room named parameters positionally. */
    private fun Connection.execute(sql: String, args: Map<String, Any?>): Int {
        val names = mutableListOf<String>()
        val jdbc = Regex(":(\\w+)").replace(sql) { match ->
            names += match.groupValues[1]
            "?"
        }
        prepareStatement(jdbc).use { ps ->
            names.forEachIndexed { i, name ->
                assertTrue("no value supplied for `:$name`", args.containsKey(name))
                when (val value = args.getValue(name)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Long -> ps.setLong(i + 1, value)
                    is Double -> ps.setDouble(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            return ps.executeUpdate()
        }
    }

    private fun Connection.query(
        sql: String,
        args: Map<String, Any?> = emptyMap(),
    ): List<Map<String, Any?>> {
        val names = mutableListOf<String>()
        val jdbc = Regex(":(\\w+)").replace(sql) { match ->
            names += match.groupValues[1]
            "?"
        }
        prepareStatement(jdbc).use { ps ->
            names.forEachIndexed { i, name ->
                when (val value = args.getValue(name)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Long -> ps.setLong(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val rows = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    rows += (1..meta.columnCount).associate { i ->
                        meta.getColumnLabel(i) to rs.getObject(i)?.takeIf { !rs.wasNull() }
                    }
                }
                return rows
            }
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** The shipped lease length, inlined because it is a `const val`. */
    private val LEASE_MILLIS = com.superstudent.core.repository.PackageRepository.LEASE_MILLIS

    private val nowMillis = 1_700_000_000_000L
    private val now = "2026-01-01T02:00:00Z"
    private val owner = "owner_1"
    private val token = "tok_1"

    /** Every v4 column, so the fixture cannot silently omit one the guards read. */
    private fun baseRow(
        sourceId: String = "src_1",
        state: String = "PENDING",
    ): Map<String, Any?> = mapOf(
        "source_id" to sourceId,
        "package_id" to "pkg_1",
        "drive_path" to null,
        "display_name" to "第三章.pdf",
        "mime_type" to "application/pdf",
        "kind" to "FILE",
        "size_bytes" to 2048L,
        "sha256" to null,
        "upload_state" to state,
        "local_uri" to "content://downloads/$sourceId",
        "added_at" to "2026-01-01T00:00:00Z",
        "local_access_mode" to "PERSISTABLE",
        "canonical_type" to "PDF",
        "error_code" to null,
        "error_message" to null,
        "retryable" to 0,
        "attempt_count" to 0,
        "next_retry_at" to null,
        "attempt_token" to null,
        "lease_until" to null,
        "lease_owner_id" to null,
        "lease_heartbeat_at" to null,
        "upload_started_at" to null,
        "last_progress_at" to null,
        "interrupt_requested_at" to null,
        "remote_generation" to null,
        "delete_pending" to 0,
        "delete_requested_at" to null,
        "updated_at" to "2026-01-01T00:00:00Z",
    )

    private fun Connection.insert(row: Map<String, Any?>) {
        val fields = row.keys.toList()
        assertEquals(
            "the fixture must cover every v4 column",
            baseRow().keys,
            fields.toSet(),
        )
        prepareStatement(
            "INSERT INTO source_asset (${fields.joinToString(",") { "`$it`" }}) " +
                "VALUES (${fields.joinToString(",") { "?" }})"
        ).use { ps ->
            fields.forEachIndexed { i, column ->
                when (val value = row.getValue(column)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Long -> ps.setLong(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            assertEquals(1, ps.executeUpdate())
        }
    }

    /**
     * Inserts one row. `added_at` is auto-incremented per call so every `ORDER BY added_at` assertion
     * below is deterministic instead of relying on SQLite's rowid tie-break.
     */
    private fun Connection.seed(vararg overrides: Pair<String, Any?>) {
        insert(
            baseRow()
                .plus("added_at" to "2026-01-01T00:%02d:00Z".format(sequence++))
                .plus(overrides.toMap())
        )
    }

    private var sequence = 0

    private fun Connection.row(sourceId: String = "src_1"): Map<String, Any?> =
        query("SELECT * FROM source_asset WHERE source_id = '$sourceId'").single()

    private fun Connection.int(sourceId: String, column: String): Int =
        (row(sourceId).getValue(column) as Number).toInt()

    /**
     * INTEGER columns come back as whichever boxed type fits, so a `Long` literal compared against
     * `getObject` fails on type alone. Every numeric assertion goes through this or [int].
     */
    private fun Connection.long(sourceId: String, column: String): Long =
        (row(sourceId).getValue(column) as Number).toLong()

    private fun Connection.claimArgs(
        sourceId: String = "src_1",
        token: String = this@SourceRecoverySqlTest.token,
        ownerId: String = this@SourceRecoverySqlTest.owner,
        nowMillis: Long = this@SourceRecoverySqlTest.nowMillis,
        maxAttempts: Int = SOURCE_MAX_ATTEMPTS,
    ) = mapOf(
        "sourceId" to sourceId,
        "token" to token,
        "ownerId" to ownerId,
        "leaseUntilMillis" to nowMillis + LEASE_MILLIS,
        "heartbeatAt" to nowMillis,
        "uploadStartedAt" to nowMillis,
        "lastProgressAt" to nowMillis,
        "nowMillis" to nowMillis,
        "maxAttempts" to maxAttempts,
        "now" to now,
    )

    private fun Connection.ownershipArgs(
        sourceId: String = "src_1",
        token: String = this@SourceRecoverySqlTest.token,
        ownerId: String = this@SourceRecoverySqlTest.owner,
    ) = mapOf(
        "sourceId" to sourceId,
        "token" to token,
        "ownerId" to ownerId,
        "heartbeatAt" to nowMillis + 30_000,
        "leaseUntilMillis" to nowMillis + 150_000,
        "now" to now,
    )

    /**
     * `COUNT_OWNED_ATTEMPT`, which `ManifestWriter.commitUploadedSource` asks *inside* the package lock
     * right before publishing. 0 is what makes it return false instead of writing a manifest the row
     * would then contradict.
     */
    private fun Connection.ownedCount(
        token: String,
        ownerId: String,
        sourceId: String = "src_1",
    ): Int = query(
        SourceRecoverySql.COUNT_OWNED_ATTEMPT,
        mapOf("sourceId" to sourceId, "token" to token, "ownerId" to ownerId),
    ).single().values.first().let { (it as Number).toInt() }

    // ---- §7.2 item 2: the claim guards ---------------------------------------------------------

    @Test
    fun `a claim takes a due PENDING row and writes the whole lease`() {
        withV4Database { conn ->
            conn.seed("error_code" to "TIMEOUT", "error_message" to "上传超时，请检查网络后重试")

            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            val row = conn.row()
            assertEquals("UPLOADING", row.getValue("upload_state"))
            assertEquals(token, row.getValue("attempt_token"))
            assertEquals(owner, row.getValue("lease_owner_id"))
            assertEquals(nowMillis + LEASE_MILLIS, row.getValue("lease_until"))
            assertEquals(nowMillis, row.getValue("lease_heartbeat_at"))
            assertEquals(nowMillis, row.getValue("upload_started_at"))
            assertEquals(nowMillis, row.getValue("last_progress_at"))
            assertEquals(1, conn.int("src_1", "attempt_count"))
            // A claim starts a clean cycle: the previous failure's copy must not survive into it.
            assertNull(row.getValue("error_code"))
            assertNull(row.getValue("error_message"))
            assertNull(row.getValue("next_retry_at"))
            assertNull(row.getValue("interrupt_requested_at"))
            assertEquals(now, row.getValue("updated_at"))
        }
    }

    @Test
    fun `a claim cannot take a row another attempt is still UPLOADING`() {
        // This is the whole defect. `CLAIM` has no UPLOADING branch, so an expired lease is not a
        // licence: the row must be released by `INTERRUPT_ATTEMPT` under the previous owner's OS lock
        // first. Without that ordering two processes PUT one object concurrently (§3.2 「不得回收」).
        withV4Database { conn ->
            conn.seed(
                "upload_state" to "UPLOADING",
                "attempt_token" to "tok_old",
                "lease_owner_id" to "owner_dead",
                "lease_until" to nowMillis - 1,
                "attempt_count" to 2,
            )

            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))
            assertEquals("UPLOADING", conn.row().getValue("upload_state"))
            assertEquals("tok_old", conn.row().getValue("attempt_token"))
            assertEquals("owner_dead", conn.row().getValue("lease_owner_id"))
            assertEquals("the refused claim must not spend budget", 2, conn.int("src_1", "attempt_count"))
        }
    }

    @Test
    fun `each claim guard refuses on its own`() {
        withV4Database { conn ->
            // Tombstoned: a row being deleted owes no upload.
            conn.seed("source_id" to "src_tomb", "upload_state" to "PENDING")
            assertEquals(1, conn.execute(
                SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now),
            ))
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_tomb")))

            // A stand-down request is pending: the owner has not confirmed it yet.
            conn.seed("source_id" to "src_stand", "upload_state" to "UPLOADING", "attempt_token" to "tok_live",
                "lease_owner_id" to "owner_live", "interrupt_requested_at" to nowMillis - 1)
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_stand")))

            // Already uploaded.
            conn.seed("source_id" to "src_done", "upload_state" to "UPLOADED")
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_done")))

            // A non-retryable failure: `retryable` is the scheduler's gate, never the manual entries'.
            conn.seed("source_id" to "src_perm", "upload_state" to "FAILED", "retryable" to 0,
                "error_code" to "FILE_TOO_LARGE", "attempt_count" to 1)
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_perm")))

            // Budget spent.
            conn.seed("source_id" to "src_spent", "upload_state" to "FAILED", "retryable" to 1,
                "attempt_count" to SOURCE_MAX_ATTEMPTS)
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_spent")))

            // Not due yet.
            conn.seed("source_id" to "src_later", "upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1,
                "next_retry_at" to nowMillis + 60_000)
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_later")))

            // The same row becomes claimable once the clock passes its deadline — the guard is a
            // comparison against `:nowMillis`, not a one-way latch.
            assertEquals(1, conn.execute(
                SourceRecoverySql.CLAIM,
                conn.claimArgs(sourceId = "src_later", nowMillis = nowMillis + 60_000),
            ))
        }
    }

    @Test
    fun `a clock that rolls back makes a due row not due, which is the conservative direction`() {
        // `next_retry_at` and `lease_until` are persisted epoch millis and the wall clock can move
        // backwards. Refusing the claim is safe: the row keeps its deadline and the next pass, at a
        // sane clock, takes it. Accepting would let two attempts overlap.
        withV4Database { conn ->
            conn.seed("upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1, "next_retry_at" to nowMillis)

            assertEquals(0, conn.execute(
                SourceRecoverySql.CLAIM,
                conn.claimArgs(nowMillis = nowMillis - 60_000),
            ))
            assertEquals(1, conn.int("src_1", "attempt_count"))
        }
    }

    @Test
    fun `a claim is the only thing that increments the attempt counter`() {
        withV4Database { conn ->
            conn.seed("upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1, "error_code" to "TIMEOUT")
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))
            assertEquals(2, conn.int("src_1", "attempt_count"))

            // Recovery of an orphan is not an attempt (§7.2 item 5): a crash loop must not be able to
            // spend the student's budget without ever uploading.
            conn.execute(SourceRecoverySql.REQUEST_INTERRUPT,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis, "now" to now))
            assertEquals(1, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_1", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
            assertEquals("reclaiming an orphan spent budget", 2, conn.int("src_1", "attempt_count"))
        }
    }

    // ---- §5.4 rule 1: the (token, owner) fence -------------------------------------------------

    @Test
    fun `a renewal needs the token and the owner together, not either alone`() {
        withV4Database { conn ->
            conn.seed()
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            assertEquals("a stale token renewed a live attempt", 0,
                conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs(token = "tok_0")))
            assertEquals("a different process renewed somebody else's attempt", 0,
                conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs(ownerId = "owner_2")))
            assertEquals(1, conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs()))
            assertEquals(nowMillis + 150_000, conn.row().getValue("lease_until"))
            assertEquals(nowMillis + 30_000, conn.row().getValue("lease_heartbeat_at"))
        }
    }

    @Test
    fun `the progress flush carries the renewal guard and moves only the progress timestamp`() {
        withV4Database { conn ->
            conn.seed()
            conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs())

            val args = conn.ownershipArgs() + ("progressAt" to (nowMillis + 5_000))
            assertEquals(0, conn.execute(SourceRecoverySql.RECORD_PROGRESS, args + ("token" to "tok_0")))
            assertEquals(1, conn.execute(SourceRecoverySql.RECORD_PROGRESS, args))
            assertEquals(nowMillis + 5_000, conn.row().getValue("last_progress_at"))
            assertEquals(nowMillis + 30_000, conn.row().getValue("lease_heartbeat_at"))
        }
    }

    @Test
    fun `a stand-down request is the only channel through which a live owner learns to stop`() {
        // §3.2 condition 4 with the 「不得回收」 case attached: the recovery side writes nothing but
        // `interrupt_requested_at`, the row keeps its state, token and owner so no second PUT can
        // start, and the owner's next renewal returns 0 — which is how it finds out.
        withV4Database { conn ->
            conn.seed()
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            assertEquals(1, conn.execute(SourceRecoverySql.REQUEST_INTERRUPT,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis, "now" to now)))
            assertEquals("a stand-down request must not release the row", 0,
                conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs()))
            assertEquals("a stand-down request must not release the row", 0,
                conn.execute(SourceRecoverySql.RECORD_PROGRESS,
                    conn.ownershipArgs() + ("progressAt" to nowMillis)))

            val row = conn.row()
            assertEquals("UPLOADING", row.getValue("upload_state"))
            assertEquals(token, row.getValue("attempt_token"))
            assertEquals(owner, row.getValue("lease_owner_id"))
            assertEquals(nowMillis, row.getValue("interrupt_requested_at"))

            // Idempotent: a second pass must not overwrite the first request's timestamp.
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEST_INTERRUPT,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis + 1, "now" to now)))
            assertEquals(nowMillis, conn.row().getValue("interrupt_requested_at"))

            // And it is UPLOADING-only.
            conn.seed("source_id" to "src_failed", "upload_state" to "FAILED", "retryable" to 1)
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEST_INTERRUPT,
                mapOf("sourceId" to "src_failed", "nowMillis" to nowMillis, "now" to now)))
        }
    }

    @Test
    fun `a late commit from a superseded attempt is refused on both halves of the fence`() {
        withV4Database { conn ->
            conn.seed()
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            // Half 1: the token. A reclaim issues a fresh token, so the dead attempt's no longer matches.
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_1", "token" to "tok_0", "ownerId" to owner,
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )))
            // Half 2: the owner. A reclaimed attempt gets a fresh owner id too, so even a token that
            // somehow survived cannot land — this is what makes the pair, not the token, the fence.
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to "owner_dead",
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )))
            assertEquals("a refused commit must not promote the row", "UPLOADING",
                conn.row().getValue("upload_state"))
            assertEquals(0, conn.query(SourceRecoverySql.COUNT_OWNED_ATTEMPT,
                conn.ownershipArgs(token = "tok_0")).single().values.first().let { (it as Number).toInt() })
            assertEquals(1, conn.query(SourceRecoverySql.COUNT_OWNED_ATTEMPT, conn.ownershipArgs())
                .single().values.first().let { (it as Number).toInt() })

            // The rightful attempt does land, and clears every lease column it wrote.
            assertEquals(1, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to owner,
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 4096L, "canonicalType" to "PDF", "now" to now,
            )))
            val row = conn.row()
            assertEquals("UPLOADED", row.getValue("upload_state"))
            assertEquals(4096L, conn.long("src_1", "size_bytes"))
            listOf("attempt_token", "lease_owner_id", "lease_until", "lease_heartbeat_at",
                "upload_started_at", "last_progress_at", "interrupt_requested_at").forEach {
                assertNull("terminal success left `$it` behind", row.getValue(it))
            }
            // C3: the reserved column is never written by any client statement.
            assertNull(row.getValue("remote_generation"))
        }
    }

    @Test
    fun `a failure write is fenced the same way and can carry the object it already uploaded`() {
        withV4Database { conn ->
            conn.seed()
            conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs())

            val args = mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to owner,
                "state" to "FAILED", "errorCode" to "MANIFEST_PUBLISH_FAILED",
                "errorMessage" to "文件已上传，但资料清单更新失败，请重试", "retryable" to 1,
                "nextRetryAtMillis" to (nowMillis + 10_000),
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 4096L, "canonicalType" to "PDF", "now" to now,
            )
            assertEquals(0, conn.execute(SourceRecoverySql.RECORD_FAILURE, args + ("token" to "tok_0")))
            assertEquals(1, conn.execute(SourceRecoverySql.RECORD_FAILURE, args))

            val row = conn.row()
            assertEquals("FAILED", row.getValue("upload_state"))
            // Kept, so the retry overwrites one object rather than creating a second one.
            assertEquals("superstudent/v1/x.pdf", row.getValue("drive_path"))
            assertEquals(4096L, conn.long("src_1", "size_bytes"))
            assertNull(row.getValue("attempt_token"))
            assertNull(row.getValue("lease_owner_id"))

            // And `COALESCE` really does keep the stored value when the caller has none.
            conn.seed("source_id" to "src_2", "upload_state" to "PENDING")
            conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs(sourceId = "src_2", token = "tok_2"))
            assertEquals(1, conn.execute(SourceRecoverySql.RECORD_FAILURE, mapOf(
                "sourceId" to "src_2", "token" to "tok_2", "ownerId" to owner,
                "state" to "FAILED", "errorCode" to "TIMEOUT",
                "errorMessage" to "上传超时，请检查网络后重试", "retryable" to 1,
                "nextRetryAtMillis" to (nowMillis + 10_000),
                "drivePath" to null, "sha256" to null, "sizeBytes" to null,
                "canonicalType" to null, "now" to now,
            )))
            assertEquals(2048L, conn.long("src_2", "size_bytes"))
        }
    }

    // ---- C5: the client-side fencing tests this batch must run ----------------------------------

    /**
     * The three tests below carry the identifiers ZLQ-136 C5 names verbatim, which is why they break
     * this file's backtick-prose convention: whoever checks the contract has to grep its name and find
     * it. C5 also fixes what their result may be *called* — 「本地 fencing 闭合（AC-5a）」, never
     * 「AC-5 通过」 — because the server half (`remote_generation`, manifest revision CAS, the durable
     * backend tombstone) is C1 out of scope for this batch and is reported 「不可执行」.
     *
     * "Generation" in these names is therefore the only generation this build has: the
     * `(attempt_token, lease_owner_id)` pair, both halves of which a reclaim replaces. C3 keeps
     * `remote_generation` NULL, and nothing below writes it.
     */
    @Test
    fun lateGenerationCannotCommitAfterReclaim() {
        withV4Database { conn ->
            conn.seed()
            // Attempt 1 claims; its process dies with the lease expired.
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))
            assertEquals(1, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_1", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
            // The recovery pass hands the row to attempt 2, which gets a fresh token *and* owner.
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM,
                conn.claimArgs(token = "tok_2", ownerId = "owner_2")))
            assertEquals(2, conn.int("src_1", "attempt_count"))

            // Attempt 1's PUT finishes anyway and tries to land. Both writes it could make are refused,
            // and each is refused by *either* half of the pair alone — its token is gone, and so is the
            // owner id that held it.
            val object1 = mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to owner,
                "drivePath" to "superstudent/v1/late.pdf", "sha256" to "b".repeat(64),
                "sizeBytes" to 9_999L, "canonicalType" to "PDF", "now" to now,
            )
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, object1))
            assertEquals(0, conn.execute(SourceRecoverySql.RECORD_FAILURE, object1 + mapOf(
                "state" to "FAILED", "errorCode" to "TIMEOUT",
                "errorMessage" to "上传超时，请检查网络后重试", "retryable" to 1,
                "nextRetryAtMillis" to (nowMillis + 10_000),
            )))
            assertEquals(0, conn.ownedCount(token = token, ownerId = owner))
            assertEquals(1, conn.ownedCount(token = "tok_2", ownerId = "owner_2"))
            assertEquals("a refused late commit promoted the row", "UPLOADING",
                conn.row().getValue("upload_state"))

            // Attempt 2's own commit lands, and it is attempt 2's object that the row records.
            assertEquals(1, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_1", "token" to "tok_2", "ownerId" to "owner_2",
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )))
            val row = conn.row()
            assertEquals("UPLOADED", row.getValue("upload_state"))
            assertEquals("superstudent/v1/x.pdf", row.getValue("drive_path"))
            assertEquals(2048L, conn.long("src_1", "size_bytes"))
            assertNull(row.getValue("remote_generation"))
        }
    }

    @Test
    fun concurrentRecoveryClaimsExactlyOnce() {
        withV4Database { conn ->
            conn.seed("upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1)

            // Eight recovery passes, each with its own identity, all reach the same released row.
            // Exclusivity is a property of the statement rather than of the scheduler: `CLAIM` has no
            // `UPLOADING` branch, so the moment one lands the predicate is false for every other, and
            // SQLite serialises the writes. What a JVM test cannot add evidence to is the *process*-level
            // half of the same race — that is `ProcessLeaseRegistryTest`'s
            // `two recovery probes of one dead owner produce exactly one winner`, on real OS locks.
            val wins = (1..8).count { i ->
                conn.execute(
                    SourceRecoverySql.CLAIM,
                    conn.claimArgs(token = "tok_$i", ownerId = "owner_$i"),
                ) == 1
            }

            assertEquals(1, wins)
            assertEquals("tok_1", conn.row().getValue("attempt_token"))
            assertEquals("owner_1", conn.row().getValue("lease_owner_id"))
            // The real content of "exactly once": seven refused claims incremented nothing.
            assertEquals(2, conn.int("src_1", "attempt_count"))
        }
    }

    @Test
    fun deleteTombstoneFencesInFlightCommit() {
        withV4Database { conn ->
            conn.seed()
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            // 删除 is pressed while the PUT is still in the air.
            assertEquals(1, conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis, "now" to now)))

            // The PUT completes. Neither write it could make may touch the row...
            val inFlight = mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to owner,
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, inFlight))
            assertEquals(0, conn.execute(SourceRecoverySql.RECORD_FAILURE, inFlight + mapOf(
                "state" to "FAILED", "errorCode" to "TIMEOUT",
                "errorMessage" to "上传超时，请检查网络后重试", "retryable" to 1,
                "nextRetryAtMillis" to (nowMillis + 10_000),
            )))
            assertEquals(0, conn.ownedCount(token = token, ownerId = owner))
            // ...nor may the attempt keep its lease alive...
            assertEquals(0, conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs()))
            assertEquals(0, conn.execute(SourceRecoverySql.RECORD_PROGRESS,
                conn.ownershipArgs() + ("progressAt" to nowMillis)))
            // ...and there is no route back to an upload at all. This is the half that makes the fence
            // terminal rather than a momentary refusal: without it the row would sit tombstoned and
            // `UPLOADING`, and any of these would resurrect a file the student just deleted.
            assertEquals(0, conn.execute(SourceRecoverySql.CLAIM,
                conn.claimArgs(token = "tok_2", ownerId = "owner_2")))
            assertEquals(0, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_1", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEUE_FOR_RETRY,
                mapOf("sourceId" to "src_1", "now" to now)))
            assertEquals(0, conn.execute(SourceRecoverySql.BIND_LOCAL_FILE, mapOf(
                "sourceId" to "src_1", "localUri" to "content://downloads/new",
                "localAccessMode" to "APP_COPY", "sizeBytes" to null, "state" to "PENDING",
                "errorCode" to null, "errorMessage" to null, "retryable" to 1, "now" to now,
            )))
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_LOCAL_ONLY, mapOf(
                "sourceId" to "src_1", "localAccessMode" to "NONE",
                "errorCode" to "URI_PERMISSION_REQUIRED", "errorMessage" to "x", "now" to now,
            )))

            // `delete_pending = 0` is an independent guard, not a side effect of `BEGIN_DELETE` having
            // cleared the token: a tombstoned row whose token somehow survived is still refused.
            conn.seed("source_id" to "src_kept", "upload_state" to "UPLOADING",
                "attempt_token" to "tok_9", "lease_owner_id" to "owner_9")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_kept", "nowMillis" to nowMillis, "now" to now))
            conn.createStatement().use { st ->
                st.execute("UPDATE source_asset SET attempt_token = 'tok_9' WHERE source_id = 'src_kept'")
            }
            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_kept", "token" to "tok_9", "ownerId" to "owner_9",
                "drivePath" to "superstudent/v1/y.pdf", "sha256" to "c".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )))

            // The row survives, unchanged apart from the tombstone, until the delete worker's last
            // step — which is what leaves 待清理 / 重试删除 something to render (§3.9 item 6).
            assertEquals("UPLOADING", conn.row().getValue("upload_state"))
            assertEquals(1, conn.row().getValue("delete_pending"))
            assertEquals(1, conn.execute(SourceRecoverySql.FINISH_DELETE,
                mapOf("sourceId" to "src_1")))
        }
    }

    // ---- §3.2: releasing an orphan -------------------------------------------------------------

    @Test
    fun `releasing an orphan lands it inside budget, and exhausted once the budget is gone`() {
        withV4Database { conn ->
            conn.seed("upload_state" to "UPLOADING", "attempt_token" to "tok_old", "lease_owner_id" to "owner_dead",
                "lease_until" to nowMillis - 1, "attempt_count" to 2)

            assertEquals(1, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_1", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
            val row = conn.row()
            assertEquals("FAILED", row.getValue("upload_state"))
            assertEquals("PROCESS_INTERRUPTED", row.getValue("error_code"))
            // The matrix copy for this code, verbatim — the stored text and the rendered text are the
            // same string, which is what keeps them from drifting apart.
            assertEquals("上传中断，请重试", row.getValue("error_message"))
            assertEquals(1, row.getValue("retryable"))
            assertEquals(nowMillis, row.getValue("next_retry_at"))
            assertEquals("recovery is not an attempt", 2, conn.int("src_1", "attempt_count"))
            listOf("attempt_token", "lease_owner_id", "lease_until", "lease_heartbeat_at",
                "upload_started_at", "last_progress_at", "interrupt_requested_at").forEach {
                assertNull("release left `$it` behind", row.getValue(it))
            }

            // Idempotent, and terminal: a second pass has nothing to release.
            assertEquals(0, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_1", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))

            // At the cap the row becomes non-retryable but keeps its entries: R3 forbids a dead end.
            conn.seed("source_id" to "src_spent", "upload_state" to "UPLOADING", "attempt_token" to "tok_old",
                "lease_owner_id" to "owner_dead", "attempt_count" to SOURCE_MAX_ATTEMPTS)
            assertEquals(1, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_spent", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
            assertEquals(0, conn.row("src_spent").getValue("retryable"))
            assertNull(conn.row("src_spent").getValue("next_retry_at"))

            // A tombstoned row is the delete worker's, not the recovery pass's.
            conn.seed("source_id" to "src_tomb", "upload_state" to "UPLOADING", "attempt_token" to "tok_old")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now))
            assertEquals(0, conn.execute(SourceRecoverySql.INTERRUPT_ATTEMPT, mapOf(
                "sourceId" to "src_tomb", "nowMillis" to nowMillis,
                "maxAttempts" to SOURCE_MAX_ATTEMPTS, "now" to now,
            )))
        }
    }

    // ---- §3.9: the delete tombstone ------------------------------------------------------------

    @Test
    fun `arming the tombstone fences a PUT that is already in the air`() {
        withV4Database { conn ->
            conn.seed()
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))

            assertEquals(1, conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis, "now" to now)))
            val row = conn.row()
            assertEquals(1, row.getValue("delete_pending"))
            assertEquals(nowMillis, row.getValue("delete_requested_at"))
            assertEquals(nowMillis, row.getValue("interrupt_requested_at"))
            // The token is cleared on purpose: that, and `delete_pending = 0` in every business guard,
            // is what makes the in-flight `markUploaded` below return 0 instead of resurrecting a row
            // the student just deleted.
            assertNull(row.getValue("attempt_token"))

            assertEquals(0, conn.execute(SourceRecoverySql.MARK_UPLOADED, mapOf(
                "sourceId" to "src_1", "token" to token, "ownerId" to owner,
                "drivePath" to "superstudent/v1/x.pdf", "sha256" to "a".repeat(64),
                "sizeBytes" to 2048L, "canonicalType" to "PDF", "now" to now,
            )))
            assertEquals("a tombstoned row was promoted to UPLOADED", 1,
                conn.row().getValue("delete_pending"))
            assertEquals(0, conn.execute(SourceRecoverySql.RENEW_LEASE, conn.ownershipArgs()))
            assertEquals(0, conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_1", "nowMillis" to nowMillis + 1, "now" to now)))
            assertEquals("a second 删除 armed a second tombstone", nowMillis,
                conn.row().getValue("delete_requested_at"))

            // The row survives until the worker's last step, so 待清理 / 重试删除 has something to show.
            assertEquals(1, conn.query("SELECT COUNT(*) AS n FROM source_asset").single()
                .getValue("n").let { (it as Number).toInt() })
            assertEquals(1, conn.execute(SourceRecoverySql.FINISH_DELETE,
                mapOf("sourceId" to "src_1")))
            assertEquals(0, conn.query("SELECT COUNT(*) AS n FROM source_asset").single()
                .getValue("n").let { (it as Number).toInt() })
        }
    }

    @Test
    fun `only a tombstoned row can disappear`() {
        withV4Database { conn ->
            conn.seed()
            assertEquals(0, conn.execute(SourceRecoverySql.FINISH_DELETE, mapOf("sourceId" to "src_1")))
            assertEquals(1, conn.query("SELECT COUNT(*) AS n FROM source_asset").single()
                .getValue("n").let { (it as Number).toInt() })
        }
    }

    // ---- §7.2 item 5: the manual entries reset the cycle ---------------------------------------

    @Test
    fun `a manual retry resets the budget and only ever takes a FAILED row`() {
        withV4Database { conn ->
            conn.seed("upload_state" to "FAILED", "retryable" to 0, "attempt_count" to SOURCE_MAX_ATTEMPTS,
                "error_code" to "TIMEOUT", "error_message" to "上传多次超时，请检查网络后重试",
                "next_retry_at" to null)

            assertEquals(1, conn.execute(SourceRecoverySql.REQUEUE_FOR_RETRY,
                mapOf("sourceId" to "src_1", "now" to now)))
            val row = conn.row()
            assertEquals("PENDING", row.getValue("upload_state"))
            assertEquals("a manual retry must hand back a full budget", 0,
                conn.int("src_1", "attempt_count"))
            assertEquals(1, row.getValue("retryable"))
            assertNull(row.getValue("error_code"))
            assertNull(row.getValue("error_message"))
            assertNull(row.getValue("next_retry_at"))
            // And the reset row is claimable again immediately.
            assertEquals(1, conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs()))
            assertEquals(1, conn.int("src_1", "attempt_count"))

            // An interrupted UPLOADING row is *not* requeued here: it goes through the owner-lock
            // release first, so tapping 重试 can never start a second PUT under a live owner.
            conn.seed("source_id" to "src_up", "upload_state" to "UPLOADING", "attempt_token" to "tok_live",
                "lease_owner_id" to "owner_live")
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEUE_FOR_RETRY,
                mapOf("sourceId" to "src_up", "now" to now)))
            assertEquals("UPLOADING", conn.row("src_up").getValue("upload_state"))
            assertEquals("tok_live", conn.row("src_up").getValue("attempt_token"))

            conn.seed("source_id" to "src_done", "upload_state" to "UPLOADED")
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEUE_FOR_RETRY,
                mapOf("sourceId" to "src_done", "now" to now)))

            conn.seed("source_id" to "src_tomb", "upload_state" to "FAILED", "retryable" to 1)
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now))
            assertEquals(0, conn.execute(SourceRecoverySql.REQUEUE_FOR_RETRY,
                mapOf("sourceId" to "src_tomb", "now" to now)))
        }
    }

    @Test
    fun `re-selecting a file writes its size in the same statement and cannot touch an upload`() {
        withV4Database { conn ->
            conn.seed("upload_state" to "FAILED", "error_code" to "FILE_TOO_LARGE", "attempt_count" to 3,
                "size_bytes" to 60_000_000L)

            val args = mapOf(
                "sourceId" to "src_1", "localUri" to "content://downloads/new",
                "localAccessMode" to "APP_COPY", "sizeBytes" to 4_000_000L, "state" to "PENDING",
                "errorCode" to null, "errorMessage" to null, "retryable" to 1, "now" to now,
            )
            assertEquals(1, conn.execute(SourceRecoverySql.BIND_LOCAL_FILE, args))
            val row = conn.row()
            assertEquals("content://downloads/new", row.getValue("local_uri"))
            assertEquals("APP_COPY", row.getValue("local_access_mode"))
            assertEquals(4_000_000L, conn.long("src_1", "size_bytes"))
            assertEquals("PENDING", row.getValue("upload_state"))
            assertEquals(0, conn.int("src_1", "attempt_count"))
            assertNull(row.getValue("error_code"))

            // A cancelled picker passes no size and must not zero the stored one — that is why the
            // column is COALESCEd here rather than written by a read-then-upsert in the repository.
            conn.seed("source_id" to "src_2", "upload_state" to "LOCAL_ONLY", "error_code" to "READ_FAILED",
                "size_bytes" to 2048L)
            assertEquals(1, conn.execute(SourceRecoverySql.BIND_LOCAL_FILE,
                args + ("sourceId" to "src_2") + ("sizeBytes" to null)))
            assertEquals(2048L, conn.long("src_2", "size_bytes"))

            conn.seed("source_id" to "src_done", "upload_state" to "UPLOADED")
            assertEquals(0, conn.execute(SourceRecoverySql.BIND_LOCAL_FILE,
                args + ("sourceId" to "src_done")))
        }
    }

    @Test
    fun `an unreadable file becomes LOCAL_ONLY and clears the lease it never finished`() {
        withV4Database { conn ->
            conn.seed()
            conn.execute(SourceRecoverySql.CLAIM, conn.claimArgs())

            assertEquals(1, conn.execute(SourceRecoverySql.MARK_LOCAL_ONLY, mapOf(
                "sourceId" to "src_1", "localAccessMode" to "NONE",
                "errorCode" to "URI_PERMISSION_REQUIRED",
                "errorMessage" to "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
                "now" to now,
            )))
            val row = conn.row()
            assertEquals("LOCAL_ONLY", row.getValue("upload_state"))
            assertEquals("NONE", row.getValue("local_access_mode"))
            assertEquals(0, row.getValue("retryable"))
            assertNull(row.getValue("attempt_token"))
            assertNull(row.getValue("lease_owner_id"))

            conn.seed("source_id" to "src_done", "upload_state" to "UPLOADED")
            assertEquals("a finished upload was demoted to LOCAL_ONLY", 0,
                conn.execute(SourceRecoverySql.MARK_LOCAL_ONLY, mapOf(
                    "sourceId" to "src_done", "localAccessMode" to "NONE",
                    "errorCode" to "READ_FAILED", "errorMessage" to "x", "now" to now,
                )))
        }
    }

    // ---- the scans ------------------------------------------------------------------------------

    @Test
    fun `the recovery scan finds tombstones, dead leases and due work, and nothing else`() {
        withV4Database { conn ->
            conn.seed("source_id" to "src_tomb", "upload_state" to "UPLOADED")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now))
            // A v3 orphan: UPLOADING with no owner column at all, which is §3.2 condition 1.
            conn.seed("source_id" to "src_v3", "upload_state" to "UPLOADING", "attempt_token" to "tok_v3")
            conn.seed("source_id" to "src_expired", "upload_state" to "UPLOADING", "attempt_token" to "tok_e",
                "lease_owner_id" to "owner_dead", "lease_until" to nowMillis - 1)
            conn.seed("source_id" to "src_live", "upload_state" to "UPLOADING", "attempt_token" to "tok_l",
                "lease_owner_id" to "owner_live", "lease_until" to nowMillis + 60_000,
                "last_progress_at" to nowMillis - 1)
            // §3.2 condition 4: the heartbeat still renews, so the lease is live, but no byte has
            // moved for the whole window. Without this branch that row is invisible to every scan.
            conn.seed("source_id" to "src_stuck", "upload_state" to "UPLOADING", "attempt_token" to "tok_s",
                "lease_owner_id" to "owner_live", "lease_until" to nowMillis + 60_000,
                "last_progress_at" to nowMillis - SOURCE_NO_PROGRESS_MILLIS - 1)
            conn.seed("source_id" to "src_pending", "upload_state" to "PENDING")
            conn.seed("source_id" to "src_due", "upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1,
                "next_retry_at" to nowMillis - 1)
            conn.seed("source_id" to "src_later", "upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1,
                "next_retry_at" to nowMillis + 60_000)
            conn.seed("source_id" to "src_spent", "upload_state" to "FAILED", "retryable" to 1,
                "attempt_count" to SOURCE_MAX_ATTEMPTS)
            conn.seed("source_id" to "src_perm", "upload_state" to "FAILED", "retryable" to 0,
                "error_code" to "FILE_TOO_LARGE")
            conn.seed("source_id" to "src_done", "upload_state" to "UPLOADED")

            val found = conn.query(SourceRecoverySql.LIST_RECOVERY_CANDIDATES,
                mapOf("nowMillis" to nowMillis, "maxAttempts" to SOURCE_MAX_ATTEMPTS,
                    "noProgressBefore" to nowMillis - SOURCE_NO_PROGRESS_MILLIS))
                .map { it.getValue("source_id") }
            assertEquals(
                "ordered by added_at, which is the order the coordinator works them in",
                listOf("src_tomb", "src_v3", "src_expired", "src_stuck", "src_pending", "src_due"),
                found,
            )
        }
    }

    @Test
    fun `the resumable scan carries no UPLOADING branch and skips tombstones`() {
        withV4Database { conn ->
            conn.seed("source_id" to "src_pending", "upload_state" to "PENDING")
            conn.seed("source_id" to "src_expired", "upload_state" to "UPLOADING", "lease_until" to nowMillis - 1)
            conn.seed("source_id" to "src_due", "upload_state" to "FAILED", "retryable" to 1,
                "next_retry_at" to nowMillis - 1)
            conn.seed("source_id" to "src_tomb", "upload_state" to "PENDING")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now))

            assertEquals(
                listOf("src_pending", "src_due"),
                conn.query(SourceRecoverySql.LIST_RESUMABLE,
                    mapOf("nowMillis" to nowMillis, "maxAttempts" to SOURCE_MAX_ATTEMPTS))
                    .map { it.getValue("source_id") },
            )
        }
    }

    @Test
    fun `the follow-up deadline is the earliest of the three families, and never in the past`() {
        withV4Database { conn ->
            val deadline = {
                conn.query(SourceRecoverySql.NEXT_RECOVERY_DEADLINE,
                    mapOf("maxAttempts" to SOURCE_MAX_ATTEMPTS, "nowMillis" to nowMillis))
                    .single().values.first()
            }
            // Nothing actionable at all → no deadline, so no follow-up is armed.
            assertNull(deadline())

            conn.seed("source_id" to "src_live", "upload_state" to "UPLOADING", "lease_owner_id" to "owner_live",
                "lease_until" to nowMillis + 90_000)
            conn.seed("source_id" to "src_v3", "upload_state" to "UPLOADING")
            conn.seed("source_id" to "src_retry", "upload_state" to "FAILED", "retryable" to 1, "attempt_count" to 1,
                "next_retry_at" to nowMillis + 30_000)
            conn.seed("source_id" to "src_tomb", "upload_state" to "UPLOADED")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis + 120_000, "now" to now))
            // Out of budget, so its deadline must not arm a pass that could not act on it.
            conn.seed("source_id" to "src_spent", "upload_state" to "FAILED", "retryable" to 1,
                "attempt_count" to SOURCE_MAX_ATTEMPTS, "next_retry_at" to nowMillis + 1_000)

            // The v3 orphan has no `lease_until` and contributes nothing on purpose: this pass already
            // claims it, and a deadline in the past would arm a follow-up that fires immediately and
            // re-arms itself forever.
            assertEquals(nowMillis + 30_000, deadline())

            // The same, for a deadline that *exists* but has already passed. This is the case a live
            // owner produces: §3.2 「不得回收」 leaves the row `UPLOADING` with an expired lease and only
            // an `interrupt_requested_at` written, so nothing this pass does moves its `lease_until`.
            // Reporting it would arm a 0 ms follow-up that re-arms itself until that process dies.
            conn.seed("source_id" to "src_wedged", "upload_state" to "UPLOADING",
                "lease_owner_id" to "owner_live", "lease_until" to nowMillis - 1,
                "interrupt_requested_at" to nowMillis)
            assertEquals("an expired lease must not become the deadline", nowMillis + 30_000, deadline())

            // And a retry that fell due while this pass was running is this pass's business, not a
            // follow-up's.
            conn.seed("source_id" to "src_due_now", "upload_state" to "FAILED", "retryable" to 1,
                "attempt_count" to 1, "next_retry_at" to nowMillis - 5_000)
            assertEquals(nowMillis + 30_000, deadline())

            // Once every future deadline has passed, nothing is armed at all rather than armed at 0.
            // The tombstone is settled first so the assertion is about the two stale families and not
            // about a delete that is genuinely still owed.
            conn.createStatement().use { st ->
                st.execute(
                    "UPDATE source_asset SET lease_until = $nowMillis - 1, next_retry_at = NULL," +
                        " delete_pending = 0"
                )
            }
            assertNull(deadline())
        }
    }

    @Test
    fun `the exhausted-transient scan keeps permanent codes and manifest failures out`() {
        withV4Database { conn ->
            listOf("NETWORK_UNAVAILABLE", "TIMEOUT", "SERVER_BUSY", "PRESIGNED_URL_EXPIRED",
                "PROCESS_INTERRUPTED", "UNKNOWN").forEachIndexed { i, code ->
                conn.seed("source_id" to "src_t$i", "upload_state" to "FAILED", "retryable" to 0, "error_code" to code)
            }
            // Believed permanent: re-probing these into "re-select the file" is ZLQ-105's defect.
            listOf("FILE_TOO_LARGE", "UNSUPPORTED_FORMAT", "HASH_MISMATCH", "AUTH_EXPIRED",
                "ACCESS_DENIED", "NOT_FOUND", "READ_FAILED").forEachIndexed { i, code ->
                conn.seed("source_id" to "src_p$i", "upload_state" to "FAILED", "retryable" to 0, "error_code" to code)
            }
            // Its object is already on Drive, so it belongs to the manifest reconciliation.
            conn.seed("source_id" to "src_m", "upload_state" to "FAILED", "retryable" to 0,
                "error_code" to "MANIFEST_PUBLISH_FAILED")
            // Still inside budget → the scheduler owns it, not this scan.
            conn.seed("source_id" to "src_r", "upload_state" to "FAILED", "retryable" to 1,
                "error_code" to "TIMEOUT")
            conn.seed("source_id" to "src_tomb", "upload_state" to "FAILED", "retryable" to 0,
                "error_code" to "TIMEOUT")
            conn.execute(SourceRecoverySql.BEGIN_DELETE,
                mapOf("sourceId" to "src_tomb", "nowMillis" to nowMillis, "now" to now))

            assertEquals(
                listOf("src_t0", "src_t1", "src_t2", "src_t3", "src_t4", "src_t5"),
                conn.query(SourceRecoverySql.LIST_EXHAUSTED_TRANSIENT_FAILURES)
                    .map { it.getValue("source_id") },
            )
        }
    }

    // ---- §5.4 rules 1 and 2, and C3, as structural gates over the shipped text -----------------

    /**
     * The columns a statement's SET clause assigns, parsed line by line.
     *
     * Splitting the clause on commas instead would be wrong here: `RECORD_FAILURE` assigns
     * `COALESCE(:drivePath, drive_path)` and `INTERRUPT_ATTEMPT` assigns a `CASE … END`, both of
     * which contain commas that belong to a value rather than separating two assignments. Every
     * shipped statement puts one assignment on one line, so the line boundary is the real separator.
     */
    private fun setColumns(sql: String): Set<String> =
        sql.substringAfter("SET ", "")
            .lineSequence()
            .takeWhile { !it.trim().startsWith("WHERE") }
            .mapNotNull { Regex("^\\s*`?(\\w+)`?\\s*=").find(it)?.groupValues?.get(1) }
            .toSet()

    @Test
    fun `ALL still covers every statement this object ships`() {
        // Without this the gates below could pass while a newly added constant escaped them.
        val declared = SourceRecoverySql::class.java.fields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(declared, SourceRecoverySql.ALL.keys)
    }

    @Test
    fun `every business write carries the tombstone guard`() {
        val updates = SourceRecoverySql.ALL.filter { it.value.trimStart().startsWith("UPDATE source_asset") }
        assertEquals(
            "the gate below only means something if it found every UPDATE",
            setOf(
                "CLAIM", "RENEW_LEASE", "RECORD_PROGRESS", "MARK_UPLOADED", "RECORD_FAILURE",
                "MARK_LOCAL_ONLY", "REQUEST_INTERRUPT", "INTERRUPT_ATTEMPT", "BEGIN_DELETE",
                "REQUEUE_FOR_RETRY", "BIND_LOCAL_FILE",
            ),
            updates.keys,
        )
        updates.forEach { (name, sql) ->
            assertTrue(
                "$name writes a business state without `delete_pending = 0`, so it could resurrect " +
                    "a row the student deleted",
                sql.substringAfter("WHERE").contains("delete_pending = 0"),
            )
        }
        assertTrue(
            "FINISH_DELETE must only remove a tombstoned row",
            SourceRecoverySql.FINISH_DELETE.contains("delete_pending = 1"),
        )
    }

    @Test
    fun `every ownership write matches source, token and owner together`() {
        // Substituting the token for the owner — or either for the other — is how a dead process's
        // late PUT used to be able to commit over a live one (§5.4 rule 1).
        listOf(
            SourceRecoverySql.RENEW_LEASE,
            SourceRecoverySql.RECORD_PROGRESS,
            SourceRecoverySql.MARK_UPLOADED,
            SourceRecoverySql.RECORD_FAILURE,
            SourceRecoverySql.COUNT_OWNED_ATTEMPT,
        ).forEach { sql ->
            listOf("source_id = :sourceId", "attempt_token = :token", "lease_owner_id = :ownerId")
                .forEach { guard -> assertTrue("missing `$guard`", sql.contains(guard)) }
        }
    }

    @Test
    fun `the claim has no UPLOADING branch to take`() {
        val where = SourceRecoverySql.CLAIM.substringAfter("WHERE")
        assertFalse(
            "CLAIM grew an UPLOADING branch, which is the double-PUT this design removes",
            where.contains("upload_state = 'UPLOADING'"),
        )
        assertFalse(
            "LIST_RESUMABLE grew an UPLOADING branch",
            SourceRecoverySql.LIST_RESUMABLE.substringAfter("WHERE")
                .contains("upload_state = 'UPLOADING'"),
        )
    }

    @Test
    fun `no shipped statement writes the reserved generation column`() {
        // C3 / reverse gate 5: `remote_generation` exists in v4 and stays NULL in this batch. A
        // locally invented value would later be read as authoritative by the backend.
        SourceRecoverySql.ALL.forEach { (name, sql) ->
            assertFalse("$name writes remote_generation", "remote_generation" in setColumns(sql))
            assertFalse("$name mentions remote_generation at all", sql.contains("remote_generation"))
        }
    }

    @Test
    fun `every column the shipped statements write exists in v4`() {
        val declared = schema(4).getValue("entities").jsonArray.map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == "source_asset" }
            .getValue("fields").jsonArray.map { it.jsonObject }
            .map { it.getValue("columnName").jsonPrimitive.content }
            .toSet()
        SourceRecoverySql.ALL.forEach { (name, sql) ->
            setColumns(sql).forEach {
                assertTrue("$name writes `$it`, which v4 does not have", it in declared)
            }
        }
        assertTrue(
            "the parser found nothing, so the gate above proved nothing",
            "lease_owner_id" in setColumns(SourceRecoverySql.CLAIM),
        )
    }
}
