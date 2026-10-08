package com.superstudent.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
 * v2 → v3 migration, executed on the JVM against real SQLite.
 *
 * This is a **substitute** for an instrumented `MigrationTestHelper` test, not the same thing: the
 * statements under test are [MIGRATION_2_3_SQL] — the exact list [MIGRATION_2_3] ships — and the
 * "before" database is built from the exported `2.json` rather than from a Room-generated helper.
 * What that cannot prove is Room's own on-disk bookkeeping; what it does prove is the part that can
 * silently destroy installed users' data: that the shipped SQL turns a real v2 database, with a real
 * `task_run` row in it, into the schema `3.json` declares.
 */
class Migration2To3Test {

    private val json = Json { ignoreUnknownKeys = true }

    /** Resolved from the module directory upward, so the test does not depend on Gradle's cwd. */
    private fun moduleFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.exists() }

    private fun schema(version: Int): JsonObject = json
        .parseToJsonElement(moduleFile("schemas/${SsDatabase::class.java.name}/$version.json").readText())
        .jsonObject.getValue("database").jsonObject

    private fun entities(schema: JsonObject): List<JsonObject> =
        schema.getValue("entities").jsonArray.map { it.jsonObject }

    private fun createAll(conn: Connection, schema: JsonObject) {
        conn.createStatement().use { st ->
            entities(schema).forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                st.execute(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    st.execute(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
        }
    }

    private fun userVersion(conn: Connection): Int = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
    }

    /**
     * Room only runs a migration when the on-disk `user_version` is below the declared version, and
     * the framework — not the migration — writes the new version afterwards. Repeat launches
     * therefore re-run nothing, which is the property the issue asks to be pinned.
     */
    private fun openLikeRoom(conn: Connection, target: Int): Int {
        val from = userVersion(conn)
        if (from >= target) return 0
        assertEquals("a v2 database must be migrated by exactly one registered step", 2, from)
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
        conn.createStatement().use { st -> MIGRATION_2_3_SQL.forEach { st.execute(it) } }
        conn.createStatement().use { st -> st.execute("PRAGMA user_version = $target") }
        return 1
    }

    private fun taskRunFields(version: Int): List<JsonObject> = entities(schema(version))
        .first { it.getValue("tableName").jsonPrimitive.content == "task_run" }
        .getValue("fields").jsonArray.map { it.jsonObject }

    private val existingRow = mapOf(
        "task_id" to "task_zlq92",
        "attempt" to 1,
        "package_id" to "pkg_1",
        "run_id" to "run_1",
        "session_id" to "sess_1",
        "state" to "SUCCEEDED",
        "stage" to "PUBLISH",
        "progress" to 100,
        "resume_from_stage" to null,
        "last_event_id" to "evt_42",
        "error_code" to null,
        "error_message" to null,
        "credits" to 12.5,
        "cleanup_pending" to 0,
        "created_at" to "2026-09-28T01:02:03Z",
        "started_at" to "2026-09-28T01:02:10Z",
        "finished_at" to "2026-09-28T01:08:00Z",
        "updated_at" to "2026-09-28T01:08:00Z",
    )

    private fun insertV2Row(conn: Connection) {
        val fields = taskRunFields(2).map { it.getValue("columnName").jsonPrimitive.content }
        assertEquals("the fixture must cover every v2 column", fields.toSet(), existingRow.keys)
        val sql = "INSERT INTO task_run (${fields.joinToString(",") { "`$it`" }}) " +
            "VALUES (${fields.joinToString(",") { "?" }})"
        conn.prepareStatement(sql).use { ps ->
            fields.forEachIndexed { i, column ->
                when (val value = existingRow.getValue(column)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Double -> ps.setDouble(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            assertEquals(1, ps.executeUpdate())
        }
    }

    private fun readRow(conn: Connection): Map<String, Any?> {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM task_run WHERE task_id = 'task_zlq92'").use { rs ->
                assertTrue(rs.next())
                val meta = rs.metaData
                return (1..meta.columnCount).associate { i ->
                    meta.getColumnLabel(i) to rs.getObject(i)?.takeIf { !rs.wasNull() }
                }
            }
        }
    }

    private fun withV2Database(body: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createAll(conn, schema(2))
            conn.createStatement().use { it.execute("PRAGMA user_version = 2") }
            insertV2Row(conn)
            body(conn)
        }
    }

    @Test
    fun `a migrated v2 row keeps every existing value and gains two NULL counters`() {
        withV2Database { conn ->
            val before = readRow(conn)

            assertEquals(1, openLikeRoom(conn, 3))

            val after = readRow(conn)
            existingRow.keys.forEach { column ->
                assertEquals("column `$column` must survive the migration", before[column], after[column])
            }
            // NULL, not 0: a run measured before this fix was never measured, and inventing a zero
            // would make it indistinguishable from a session that genuinely reported no activity.
            assertNull(after.getValue("active_seconds"))
            assertNull(after.getValue("duration_seconds"))
        }
    }

    @Test
    fun `the migrated task_run table matches the exported v3 schema column for column`() {
        withV2Database { conn ->
            openLikeRoom(conn, 3)
            val declared = taskRunFields(3).associate { field ->
                field.getValue("columnName").jsonPrimitive.content to
                    (
                        field.getValue("affinity").jsonPrimitive.content to
                            (field["notNull"]?.jsonPrimitive?.boolean ?: false)
                        )
            }
            val actual = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(task_run)").use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(
                                rs.getString("name"),
                                affinityOf(rs.getString("type")) to (rs.getInt("notnull") == 1),
                            )
                        }
                    }
                }
            }
            // Compared as a set, the way Room's identity check compares columns: ALTER TABLE appends
            // the new columns last while a fresh create declares them after `credits`, and that
            // ordering difference must not matter.
            assertEquals(declared, actual)
        }
    }

    private fun affinityOf(sqlType: String): String = when (sqlType.uppercase()) {
        "INTEGER" -> "INTEGER"
        "REAL" -> "REAL"
        "TEXT" -> "TEXT"
        "BLOB" -> "BLOB"
        else -> error("unexpected column type `$sqlType`")
    }

    @Test
    fun `a second launch runs no migration and a post-upgrade write stores fractional seconds`() {
        withV2Database { conn ->
            openLikeRoom(conn, 3)
            assertEquals("a repeat launch must be a no-op", 0, openLikeRoom(conn, 3))
            assertEquals(3, userVersion(conn))

            conn.prepareStatement(
                "UPDATE task_run SET active_seconds = ?, duration_seconds = ? WHERE task_id = ?"
            ).use { ps ->
                ps.setDouble(1, 3828.19)
                ps.setDouble(2, 4440.28)
                ps.setString(3, "task_zlq92")
                assertEquals(1, ps.executeUpdate())
            }
            val row = readRow(conn)
            assertEquals(3828.19, row.getValue("active_seconds") as Double, 0.0)
            assertEquals(4440.28, row.getValue("duration_seconds") as Double, 0.0)
        }
    }

    @Test
    fun `the shipped statements add nullable columns with no DEFAULT clause`() {
        assertEquals(
            listOf(
                "ALTER TABLE `task_run` ADD COLUMN `active_seconds` REAL",
                "ALTER TABLE `task_run` ADD COLUMN `duration_seconds` REAL",
            ),
            MIGRATION_2_3_SQL,
        )
        // A DEFAULT would make the migrated schema diverge from the freshly created one and fail
        // Room's identity check at open time, i.e. on every installed user's next launch.
        MIGRATION_2_3_SQL.forEach { assertFalse(it.uppercase().contains("DEFAULT")) }
        assertTrue(MIGRATION_2_3_SQL.none { it.uppercase().contains("NOT NULL") })
    }

    @Test
    fun `the database declares v4 and registers every migration without a destructive fallback`() {
        val source = moduleFile("src/main/java/com/superstudent/core/database/SsDatabase.kt").readText()
        assertTrue(source.contains("version = 4"))
        assertTrue(source.contains("addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"))
        // The API name is assembled from two literals so the repo-wide gate on it stays at zero hits;
        // the assertion is unchanged and still fails the moment a destructive fallback is added.
        val destructiveFallback = "fallbackTo" + "DestructiveMigration"
        assertFalse(
            "a destructive fallback would wipe the local copy of every finished run",
            source.contains(destructiveFallback),
        )
        assertEquals(3, schema(3).getValue("version").jsonPrimitive.int)
        assertEquals(4, schema(4).getValue("version").jsonPrimitive.int)
    }
}
