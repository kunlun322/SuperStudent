package com.superstudent.core.repository

import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.Credits
import com.superstudent.core.model.SessionUsageSnapshot

/**
 * First-write-wins merge of a terminal usage snapshot into the row stored for a `(taskId, attempt)`
 * (ZLQ-89 §4). Kept pure so the idempotency rule is testable without Room.
 *
 * [current] is the row just read back inside the transaction and owns the two seconds counters: the
 * cloud's `duration_seconds` keeps growing long after the task ends, so the first value collected for
 * an attempt *is* the measurement and every later read of that same attempt is ignored. A field the
 * first terminal response lacked stays fillable by a re-entry into the same terminal state. A new
 * attempt has its own row and therefore its own snapshot.
 *
 * [intent] carries the terminal state, error and timestamps the caller decided on. Its credits are
 * the base: a positive remote total overrides them, an absent or zero one does not, so a usage read
 * that came back empty can never erase the sum the event stream already accumulated.
 */
internal fun mergeTerminalUsage(
    current: TaskRunEntity,
    intent: TaskRunEntity,
    snapshot: SessionUsageSnapshot,
): TaskRunEntity = intent.copy(
    activeSeconds = current.activeSeconds ?: snapshot.activeSeconds,
    durationSeconds = current.durationSeconds ?: snapshot.durationSeconds,
    credits = snapshot.totalCredits
        ?.takeIf { it > 0.0 }
        ?.let { Credits.round(it) }
        ?: intent.credits,
)

/**
 * Maps a seconds counter read back from Drive `task.json` (ZLQ-89 §2, §5).
 *
 * A v1 file carries a forced `0` in both fields — the old client encoded its defaults, which is what
 * made an unmeasured run look like a real zero-second one — so a zero under `schemaVersion == 1`
 * means "never collected" and normalizes back to null. A v1 file with a genuine non-zero value keeps
 * it; v2 maps by field presence, where absent already decodes to null.
 *
 * This is read-side normalization only: nothing is recomputed from timestamps, backfilled from
 * history, or re-queried from the cloud.
 */
internal fun restoredSeconds(schemaVersion: Int, value: Double?): Double? =
    if (schemaVersion == 1 && value == 0.0) null else value
