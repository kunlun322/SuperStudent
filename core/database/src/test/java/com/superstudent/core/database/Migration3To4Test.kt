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
 * v3 → v4 migration (ZLQ-130 / ZLQ-132 §5.1), executed on the JVM against real SQLite.
 *
 * Same substitute discipline as [Migration2To3Test]: the statements under test are
 * [MIGRATION_3_4_SQL] — the exact list [MIGRATION_3_4] ships — and the "before" database is built
 * from the exported `3.json`. What that cannot prove is Room's own on-disk bookkeeping; what it does
 * prove is the two things that can silently destroy installed users' data or lock every installed
 * user out at open time: that the shipped SQL turns a real v3 database, with a real orphaned
 * `UPLOADING` row in it, into exactly the shape `4.json` declares — columns, affinities, NOT NULLs,
 * defaults and index *names* included.
 *
 * The index name is the sharpest edge here. Room derives `index_source_asset_package_id_upload_state`
 * from an unnamed `Index(...)`, so an unnamed recovery index would have been derived too — and would
 * not have matched the `CREATE INDEX` the migration ships, which fails the identity check on every
 * installed user's next launch. [Migration3To4Test] therefore compares a freshly created v4 database
 * against a migrated one rather than only against the JSON.
 */
class Migration3To4Test {

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

    private fun entity(schema: JsonObject, table: String): JsonObject =
        entities(schema).first { it.getValue("tableName").jsonPrimitive.content == table }

    private fun tableNames(schema: JsonObject): List<String> =
        entities(schema).map { it.getValue("tableName").jsonPrimitive.content }

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

    private fun setVersion(conn: Connection, version: Int) {
        conn.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    /**
     * Room only runs a migration when the on-disk `user_version` is below the declared version, and
     * the framework — not the migration — writes the new version afterwards.
     */
    private fun openLikeRoom(conn: Connection, target: Int): Int {
        val from = userVersion(conn)
        if (from >= target) return 0
        assertEquals("a v3 database must be migrated by exactly one registered step", 3, from)
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
        conn.createStatement().use { st -> MIGRATION_3_4_SQL.forEach { st.execute(it) } }
        setVersion(conn, target)
        return 1
    }

    private data class Column(val affinity: String, val notNull: Boolean, val defaultValue: String?)

    private data class IndexShape(val unique: Boolean, val columns: List<String>)

    private fun liveColumns(conn: Connection, table: String): Map<String, Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getString("name"),
                            Column(
                                affinityOf(rs.getString("type")),
                                rs.getInt("notnull") == 1,
                                rs.getString("dflt_value")?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }

    private fun declaredColumns(schema: JsonObject, table: String): Map<String, Column> =
        entity(schema, table).getValue("fields").jsonArray.map { it.jsonObject }.associate { field ->
            field.getValue("columnName").jsonPrimitive.content to Column(
                affinity = field.getValue("affinity").jsonPrimitive.content,
                notNull = field["notNull"]?.jsonPrimitive?.boolean ?: false,
                defaultValue = field["defaultValue"]?.jsonPrimitive?.content,
            )
        }

    private fun liveIndexes(conn: Connection, table: String): Map<String, IndexShape> {
        val listed = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getString("name") to (rs.getInt("unique") == 1))
                    }
                }
            }
        }
        return listed.associate { (name, unique) ->
            val columns = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
            name to IndexShape(unique, columns)
        }
    }

    private fun declaredIndexes(schema: JsonObject, table: String): Map<String, IndexShape> =
        entity(schema, table)["indices"]?.jsonArray?.map { it.jsonObject }?.associate { index ->
            index.getValue("name").jsonPrimitive.content to IndexShape(
                unique = index.getValue("unique").jsonPrimitive.boolean,
                columns = index.getValue("columnNames").jsonArray
                    .map { it.jsonPrimitive.content },
            )
        } ?: emptyMap()

    private fun affinityOf(sqlType: String): String = when (sqlType.uppercase()) {
        "INTEGER" -> "INTEGER"
        "REAL" -> "REAL"
        "TEXT" -> "TEXT"
        "BLOB" -> "BLOB"
        else -> error("unexpected column type `$sqlType`")
    }

    /**
     * The row shape this migration exists for: a v3 attempt that was `UPLOADING` when the process
     * died, carrying a token and an expired lease but no owner column, because v3 had none. Recovery
     * condition 1 of design §3.2 keys off exactly `lease_owner_id IS NULL`.
     */
    private val orphanedV3Row = mapOf(
        "source_id" to "src_zlq130",
        "package_id" to "pkg_1",
        "drive_path" to null,
        "display_name" to "第三章.pdf",
        "mime_type" to "application/pdf",
        "kind" to "FILE",
        "size_bytes" to 20480,
        "sha256" to null,
        "upload_state" to "UPLOADING",
        "local_uri" to "content://downloads/src_zlq130",
        "added_at" to "2026-09-30T10:00:00Z",
        "local_access_mode" to "PERSISTABLE",
        "canonical_type" to "PDF",
        "error_code" to null,
        "error_message" to null,
        "retryable" to 0,
        "attempt_count" to 2,
        "next_retry_at" to null,
        "attempt_token" to "tok_v3_orphan",
        "lease_until" to 1_700_000_000_000L,
        "updated_at" to "2026-09-30T10:00:30Z",
    )

    private fun insertV3Row(conn: Connection) {
        val fields = declaredColumns(schema(3), "source_asset").keys.toList()
        assertEquals("the fixture must cover every v3 column", fields.toSet(), orphanedV3Row.keys)
        val sql = "INSERT INTO source_asset (${fields.joinToString(",") { "`$it`" }}) " +
            "VALUES (${fields.joinToString(",") { "?" }})"
        conn.prepareStatement(sql).use { ps ->
            fields.forEachIndexed { i, column ->
                when (val value = orphanedV3Row.getValue(column)) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is Int -> ps.setInt(i + 1, value)
                    is Long -> ps.setLong(i + 1, value)
                    else -> ps.setString(i + 1, value.toString())
                }
            }
            assertEquals(1, ps.executeUpdate())
        }
    }

    private fun readRow(conn: Connection): Map<String, Any?> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM source_asset WHERE source_id = 'src_zlq130'").use { rs ->
                assertTrue(rs.next())
                val meta = rs.metaData
                (1..meta.columnCount).associate { i ->
                    meta.getColumnLabel(i) to rs.getObject(i)?.takeIf { !rs.wasNull() }
                }
            }
        }

    private fun withV3Database(body: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            createAll(conn, schema(3))
            setVersion(conn, 3)
            insertV3Row(conn)
            body(conn)
        }
    }

    @Test
    fun `a migrated v3 row keeps every existing value and gains only NULL lease columns`() {
        withV3Database { conn ->
            val before = readRow(conn)

            assertEquals(1, openLikeRoom(conn, 4))

            val after = readRow(conn)
            orphanedV3Row.keys.forEach { column ->
                assertEquals("column `$column` must survive the migration", before[column], after[column])
            }
            // NULL, not a sentinel: a v3 attempt has no owner to attribute, and inventing one would
            // make an orphan look claimed and unreclaimable forever.
            listOf(
                "lease_owner_id",
                "lease_heartbeat_at",
                "upload_started_at",
                "last_progress_at",
                "interrupt_requested_at",
                "delete_requested_at",
            ).forEach { column -> assertNull("column `$column`", after.getValue(column)) }
            // C3 (ZLQ-136): the column exists but this build writes no local substitute for the
            // server-issued generation. A locally invented value would later be read as authoritative.
            assertNull(after.getValue("remote_generation"))
            // Not NULL and not defaulted to 1: a pre-existing row owes no delete.
            assertEquals(0, after.getValue("delete_pending"))
        }
    }

    @Test
    fun `the migrated source_asset table matches the exported v4 schema column for column`() {
        withV3Database { conn ->
            openLikeRoom(conn, 4)
            // Compared as a map, the way Room's identity check compares columns: ALTER TABLE appends
            // the new columns after `updated_at` while a fresh create declares them before it, and
            // that ordering difference must not matter.
            assertEquals(declaredColumns(schema(4), "source_asset"), liveColumns(conn, "source_asset"))
        }
    }

    @Test
    fun `the migrated database carries the recovery index under the name the entity declares`() {
        withV3Database { conn ->
            openLikeRoom(conn, 4)
            // `sqlite_autoindex_source_asset_1` is SQLite's implicit index for the TEXT primary key.
            // Room never records it in the schema JSON — it is implied by the entity's `primaryKey` —
            // so it is excluded here rather than in `liveIndexes`: the live-vs-live comparison below
            // still asserts both databases derive it identically.
            assertEquals(
                declaredIndexes(schema(4), "source_asset"),
                liveIndexes(conn, "source_asset")
                    .filterKeys { !it.startsWith("sqlite_autoindex_") },
            )
            assertTrue(
                "an unnamed Index(...) would have derived a different name and broken the identity check",
                liveIndexes(conn, "source_asset").containsKey("index_source_asset_recovery"),
            )
        }
    }

    @Test
    fun `a fresh v4 database and a migrated v3 database agree on every table and index`() {
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh.use { freshConn ->
            migrated.use { migratedConn ->
                createAll(freshConn, schema(4))
                createAll(migratedConn, schema(3))
                setVersion(migratedConn, 3)
                openLikeRoom(migratedConn, 4)

                val tables = tableNames(schema(4))
                assertEquals(tableNames(schema(3)).toSet(), tables.toSet())
                tables.forEach { table ->
                    assertEquals(
                        "table `$table` columns",
                        liveColumns(freshConn, table),
                        liveColumns(migratedConn, table),
                    )
                    assertEquals(
                        "table `$table` indices",
                        liveIndexes(freshConn, table),
                        liveIndexes(migratedConn, table),
                    )
                }
            }
        }
    }

    @Test
    fun `the shipped statements are additive and the one NOT NULL column carries its declared default`() {
        assertEquals(
            listOf(
                "ALTER TABLE `source_asset` ADD COLUMN `lease_owner_id` TEXT",
                "ALTER TABLE `source_asset` ADD COLUMN `lease_heartbeat_at` INTEGER",
                "ALTER TABLE `source_asset` ADD COLUMN `upload_started_at` INTEGER",
                "ALTER TABLE `source_asset` ADD COLUMN `last_progress_at` INTEGER",
                "ALTER TABLE `source_asset` ADD COLUMN `interrupt_requested_at` INTEGER",
                "ALTER TABLE `source_asset` ADD COLUMN `remote_generation` INTEGER",
                "ALTER TABLE `source_asset` ADD COLUMN `delete_pending` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `source_asset` ADD COLUMN `delete_requested_at` INTEGER",
                "CREATE INDEX IF NOT EXISTS `index_source_asset_recovery` " +
                    "ON `source_asset` (`upload_state`, `delete_pending`, `lease_until`, `next_retry_at`)",
            ),
            MIGRATION_3_4_SQL,
        )
        // No DROP, no RENAME, no table rebuild: a migration that recreates `source_asset` is one
        // crashed statement away from an installed user losing every row.
        MIGRATION_3_4_SQL.forEach { statement ->
            val upper = statement.uppercase()
            assertFalse(upper.contains("DROP "))
            assertFalse(upper.contains("RENAME"))
            assertFalse(upper.contains("DELETE FROM"))
        }
        // Every nullable column must stay DEFAULT-free, so the migrated affinity/default set equals
        // the fresh one; only `delete_pending` may carry a default, and it must equal the entity's.
        val notNullColumn = MIGRATION_3_4_SQL.single { it.uppercase().contains("NOT NULL") }
        assertTrue(notNullColumn.contains("`delete_pending`"))
        assertTrue(notNullColumn.contains("DEFAULT 0"))
        assertEquals(
            "0",
            declaredColumns(schema(4), "source_asset").getValue("delete_pending").defaultValue,
        )
        MIGRATION_3_4_SQL.filterNot { it == notNullColumn }.forEach { statement ->
            assertFalse("nullable columns must not gain a DEFAULT", statement.uppercase().contains("DEFAULT"))
        }
    }

    @Test
    fun `a repeat launch runs no migration and leaves the row alone`() {
        withV3Database { conn ->
            openLikeRoom(conn, 4)
            val afterFirst = readRow(conn)
            assertEquals("a repeat launch must be a no-op", 0, openLikeRoom(conn, 4))
            assertEquals(4, userVersion(conn))
            assertEquals(afterFirst, readRow(conn))
        }
    }

    @Test
    fun `a migration that fails partway rolls back to a working v3 database`() {
        withV3Database { conn ->
            val before = readRow(conn)
            val v3Columns = liveColumns(conn, "source_asset")

            conn.autoCommit = false
            val failed = runCatching {
                conn.createStatement().use { st ->
                    // The first four statements are the real ones; the fifth repeats a column the
                    // fourth just added, which is how a half-applied migration actually fails.
                    MIGRATION_3_4_SQL.take(4).forEach { st.execute(it) }
                    st.execute("ALTER TABLE `source_asset` ADD COLUMN `lease_owner_id` TEXT")
                }
                conn.commit()
                false
            }.onFailure { conn.rollback() }.isFailure
            conn.autoCommit = true

            assertTrue("the duplicate column must have failed the migration", failed)
            // Nothing destructive ran, so the student still has a v3 database the previous build can
            // open — which is the whole reason no destructive fallback is registered.
            assertEquals(3, userVersion(conn))
            assertEquals(v3Columns, liveColumns(conn, "source_asset"))
            assertEquals(before, readRow(conn))
        }
    }
}
