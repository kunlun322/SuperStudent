package com.superstudent.core.upload

import com.superstudent.core.security.Hashing

/**
 * The `source_recovery` line's trigger (design §5.5). Closed set, explicit wire values: the string
 * reaches a log a human reads back after a field failure, so it must not be a reflected name R8 may
 * rename, and it must not be inventable per call site — a trigger nobody declared cannot be grepped
 * for, which is the whole point of the contract.
 */
enum class SourceRecoveryTrigger(val wire: String) {
    PROCESS_START("PROCESS_START"),
    LOGIN("LOGIN"),
    NETWORK("NETWORK"),
    OUTCOME("OUTCOME"),
    DEADLINE("DEADLINE"),
    PAGE("PAGE"),
}

enum class SourceLeaseEvent(val wire: String) {
    CLAIM("CLAIM"),
    HEARTBEAT("HEARTBEAT"),
    INTERRUPT("INTERRUPT"),
    RELEASE("RELEASE"),
}

enum class SourceDeleteEvent(val wire: String) {
    MARK("MARK"),
    CANCEL("CANCEL"),
    REMOTE_DELETE("REMOTE_DELETE"),
    MANIFEST("MANIFEST"),
    DONE("DONE"),
    RETRY("RETRY"),
}

/**
 * Why one lease line was written. Closed for the same reason as the triggers: §3.2 has four recovery
 * conditions and one 「不得回收」 branch, and telling them apart in a log is the only way to prove after
 * the fact that a release came from the condition the design allows rather than from a bare
 * `lease_until < now`.
 */
enum class SourceLeaseReason(val wire: String) {
    /** This process claimed the row and holds the execution lock. */
    ATTEMPT_START("ATTEMPT_START"),
    RENEWED("RENEWED"),
    PROGRESS("PROGRESS"),
    /** The renew guard returned 0 rows: a recovery pass took the row while we were mid-PUT. */
    LEASE_LOST("LEASE_LOST"),
    /** §3.2 condition 1/3: no owner recorded, or our own id with no live attempt registered. */
    ORPHAN_NO_OWNER("ORPHAN_NO_OWNER"),
    /** §3.2 condition 2: the other owner's kernel lock was free, so that process is gone. */
    ORPHAN_DEAD_OWNER("ORPHAN_DEAD_OWNER"),
    /** §3.2 condition 4: live owner with no progress for the full window. */
    NO_PROGRESS("NO_PROGRESS"),
    /** §3.2 「不得回收」: the owner is provably alive, so only an interrupt request was written. */
    OWNER_ALIVE("OWNER_ALIVE"),
    /** The attempt finished, whichever way it finished. */
    ATTEMPT_END("ATTEMPT_END"),
}

/**
 * The three §5.5 log lines, formatted in exactly one place.
 *
 * Kept in `core` rather than in each caller so a JVM test can assert the shipped text — an
 * interpolated string built at five call sites is five chances to drop a field, and the field list is
 * what makes a pass auditable.
 *
 * Nothing here may carry a PAT, an `Authorization` header, a presigned URL, a full local URI, file
 * bytes or a raw owner UUID. The two ids that *are* identifying — `lease_owner_id` and
 * `attempt_token` — go through [hash8].
 */
object SourceRecoveryLog {

    /**
     * Eight hex characters of the SHA-256, enough to tell two owners apart across a log and to
     * correlate a claim with its release, without printing a value that identifies the device
     * install. A missing id reads as `-` rather than as the hash of an empty string.
     */
    fun hash8(value: String?): String =
        if (value.isNullOrBlank()) "-" else Hashing.sha256Hex(value).take(8)

    /**
     * @param nextDueMs millis *from now* until the earliest row becomes actionable, or null when
     *   nothing is pending. Relative on purpose: §5.5 marks the field `<redacted>`, and an absolute
     *   epoch would publish the wall clock of whoever is being diagnosed. A relative offset carries
     *   the same scheduling information and nothing else.
     */
    fun recovery(
        trigger: SourceRecoveryTrigger,
        scanned: Int,
        interrupted: Int,
        enqueued: Int,
        nextDueMs: Long?,
    ): String = "source_recovery trigger=${trigger.wire} scanned=$scanned interrupted=$interrupted" +
        " enqueued=$enqueued nextDueMs=${nextDueMs ?: "-"}"

    fun lease(
        sourceId: String,
        event: SourceLeaseEvent,
        ownerId: String?,
        attemptToken: String?,
        reason: SourceLeaseReason,
    ): String = "source_lease sourceId=$sourceId event=${event.wire}" +
        " owner=${hash8(ownerId)} attempt=${hash8(attemptToken)} reason=${reason.wire}"

    fun delete(sourceId: String, event: SourceDeleteEvent, reason: String? = null): String =
        "source_delete sourceId=$sourceId event=${event.wire}" +
            (if (reason.isNullOrBlank()) "" else " reason=$reason")
}
