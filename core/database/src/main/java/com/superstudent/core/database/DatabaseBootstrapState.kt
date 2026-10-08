package com.superstudent.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the local database could not be opened.
 *
 * A closed set with explicit wire values (ZLQ-133 §3): this string reaches a log line, so it must not
 * be a reflected class name that R8 is free to rename, and it must not carry the exception message —
 * a Room migration failure interpolates the database path and the failing SQL.
 */
enum class DatabaseBootstrapFailure(val wire: String) {
    /** No migration path exists from the on-disk `user_version` to this build's schema. */
    MIGRATION_MISSING("MIGRATION_MISSING"),

    /** A migration was found and threw part way through; SQLite rolled the transaction back. */
    MIGRATION_FAILED("MIGRATION_FAILED"),

    /** The schema opened but Room's identity check rejected it. */
    SCHEMA_INVALID("SCHEMA_INVALID"),

    /** Anything else: unreadable file, exhausted disk, a locked handle. */
    OPEN_FAILED("OPEN_FAILED");

    companion object {
        fun of(t: Throwable): DatabaseBootstrapFailure {
            var cause: Throwable? = t
            while (cause != null) {
                val message = cause.message.orEmpty()
                when {
                    message.contains("A migration from") && message.contains("was required but not found") ->
                        return MIGRATION_MISSING
                    message.contains("Room cannot verify the data integrity") ||
                        message.contains("identity hash") -> return SCHEMA_INVALID
                }
                cause = cause.cause
            }
            // Room wraps a throwing Migration in an IllegalStateException whose cause is the SQL
            // failure; a bare SQLiteException is the same branch reached without the wrapper.
            return if (t is android.database.sqlite.SQLiteException || t.cause is android.database.sqlite.SQLiteException) {
                MIGRATION_FAILED
            } else {
                OPEN_FAILED
            }
        }
    }
}

/**
 * Whether the local database is usable, decided once at process start.
 *
 * Deliberately independent of Room: the shell has to be able to say "the upgrade failed" while the
 * database itself is the thing that failed (ZLQ-132 §3.7). Read by the app shell to route to a
 * blocking page instead of any surface that would query.
 */
object DatabaseBootstrapState {

    sealed interface Bootstrap {
        /** Not opened yet. Never rendered as usable. */
        data object Checking : Bootstrap

        data object Ready : Bootstrap

        data class Failed(val failure: DatabaseBootstrapFailure) : Bootstrap
    }

    private val _state = MutableStateFlow<Bootstrap>(Bootstrap.Checking)
    val state: StateFlow<Bootstrap> = _state.asStateFlow()

    /**
     * Whether opening was attempted and rejected.
     *
     * Deliberately not "is ready": [Bootstrap.Checking] means nobody has decided yet, and a worker
     * that treated that as a refusal would drop its retry chain on a device whose database is
     * perfectly fine. Only an explicit [Bootstrap.Failed] may stop an entry point.
     */
    val isFailed: Boolean get() = _state.value is Bootstrap.Failed

    fun publish(next: Bootstrap) {
        _state.value = next
    }

    fun resetForTest() {
        _state.value = Bootstrap.Checking
    }
}
