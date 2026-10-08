package com.superstudent.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The offline-cancel SQL, executed on the JVM against real SQLite built from the exported v3 schema
 * (ZLQ-114 §8.1 items 1, 2, 3, 6, 10 and the SQL half of 12).
 *
 * The statements under test are the shipped [TaskRunRecoverySql] constants, so what is proven here is
 * the part a fake DAO cannot show: that §5.2's two writes really do land together in one transaction,
 * that the package reset's four guards really do refuse a reset a newer attempt or another live run
 * has invalidated, and that a cold start converges a `CANCELED + GENERATING` mismatch to `READY` in
 * the database rather than merely signalling that it tried.
 *
 * The harness mirrors `TaskRunRecoverySqlTest`'s on purpose: that file pins accepted ZLQ-103
 * behaviour and this fix must not require re-reviewing it, so the scaffolding is repeated rather than
 * extracted from under it.
 *
 * No migration is involved, and that claim is guarded the same way as in ZLQ-103 — every statement
 * runs against the unmodified v3 schema, so a constant that reached for a column v3 lacks fails here.
 */
class CancelCompensationSqlTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun moduleFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.exists() }

    private fun schema(version: Int): JsonObject = json
        .parseToJsonElement(moduleFile("schemas/${SsDatabase::class.java.name}/$version.json").readText())
        .jsonObject.getValue("database").jsonObject

    private fun columns(table: String): List<String> =
        schema(3).getValue("entities").jsonArray.map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == table }
            .let { entity ->
                entity.getValue("createSql").jsonPrimitive.content.let { sql ->
                    sql.substringAfter('(').substringBeforeLast(')')
                        .split(", PRIMARY KEY")[0]
                        .split(',')
                        .map { it.trim().trim('`').substringBefore(' ').trim('`') }
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

    private fun Connection.query(sql: String, args: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
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

    private fun Connection.insert(table: String, values: Map<String, Any?>) {
        val fields = columns(table)
        assertEquals("the fixture must cover every v3 column of $table", fields.toSet(), values.keys)
        prepareStatement(
            "INSERT INTO $table (${fields.joinToString(",") { "`$it`" }}) " +
                "VALUES (${fields.joinToString(",") { "?" }})"
        ).use { ps ->
            fields.forEachIndexed { i, column ->
                when (val value = values.getValue(column)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Double -> ps.setDouble(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            assertEquals(1, ps.executeUpdate())
        }
    }

    private fun withV3Database(body: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                schema(3).getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    st.execute(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    entity["indices"]?.jsonArray?.forEach { index ->
                        st.execute(
                            index.jsonObject.getValue("createSql").jsonPrimitive.content
                                .replace("\${TABLE_NAME}", table)
                        )
                    }
                }
                st.execute("PRAGMA user_version = 3")
            }
            body(conn)
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private val owner = "idt-owner"

    private fun packageRow(
        packageId: String = "pkg_1",
        identityId: String = owner,
        status: String = "GENERATING",
        latestTaskId: String? = "task_c",
    ) = mapOf(
        "package_id" to packageId,
        "identity_id" to identityId,
        "title" to "初二数学",
        "goal" to "PREVIEW",
        "chapter_range" to null,
        "status" to status,
        "latest_task_id" to latestTaskId,
        "source_count" to 3,
        "created_at" to "2026-09-30T01:00:00Z",
        "updated_at" to "2026-09-30T01:00:00Z",
    )

    private fun runRow(
        taskId: String = "task_c",
        attempt: Int = 1,
        packageId: String = "pkg_1",
        runId: String = "req_c",
        sessionId: String? = "sess_c",
        state: String = "RUNNING",
        cleanupPending: Int = 0,
        updatedAt: String = "2026-09-30T01:00:01Z",
    ) = mapOf(
        "task_id" to taskId,
        "attempt" to attempt,
        "package_id" to packageId,
        "run_id" to runId,
        "session_id" to sessionId,
        "state" to state,
        "stage" to "OBSERVE",
        "progress" to 40,
        "resume_from_stage" to null,
        "last_event_id" to null,
        "error_code" to null,
        "error_message" to null,
        "credits" to 0.0,
        "active_seconds" to null,
        "duration_seconds" to null,
        "cleanup_pending" to cleanupPending,
        "created_at" to "2026-09-30T01:00:01Z",
        "started_at" to "2026-09-30T01:00:01Z",
        "finished_at" to null,
        "updated_at" to updatedAt,
    )

    private val now = "2026-09-30T02:00:00Z"

    private fun resetArgs(packageId: String = "pkg_1", taskId: String = "task_c", attempt: Int = 1) = mapOf(
        "packageId" to packageId,
        "taskId" to taskId,
        "attempt" to attempt,
        "now" to now,
    )

    /**
     * The terminal half of §5.2, as the upsert writes it. Room's own statement is a generated
     * `INSERT ... ON CONFLICT DO UPDATE`; what matters to the reset next to it is the four columns
     * this sets, and that both writes share one transaction.
     */
    private companion object {
        const val CONCLUDE_CANCELED = """
            UPDATE task_run
            SET state = 'CANCELED',
                cleanup_pending = 1,
                finished_at = :now,
                updated_at = :now
            WHERE task_id = :taskId AND attempt = :attempt
        """
    }

    private fun Connection.packageStatus(packageId: String = "pkg_1"): String =
        query("SELECT status FROM learning_package WHERE package_id = '$packageId'").single().getValue("status") as String

    private fun Connection.runState(taskId: String = "task_c", attempt: Int = 1): Map<String, Any?> =
        query("SELECT * FROM task_run WHERE task_id = '$taskId' AND attempt = $attempt").single()

    // ---- §8.1 #1 and #10: the atomic local convergence ----------------------------------------

    @Test
    fun `an offline cancel leaves CANCELED, the marker and READY in one transaction`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())

            // The cancel API never answered: this transaction is the only thing that ran. Both writes
            // are inside it, in §5.2's order, and the reset's own guard reads the terminal the first
            // write just made — which only works because they share a transaction.
            conn.autoCommit = false
            val concluded = conn.execute(CONCLUDE_CANCELED, resetArgs())
            val restored = conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs())
            conn.commit()

            assertEquals(1, concluded)
            assertEquals(1, restored)

            val row = conn.runState()
            assertEquals("CANCELED", row.getValue("state"))
            assertEquals(1, (row.getValue("cleanup_pending") as Number).toInt())
            assertEquals(now, row.getValue("finished_at"))
            assertEquals(now, row.getValue("updated_at"))
            assertEquals("the package must actually be READY in the database", "READY", conn.packageStatus())
            assertEquals(now, conn.query("SELECT updated_at FROM learning_package WHERE package_id = 'pkg_1'")
                .single().getValue("updated_at"))
        }
    }

    @Test
    fun `the reset depends on the terminal write that precedes it in the same transaction`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())

            // Reversed order: guard 2 (this attempt really is CANCELED) has nothing to see yet, so the
            // package would stay GENERATING forever — the defect §5.2's ordering exists to prevent.
            assertEquals(0, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("GENERATING", conn.packageStatus())

            assertEquals(1, conn.execute(CONCLUDE_CANCELED, resetArgs()))
            assertEquals(1, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("READY", conn.packageStatus())
        }
    }

    @Test
    fun `a rolled-back cancel leaves neither the terminal nor READY behind`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())

            conn.autoCommit = false
            conn.execute(CONCLUDE_CANCELED, resetArgs())
            conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs())
            conn.rollback()

            assertEquals("half-applied is worse than not applied", "GENERATING", conn.packageStatus())
            val row = conn.runState()
            assertEquals("RUNNING", row.getValue("state"))
            assertEquals(0, (row.getValue("cleanup_pending") as Number).toInt())
        }
    }

    // ---- §8.1 #3: what must not be reset -------------------------------------------------------

    @Test
    fun `a newer attempt owns the package, so the older cancel cannot reset it`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 1, updatedAt = "2026-09-30T01:10:00Z"))
            // The student pressed 重新生成 and attempt 2 has already concluded. It is terminal, so
            // guard 4 is satisfied and this isolates guard 3: the attempt is no longer the latest.
            // (A still-running attempt 2 — the usual case — trips guards 3 and 4 at once.)
            conn.insert(
                "task_run",
                runRow(attempt = 2, runId = "req_c2", sessionId = "sess_c2", state = "SUCCEEDED", updatedAt = "2026-09-30T01:20:00Z"),
            )

            assertEquals(0, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs(attempt = 1)))
            assertEquals(
                "a late cancel of attempt 1 must not undo what attempt 2 is doing",
                "GENERATING",
                conn.packageStatus(),
            )
        }
    }

    @Test
    fun `another active run of the same package blocks the reset`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 1))
            conn.insert(
                "task_run",
                runRow(taskId = "task_other", attempt = 1, runId = "req_o", sessionId = "sess_o", packageId = "pkg_1"),
            )

            assertEquals(0, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("live work would be hidden by a READY package", "GENERATING", conn.packageStatus())
        }
    }

    @Test
    fun `the four guards each refuse on their own, and the reset is a no-op once READY`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 1))

            // Guard 1: a package whose `latest_task_id` moved on to another task.
            conn.insert("learning_package", packageRow(packageId = "pkg_2", latestTaskId = "task_newer"))
            conn.insert("task_run", runRow(taskId = "task_p2", attempt = 1, runId = "req_p2", packageId = "pkg_2", state = "CANCELED"))
            assertEquals(
                0,
                conn.execute(
                    TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL,
                    resetArgs(packageId = "pkg_2", taskId = "task_p2"),
                ),
            )
            assertEquals("GENERATING", conn.packageStatus("pkg_2"))

            // Guard 2: an attempt that is not CANCELED — a late pass for a run that was resumed.
            conn.insert("learning_package", packageRow(packageId = "pkg_3", latestTaskId = "task_r"))
            conn.insert("task_run", runRow(taskId = "task_r", attempt = 1, runId = "req_r", packageId = "pkg_3", state = "RUNNING"))
            assertEquals(
                0,
                conn.execute(
                    TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL,
                    resetArgs(packageId = "pkg_3", taskId = "task_r"),
                ),
            )
            assertEquals("GENERATING", conn.packageStatus("pkg_3"))

            // The happy path, then the idempotent re-run: a second cold start must not churn.
            assertEquals(1, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("READY", conn.packageStatus())
            assertEquals(0, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("READY", conn.packageStatus())
        }
    }

    // ---- §8.1 #2 and #10: what a cold start converges, with no network -------------------------

    @Test
    fun `a cold start converges a historical mismatch to READY and re-arms its compensation`() {
        withV3Database { conn ->
            // The row a dead process left behind: terminal locally, marker never written, package stuck.
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 0))
            // Also stuck, but sessionless: its package is still reset, while the marker is not
            // backfilled — with no Session there is nothing remote to stop (§5.5, §9).
            conn.insert("learning_package", packageRow(packageId = "pkg_2", latestTaskId = "task_ns"))
            conn.insert(
                "task_run",
                runRow(
                    taskId = "task_ns", attempt = 1, runId = "req_ns", sessionId = null, packageId = "pkg_2",
                    state = "CANCELED", updatedAt = "2026-09-30T01:00:02Z",
                ),
            )
            // Not a mismatch: already converged.
            conn.insert("learning_package", packageRow(packageId = "pkg_3", latestTaskId = "task_ok", status = "READY"))
            conn.insert(
                "task_run",
                runRow(taskId = "task_ok", attempt = 1, runId = "req_ok", packageId = "pkg_3", state = "CANCELED"),
            )

            val mismatches = conn.query(TaskRunRecoverySql.LIST_CANCELED_GENERATING_MISMATCH)
            assertEquals(
                "both stuck packages are candidates, oldest first",
                listOf("task_c", "task_ns"),
                mismatches.map { it.getValue("task_id") },
            )
            mismatches.forEach {
                assertEquals(owner, it.getValue("owner_identity_id"))
                assertEquals("GENERATING", it.getValue("package_status"))
            }

            // Job 1, exactly as the rule runs it: reset, then re-arm the marker on session-bearing rows.
            assertEquals(1, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs()))
            assertEquals("the assertion §8.1 #10 asks for", "READY", conn.packageStatus())
            assertEquals(
                1,
                conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs(packageId = "pkg_2", taskId = "task_ns")),
            )
            assertEquals("READY", conn.packageStatus("pkg_2"))
            assertEquals(
                1,
                conn.execute(
                    TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING,
                    mapOf("taskId" to "task_c", "attempt" to 1, "now" to now),
                ),
            )
            assertEquals(
                "a sessionless historical cancel is not backfilled",
                0,
                conn.execute(
                    TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING,
                    mapOf("taskId" to "task_ns", "attempt" to 1, "now" to now),
                ),
            )
            assertEquals(0, (conn.runState("task_ns", 1).getValue("cleanup_pending") as Number).toInt())

            // Job 2: the marker scan is what becomes one Worker enqueue per attempt.
            val pending = conn.query(TaskRunRecoverySql.LIST_CANCEL_CLEANUP_PENDING)
            assertEquals(listOf("task_c"), pending.map { it.getValue("task_id") })
            assertEquals(1, (pending.single().getValue("cleanup_pending") as Number).toInt())

            // And the whole pass is repeatable: a second cold start changes nothing.
            assertEquals(0, conn.query(TaskRunRecoverySql.LIST_CANCELED_GENERATING_MISMATCH).size)
            assertEquals(
                0,
                conn.execute(
                    TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING,
                    mapOf("taskId" to "task_c", "attempt" to 1, "now" to now),
                ),
            )
        }
    }

    /**
     * ZLQ-126 §4.1's gate-1 row, taken through both rules' shipped SQL in the order the startup pass
     * runs them: a cancel the student made *before any Session existed*, whose marker was already set
     * and whose Worker never got scheduled because the process died.
     *
     * This is the case a credential-less cold start still owes. It is pure JDBC against the exported v3
     * schema — there is no PAT to read, no network to reach and no API to call anywhere in it, which is
     * the point: the writes below are the whole of the convergence.
     */
    @Test
    fun `a sessionless cancel awaiting compensation is fully converged by one credential-less pass`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow(latestTaskId = "task_ns"))
            conn.insert(
                "task_run",
                runRow(
                    taskId = "task_ns", runId = "req_ns", sessionId = null,
                    state = "CANCEL_REQUESTED", cleanupPending = 1,
                ),
            )

            // Rule 1 (`TaskRunRecoveryRule`) — the sessionless convergence, ungated by credential.
            assertEquals(
                1,
                conn.execute(
                    TaskRunRecoverySql.CONVERGE_CANCELED,
                    mapOf("taskId" to "task_ns", "attempt" to 1, "now" to now),
                ),
            )
            val converged = conn.runState("task_ns", 1)
            assertEquals("CANCELED", converged.getValue("state"))
            assertEquals(
                "the marker is not in CONVERGE_CANCELED's SET list, so rule 2 can still find the row",
                1,
                (converged.getValue("cleanup_pending") as Number).toInt(),
            )

            // Rule 2 (`CanceledRunReconcileRule`) job 1 — only now does the row match the mismatch scan.
            val mismatches = conn.query(TaskRunRecoverySql.LIST_CANCELED_GENERATING_MISMATCH)
            assertEquals(listOf("task_ns"), mismatches.map { it.getValue("task_id") })
            assertEquals(1, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs(taskId = "task_ns")))
            assertEquals("READY", conn.packageStatus())
            assertEquals(
                "sessionless, so nothing remote is owed and the marker is not re-armed",
                0,
                conn.execute(
                    TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING,
                    mapOf("taskId" to "task_ns", "attempt" to 1, "now" to now),
                ),
            )

            // Job 2 — exactly one row, which is exactly one `ss:cancel-task:task_ns-1` enqueue.
            val pending = conn.query(TaskRunRecoverySql.LIST_CANCEL_CLEANUP_PENDING)
            assertEquals(listOf("task_ns"), pending.map { it.getValue("task_id") })
            assertEquals(listOf(1), pending.map { (it.getValue("attempt") as Number).toInt() })

            // Repeatable: a second cold start finds no mismatch left and the guarded reset declines
            // without moving the package backwards.
            assertEquals(0, conn.query(TaskRunRecoverySql.LIST_CANCELED_GENERATING_MISMATCH).size)
            assertEquals(0, conn.execute(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL, resetArgs(taskId = "task_ns")))
            assertEquals("READY", conn.packageStatus())
            assertEquals("CANCELED", conn.runState("task_ns", 1).getValue("state"))
        }
    }

    // ---- §8.1 #6 and #12: the marker clear and the owning identity -----------------------------

    @Test
    fun `the marker clear is a compare-and-swap on the row the compensation actually ran for`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 1))
            val args = mapOf("taskId" to "task_c", "attempt" to 1, "now" to now)

            assertEquals(1, conn.execute(TaskRunRecoverySql.CLEAR_CANCEL_CLEANUP_PENDING, args))
            assertEquals(0, (conn.runState().getValue("cleanup_pending") as Number).toInt())
            assertEquals("a redelivery must not clear it a second time", 0, conn.execute(TaskRunRecoverySql.CLEAR_CANCEL_CLEANUP_PENDING, args))

            // A row that is no longer a pending CANCELED keeps its marker: the clear never lands on it.
            conn.insert("task_run", runRow(attempt = 2, runId = "req_c2", state = "RUNNING", cleanupPending = 1))
            assertEquals(
                0,
                conn.execute(
                    TaskRunRecoverySql.CLEAR_CANCEL_CLEANUP_PENDING,
                    mapOf("taskId" to "task_c", "attempt" to 2, "now" to now),
                ),
            )
            assertEquals(1, (conn.runState(attempt = 2).getValue("cleanup_pending") as Number).toInt())
        }
    }

    @Test
    fun `compensation reads the owning identity from the package, not from whoever is signed in`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow(identityId = owner))
            conn.insert("task_run", runRow(state = "CANCELED", cleanupPending = 1))
            // Another attempt of the same task, so "the row" has to be picked by (task_id, attempt).
            conn.insert("task_run", runRow(attempt = 2, runId = "req_c2", sessionId = "sess_c2", state = "CANCELED"))

            val found = conn.query(
                TaskRunRecoverySql.FIND_ATTEMPT_WITH_OWNER,
                mapOf("taskId" to "task_c", "attempt" to 1),
            ).single()
            assertEquals(owner, found.getValue("owner_identity_id"))
            assertEquals("sess_c", found.getValue("session_id"))
            assertEquals("pkg_1", found.getValue("package_id"))

            // The exact attempt is returned, not the task's latest one.
            assertEquals(
                2,
                (
                    conn.query(
                        TaskRunRecoverySql.FIND_ATTEMPT_WITH_OWNER,
                        mapOf("taskId" to "task_c", "attempt" to 2),
                    ).single().getValue("attempt") as Number
                    ).toInt(),
            )
            // A row whose package is gone yields nothing: the INNER JOIN, and step 1 treats that as done.
            conn.insert("task_run", runRow(taskId = "task_ghost", attempt = 1, runId = "req_g", packageId = "pkg_missing"))
            assertEquals(
                0,
                conn.query(
                    TaskRunRecoverySql.FIND_ATTEMPT_WITH_OWNER,
                    mapOf("taskId" to "task_ghost", "attempt" to 1),
                ).size,
            )
            // Deliberately unfiltered on state and marker, so the caller can tell "gone" from "moved on".
            assertEquals(
                1,
                conn.query(TaskRunRecoverySql.FIND_ATTEMPT_WITH_OWNER, mapOf("taskId" to "task_c", "attempt" to 2)).size,
            )
        }
    }

    @Test
    fun `the cancel path needs no migration because v3 already has every column it writes`() {
        assertEquals(3, schema(3).getValue("version").jsonPrimitive.content.toInt())
        val declared = columns("task_run").toSet()
        listOf("state", "cleanup_pending", "finished_at", "updated_at", "session_id")
            .forEach { assertTrue("`$it` must already exist in v3", it in declared) }

        val written = Regex("SET\\s+(.+?)\\s+WHERE", RegexOption.DOT_MATCHES_ALL)
            .findAll(
                TaskRunRecoverySql.MARK_CANCEL_CLEANUP_PENDING +
                    TaskRunRecoverySql.CLEAR_CANCEL_CLEANUP_PENDING +
                    CONCLUDE_CANCELED
            )
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim().substringBefore('=').trim().removeSuffix(":").trim() }
            .filter { it.isNotEmpty() && !it.startsWith(":") }
            .toSet()
        assertTrue(written.isNotEmpty())
        written.forEach { assertTrue("the cancel path writes `$it`, which v3 does not have", it in declared) }
    }
}
