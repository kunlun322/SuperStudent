package com.superstudent.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        AccountEntity::class,
        LearningPackageEntity::class,
        SourceAssetEntity::class,
        TaskRunEntity::class,
        ArtifactEntity::class,
        FlashcardProgressEntity::class,
        ExerciseProgressEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class SsDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun packageDao(): PackageDao
    abstract fun sourceDao(): SourceDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun flashcardProgressDao(): FlashcardProgressDao
    abstract fun exerciseProgressDao(): ExerciseProgressDao

    suspend fun <T> inTransaction(block: suspend () -> T): T = withTransaction { block() }

    companion object {
        @Volatile
        private var instance: SsDatabase? = null

        fun get(context: Context): SsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SsDatabase::class.java,
                    "superstudent.db"
                )
                    // Explicit migrations only; a destructive fallback would silently drop the
                    // pointers to content already on Drive, and the task_run rows whose usage
                    // seconds are the only copy of a finished run's measurement.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }

        /**
         * Forces the open (and therefore any pending migration) at a known point, so a failure lands
         * in [DatabaseBootstrapState] instead of surfacing later as an exception inside whichever
         * coroutine happened to query first. After a failed migration the database cannot drive the
         * UI either, which is why the result is published to a holder that does not depend on Room.
         */
        fun bootstrap(context: Context): DatabaseBootstrapState.Bootstrap = runCatching {
            get(context).openHelper.writableDatabase
            DatabaseBootstrapState.Bootstrap.Ready
        }.getOrElse { DatabaseBootstrapState.Bootstrap.Failed(DatabaseBootstrapFailure.of(it)) }
            .also { DatabaseBootstrapState.publish(it) }

        internal fun resetForTest() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
