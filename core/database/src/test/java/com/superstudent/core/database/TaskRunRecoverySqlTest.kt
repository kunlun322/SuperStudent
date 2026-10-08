package com.superstudent.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The sessionless-run reconciliation SQL, executed on the JVM against real SQLite (ZLQ-103 §3).
 *
 * The statements under test are the [TaskRunRecoverySql] constants — the exact strings the shipped
 * `@Query`s carry — and the database is built from the exported `3.json`, so this proves the part
 * that would otherwise only be visible on a device: that a real v3 database containing a real
 * `QUEUED + session_id = NULL` orphan is repaired by a guarded UPDATE that cannot touch a run which
 * has already moved past the sessionless window, and that the D-2 admission count stops counting it.
 *
 * This needs no migration, and this test is also the guard on that claim: it runs against the
 * unmodified v3 schema, so if a constant ever needs a column v3 does not have, SQLite rejects it here.
 */
class TaskRunRecoverySqlTest {

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

    private fun packageRow(
        packageId: String = "pkg_1",
        identityId: String = "id_zlq",
        status: String = "GENERATING",
        latestTaskId: String? = "task_orphan",
    ) = mapOf(
        "package_id" to packageId,
        "identity_id" to identityId,
        "title" to "初二数学",
        "goal" to "SYNCHRONIZE",
        "chapter_range" to null,
        "status" to status,
        "latest_task_id" to latestTaskId,
        "source_count" to 3,
        "created_at" to "2026-09-28T01:00:00Z",
        "updated_at" to "2026-09-28T01:00:00Z",
    )

    private fun runRow(
        taskId: String = "task_orphan",
        attempt: Int = 1,
        packageId: String = "pkg_1",
        runId: String = "run_orphan",
        sessionId: String? = null,
        state: String = "QUEUED",
        stage: String? = "CREATE_SESSION",
        progress: Int = 0,
        resumeFromStage: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        updatedAt: String = "2026-09-28T01:00:01Z",
    ) = mapOf(
        "task_id" to taskId,
        "attempt" to attempt,
        "package_id" to packageId,
        "run_id" to runId,
        "session_id" to sessionId,
        "state" to state,
        "stage" to stage,
        "progress" to progress,
        "resume_from_stage" to resumeFromStage,
        "last_event_id" to null,
        "error_code" to errorCode,
        "error_message" to errorMessage,
        "credits" to 0.0,
        "active_seconds" to null,
        "duration_seconds" to null,
        "cleanup_pending" to 0,
        "created_at" to "2026-09-28T01:00:01Z",
        "started_at" to null,
        "finished_at" to null,
        "updated_at" to updatedAt,
    )

    /** A v3 database, built from `3.json`, with `body` free to seed whatever it needs. */
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

    private val convergeArgs = mapOf(
        "taskId" to "task_orphan",
        "attempt" to 1,
        "errorCode" to "START_INTERRUPTED",
        "errorMessage" to "本次生成在启动阶段被中断，请重试",
        "now" to "2026-09-28T02:00:00Z",
    )

    private fun Connection.taskRow(taskId: String = "task_orphan", attempt: Int = 1) =
        query("SELECT * FROM task_run WHERE task_id = '$taskId' AND attempt = $attempt").single()

    private fun Connection.activeCountByIdentity(identityId: String = "id_zlq") =
        (
            query(TaskRunRecoverySql.COUNT_ACTIVE_BY_IDENTITY, mapOf("identityId" to identityId))
                .single().values.single() as Number
            ).toLong()

    // ---- the orphan itself -------------------------------------------------------------------

    @Test
    fun `a v3 orphan that never got a session converges to FAILED_RETRYABLE and stops counting`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())

            // The defect as installed users have it: the guard counts the orphan forever.
            assertEquals(1L, conn.activeCountByIdentity())

            assertEquals(1, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))

            val row = conn.taskRow()
            assertEquals("FAILED_RETRYABLE", row.getValue("state"))
            assertEquals("START_INTERRUPTED", row.getValue("error_code"))
            assertEquals("本次生成在启动阶段被中断，请重试", row.getValue("error_message"))
            assertEquals("2026-09-28T02:00:00Z", row.getValue("finished_at"))
            assertEquals("2026-09-28T02:00:00Z", row.getValue("updated_at"))
            // A run that never had a Session never measured anything: the counters stay NULL, not 0.
            assertNull(row.getValue("session_id"))
            assertNull(row.getValue("active_seconds"))
            assertNull(row.getValue("duration_seconds"))

            assertEquals("the D-2 guard must stop counting a converged run", 0L, conn.activeCountByIdentity())
        }
    }

    @Test
    fun `convergence is idempotent and never rewrites a row it already moved`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())

            assertEquals(1, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            val first = conn.taskRow()
            // A second startup, a Worker retry and an observe() pass all re-run the same reconcile.
            assertEquals(0, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            assertEquals(0, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            assertEquals(first, conn.taskRow())
        }
    }

    @Test
    fun `the guard leaves every run that is not an orphan alone`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            val untouched = listOf(
                // Past the sessionless window: the submit chain owns it, recovery must not conclude it.
                runRow(taskId = "task_live", runId = "run_live", sessionId = "sess_live", state = "RUNNING"),
                // A recorded cancel intent converges to CANCELED, not to a failure.
                runRow(taskId = "task_cancel", attempt = 1, runId = "run_cancel", state = "CANCEL_REQUESTED"),
                // Already terminal; rewriting it would lose the real conclusion and its usage.
                runRow(
                    taskId = "task_done", attempt = 1, runId = "run_done", sessionId = "sess_done",
                    state = "SUCCEEDED", progress = 100,
                ),
            )
            // (task_id, attempt) is the primary key, so each fixture row needs its own attempt.
            conn.insert("task_run", runRow())
            untouched.forEachIndexed { i, row -> conn.insert("task_run", row + ("attempt" to (i + 2))) }

            assertEquals(1, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))

            assertEquals("FAILED_RETRYABLE", conn.taskRow("task_orphan", 1).getValue("state"))
            assertEquals("RUNNING", conn.taskRow("task_live", 2).getValue("state"))
            assertEquals("CANCEL_REQUESTED", conn.taskRow("task_cancel", 3).getValue("state"))
            assertEquals("SUCCEEDED", conn.taskRow("task_done", 4).getValue("state"))
        }
    }

    @Test
    fun `every mid-run state without a session converges, and no terminal state does`() {
        val midRun = listOf("QUEUED", "RUNNING", "RETRY_WAIT", "UNKNOWN")
        val terminal = listOf(
            "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_PERMANENT", "CANCELED",
            "AUTH_EXPIRED", "ACCESS_DENIED", "IDENTITY_INVALID",
        )
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            (midRun + terminal).forEachIndexed { i, state ->
                conn.insert(
                    "task_run",
                    runRow(taskId = "task_$i", attempt = i + 1, runId = "run_$i", state = state),
                )
            }
            midRun.forEachIndexed { i, state ->
                assertEquals(
                    "$state must converge",
                    1,
                    conn.execute(
                        TaskRunRecoverySql.CONVERGE_INTERRUPTED,
                        convergeArgs + mapOf("taskId" to "task_$i", "attempt" to (i + 1)),
                    ),
                )
            }
            terminal.forEachIndexed { i, state ->
                val index = midRun.size + i
                assertEquals(
                    "$state is already concluded and must not be rewritten",
                    0,
                    conn.execute(
                        TaskRunRecoverySql.CONVERGE_INTERRUPTED,
                        convergeArgs + mapOf("taskId" to "task_$index", "attempt" to (index + 1)),
                    ),
                )
            }
        }
    }

    // ---- the cancel intent -------------------------------------------------------------------

    @Test
    fun `a cancel recorded before any session existed converges to CANCELED, not to a failure`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(state = "CANCEL_REQUESTED"))

            assertEquals(0, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            assertEquals(
                1,
                conn.execute(
                    TaskRunRecoverySql.CONVERGE_CANCELED,
                    mapOf("taskId" to "task_orphan", "attempt" to 1, "now" to "2026-09-28T02:00:00Z"),
                ),
            )

            val row = conn.taskRow()
            assertEquals("CANCELED", row.getValue("state"))
            assertNull("a user cancel is not an error", row.getValue("error_code"))
            assertNull(row.getValue("error_message"))
            assertEquals("2026-09-28T02:00:00Z", row.getValue("finished_at"))
            assertEquals(0L, conn.activeCountByIdentity())
        }
    }

    // ---- the D-2 admission count -------------------------------------------------------------

    @Test
    fun `admission counts QUEUED per owning identity, so a live first build still blocks a second`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("learning_package", packageRow(packageId = "pkg_2", identityId = "id_other"))
            // The concurrent first build the issue asks to keep rejecting: RUNNING with a Session.
            conn.insert(
                "task_run",
                runRow(taskId = "task_live", runId = "run_live", sessionId = "sess_live", state = "RUNNING"),
            )
            // Another identity's active run must not block this one.
            conn.insert(
                "task_run",
                runRow(taskId = "task_other", runId = "run_other", packageId = "pkg_2", sessionId = "s2", state = "RUNNING"),
            )

            assertEquals("a live first build must still be counted", 1L, conn.activeCountByIdentity())
            assertEquals(1L, conn.activeCountByIdentity("id_other"))
            assertEquals("an identity with no runs is admitted", 0L, conn.activeCountByIdentity("id_nobody"))

            // The orphan is what makes it 2 before recovery — and recovery, not the guard, fixes it.
            conn.insert("task_run", runRow())
            assertEquals(2L, conn.activeCountByIdentity())
            assertEquals(1, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            assertEquals(
                "converging the orphan must leave the genuine concurrent run counted",
                1L,
                conn.activeCountByIdentity(),
            )
        }
    }

    @Test
    fun `the package-scoped count excludes the run being converged`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow())
            conn.insert(
                "task_run",
                runRow(taskId = "task_live", attempt = 2, runId = "run_live", sessionId = "sess_live", state = "RUNNING"),
            )
            val countExcluding = { taskId: String, attempt: Int ->
                (
                    conn.query(
                        TaskRunRecoverySql.COUNT_ACTIVE_FOR_PACKAGE_EXCLUDING,
                        mapOf("packageId" to "pkg_1", "taskId" to taskId, "attempt" to attempt),
                    ).single().values.single() as Number
                    ).toLong()
            }
            assertEquals(
                "the other active run keeps the package GENERATING",
                1L,
                countExcluding("task_orphan", 1),
            )

            // Converging the orphan does not by itself release the package: a live run remains.
            assertEquals(1, conn.execute(TaskRunRecoverySql.CONVERGE_INTERRUPTED, convergeArgs))
            assertEquals(1L, countExcluding("task_orphan", 1))

            // Asked from the live run's side, the converged orphan is no longer counted, so a
            // converge that leaves it as the only run is what lets the package go back to READY.
            assertEquals(0L, countExcluding("task_live", 2))
        }
    }

    // ---- the package status reset ------------------------------------------------------------

    @Test
    fun `a converged run puts its own GENERATING package back to READY and leaves a newer one alone`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("learning_package", packageRow(packageId = "pkg_2", latestTaskId = "task_newer"))
            conn.insert("task_run", runRow())
            conn.insert("task_run", runRow(taskId = "task_newer", attempt = 2, runId = "run_newer", packageId = "pkg_2"))

            val reset = { packageId: String, taskId: String ->
                conn.execute(
                    TaskRunRecoverySql.PACKAGE_BACK_TO_READY,
                    mapOf("packageId" to packageId, "taskId" to taskId, "now" to "2026-09-28T02:00:00Z"),
                )
            }

            assertEquals(1, reset("pkg_1", "task_orphan"))
            assertEquals("READY", conn.query("SELECT status FROM learning_package WHERE package_id = 'pkg_1'").single().getValue("status"))

            // A package that already moved on to a newer task must not be reset by an older run.
            assertEquals(0, reset("pkg_2", "task_orphan"))
            assertEquals("GENERATING", conn.query("SELECT status FROM learning_package WHERE package_id = 'pkg_2'").single().getValue("status"))
            // Already READY: nothing to do, and no `updated_at` churn.
            assertEquals(0, reset("pkg_1", "task_orphan"))
        }
    }

    // ---- intent redelivery -------------------------------------------------------------------

    @Test
    fun `a redelivered START_NEW finds its own attempt by requestId and never creates a second one`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("task_run", runRow(runId = "request_1"))
            conn.insert("task_run", runRow(taskId = "task_orphan", attempt = 2, runId = "request_1", sessionId = "sess_2", state = "RUNNING"))
            conn.insert("task_run", runRow(taskId = "task_other", attempt = 1, runId = "request_2", state = "QUEUED"))

            val found = conn.query(TaskRunRecoverySql.FIND_BY_RUN_ID, mapOf("runId" to "request_1")).single()
            assertEquals("task_orphan", found.getValue("task_id"))
            assertEquals(
                "the latest attempt is the one a redelivery must resume, not the interrupted one",
                2,
                (found.getValue("attempt") as Number).toInt(),
            )
            assertEquals("sess_2", found.getValue("session_id"))
            assertEquals(
                "a requestId that never reached createAttempt resolves to nothing, so the service submits",
                0,
                conn.query(TaskRunRecoverySql.FIND_BY_RUN_ID, mapOf("runId" to "request_never_seen")).size,
            )
            assertEquals(1, conn.query(TaskRunRecoverySql.FIND_BY_RUN_ID, mapOf("runId" to "request_2")).size)
        }
    }

    // ---- what recovery looks at --------------------------------------------------------------

    @Test
    fun `the reconcile pass sees every active run with the identity that owns it`() {
        withV3Database { conn ->
            conn.insert("learning_package", packageRow())
            conn.insert("learning_package", packageRow(packageId = "pkg_2", identityId = "id_other", status = "READY", latestTaskId = null))
            conn.insert("task_run", runRow())
            // Distinct `updated_at` values: the ORDER BY is what puts the newest run first, and a tie
            // would leave the sequence to SQLite's rowid order rather than to the shipped SQL.
            conn.insert("task_run", runRow(taskId = "task_cancel", attempt = 2, runId = "run_cancel", state = "CANCEL_REQUESTED", updatedAt = "2026-09-28T01:00:05Z"))
            conn.insert("task_run", runRow(taskId = "task_live", attempt = 3, runId = "run_live", sessionId = "s", state = "RUNNING", updatedAt = "2026-09-28T01:00:03Z"))
            conn.insert("task_run", runRow(taskId = "task_unknown", attempt = 4, runId = "run_unknown", state = "UNKNOWN", updatedAt = "2026-09-28T03:00:00Z"))
            conn.insert("task_run", runRow(taskId = "task_retry", attempt = 5, runId = "run_retry", state = "RETRY_WAIT", updatedAt = "2026-09-28T01:00:04Z"))
            conn.insert(
                "task_run",
                runRow(taskId = "task_done", attempt = 6, runId = "run_done", sessionId = "s6", state = "SUCCEEDED"),
            )
            // A run whose package is gone must not silently disappear from recovery's view: the INNER
            // JOIN drops it, which is the documented limit — asserted here so the trade-off is visible.
            conn.insert("task_run", runRow(taskId = "task_orphan_pkg", attempt = 7, runId = "run_7", packageId = "pkg_missing"))

            val rows = conn.query(TaskRunRecoverySql.LIST_ACTIVE_WITH_OWNER)
            assertEquals(
                "only active runs whose package still exists, newest first",
                listOf("task_unknown", "task_cancel", "task_retry", "task_live", "task_orphan"),
                rows.map { it.getValue("task_id") },
            )
            rows.forEach { assertTrue(it.containsKey("owner_identity_id") && it.containsKey("package_status")) }
            assertEquals("id_zlq", rows.first().getValue("owner_identity_id"))
            assertEquals("GENERATING", rows.first().getValue("package_status"))
            assertEquals("task_orphan", rows.first().getValue("package_latest_task_id"))
        }
    }

    @Test
    fun `recovery needs no migration because every column it writes already exists in v3`() {
        assertEquals(3, schema(3).getValue("version").jsonPrimitive.content.toInt())
        val declared = columns("task_run").toSet()
        listOf("run_id", "session_id", "state", "error_code", "error_message", "finished_at", "updated_at")
            .forEach { assertTrue("`$it` must already exist in v3", it in declared) }
        // And the shipped SQL only ever touches v3 columns — proven by running it above, asserted
        // here so a future column reference fails with a readable message instead of a SQLite error.
        val written = Regex("SET\\s+(.+?)\\s+WHERE", RegexOption.DOT_MATCHES_ALL)
            .findAll(TaskRunRecoverySql.CONVERGE_INTERRUPTED + TaskRunRecoverySql.CONVERGE_CANCELED)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim().substringBefore('=').trim().removeSuffix(":").trim() }
            .filter { it.isNotEmpty() && !it.startsWith(":") }
            .toSet()
        assertTrue(written.isNotEmpty())
        written.forEach { assertTrue("recovery writes `$it`, which v3 does not have", it in declared) }
    }
}
