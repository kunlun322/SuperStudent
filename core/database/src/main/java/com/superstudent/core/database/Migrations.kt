package com.superstudent.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (ZLQ-81/ZLQ-82 design increment §2): the upload chain gains real failure state.
 *
 * `drive_path` becomes nullable because at row-creation time the file has not been read or hashed
 * yet, and a placeholder path is exactly what used to leak a user file name into Drive. The new
 * columns carry the error code, its desensitized message, retry scheduling and the CAS claim
 * (`attempt_token` / `lease_until`).
 *
 * Changing nullability needs a table rebuild, so this is CREATE-new / INSERT-SELECT / DROP / RENAME.
 * No column in this schema has a SQL DEFAULT: fresh-create and migrated databases must produce
 * byte-identical schemas or Room's identity check rejects the migrated one at runtime.
 *
 * Destructive migration stays disabled — an existing `UPLOADED` row is a pointer to content that is
 * already on Drive and must survive the upgrade unchanged.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_asset_new` (
                `source_id` TEXT NOT NULL,
                `package_id` TEXT NOT NULL,
                `drive_path` TEXT,
                `display_name` TEXT NOT NULL,
                `mime_type` TEXT,
                `kind` TEXT NOT NULL,
                `size_bytes` INTEGER NOT NULL,
                `sha256` TEXT,
                `upload_state` TEXT NOT NULL,
                `local_uri` TEXT,
                `added_at` TEXT NOT NULL,
                `local_access_mode` TEXT NOT NULL,
                `canonical_type` TEXT NOT NULL,
                `error_code` TEXT,
                `error_message` TEXT,
                `retryable` INTEGER NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `next_retry_at` INTEGER,
                `attempt_token` TEXT,
                `lease_until` INTEGER,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`source_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `source_asset_new` (
                `source_id`, `package_id`, `drive_path`, `display_name`, `mime_type`, `kind`,
                `size_bytes`, `sha256`, `upload_state`, `local_uri`, `added_at`,
                `local_access_mode`, `canonical_type`, `error_code`, `error_message`, `retryable`,
                `attempt_count`, `next_retry_at`, `attempt_token`, `lease_until`, `updated_at`
            )
            SELECT
                `source_id`, `package_id`, `drive_path`, `display_name`, `mime_type`, `kind`,
                `size_bytes`, `sha256`,
                CASE WHEN `upload_state` IN ('PENDING', 'UPLOADING') THEN 'FAILED' ELSE `upload_state` END,
                `local_uri`, `added_at`,
                CASE WHEN `local_uri` IS NULL THEN 'NONE' ELSE 'PERSISTED_URI' END,
                CASE
                    WHEN `mime_type` = 'application/pdf' OR lower(`drive_path`) LIKE '%.pdf' THEN 'PDF'
                    WHEN `mime_type` LIKE '%wordprocessingml%' OR lower(`drive_path`) LIKE '%.docx' THEN 'DOCX'
                    WHEN `mime_type` LIKE '%presentationml%' OR lower(`drive_path`) LIKE '%.pptx' THEN 'PPTX'
                    WHEN `mime_type` IN ('text/markdown', 'text/x-markdown') OR lower(`drive_path`) LIKE '%.md' THEN 'MD'
                    WHEN `mime_type` = 'text/plain' OR lower(`drive_path`) LIKE '%.txt' OR `kind` = 'TEXT' THEN 'TXT'
                    WHEN `mime_type` IN ('image/jpeg', 'image/jpg')
                        OR lower(`drive_path`) LIKE '%.jpg' OR lower(`drive_path`) LIKE '%.jpeg' THEN 'JPG'
                    WHEN `mime_type` = 'image/png' OR lower(`drive_path`) LIKE '%.png' THEN 'PNG'
                    WHEN `mime_type` = 'image/webp' OR lower(`drive_path`) LIKE '%.webp' THEN 'WEBP'
                    WHEN `mime_type` IN ('image/heic', 'image/heic-sequence') OR lower(`drive_path`) LIKE '%.heic' THEN 'HEIC'
                    WHEN `mime_type` IN ('image/heif', 'image/heif-sequence') OR lower(`drive_path`) LIKE '%.heif' THEN 'HEIF'
                    ELSE 'UNKNOWN'
                END,
                CASE WHEN `upload_state` IN ('PENDING', 'UPLOADING') THEN 'PROCESS_INTERRUPTED' ELSE NULL END,
                CASE WHEN `upload_state` IN ('PENDING', 'UPLOADING') THEN '上传被中断，请重试' ELSE NULL END,
                CASE WHEN `upload_state` IN ('PENDING', 'UPLOADING', 'FAILED') THEN 1 ELSE 0 END,
                0, NULL, NULL, NULL, `added_at`
            FROM `source_asset`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `source_asset`")
        db.execSQL("ALTER TABLE `source_asset_new` RENAME TO `source_asset`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_asset_drive_path` ON `source_asset` (`drive_path`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_asset_package_id_upload_state` " +
                "ON `source_asset` (`package_id`, `upload_state`)"
        )
    }
}

/**
 * v2 → v3 (ZLQ-84 / ZLQ-89 §3): FR-13 usage persistence gains the two Session seconds counters.
 *
 * Plain additive `ALTER TABLE`, so existing rows pick up NULL — which is exactly the intended reading
 * of "historical data is never backfilled". No SQL DEFAULT clause: a fresh-create and a migrated
 * database must produce byte-identical schemas or Room's identity check rejects the migrated one.
 *
 * v2 is already shipped by [MIGRATION_1_2], so this migration may only ever be added to, never
 * rewritten. Destructive fallback stays disabled.
 *
 * Held as a plain statement list so the JVM migration test executes exactly what ships.
 */
val MIGRATION_2_3_SQL: List<String> = listOf(
    "ALTER TABLE `task_run` ADD COLUMN `active_seconds` REAL",
    "ALTER TABLE `task_run` ADD COLUMN `duration_seconds` REAL",
)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
    }
}

/**
 * v3 → v4 (ZLQ-130 / ZLQ-132 design §3.7): the upload lease gains an owner, and deletion gains a
 * durable tombstone.
 *
 * Purely additive, so every existing row keeps its business columns untouched and picks up NULL for
 * the new nullable ones. A `lease_owner_id` of NULL on a historical `UPLOADING` row is itself the
 * orphan signal (§3.2 condition 1): the upgrade terminated the process that held it, so the first v4
 * start converges it rather than this migration rewriting `attempt_count`.
 *
 * `delete_pending` is the one column with a SQL DEFAULT, and the entity declares the matching
 * `@ColumnInfo(defaultValue = "0")` — fresh-create and migrated schemas still agree, which is what
 * Room's identity check demands.
 *
 * `remote_generation` exists so the backend does not have to force a fifth migration when server-side
 * fencing lands, but nothing writes it in this build (ZLQ-136 C3): it stays NULL. A locally invented
 * generation would be read as authoritative later, which is more dangerous than an empty column.
 *
 * The recovery index is created under the name the entity declares, not Room's derived default, so
 * the migrated database and a fresh v4 database are byte-identical.
 *
 * Destructive fallback stays disabled. v3 is already shipped, so this migration may only ever be
 * added to, never rewritten.
 */
val MIGRATION_3_4_SQL: List<String> = listOf(
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
)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
    }
}
