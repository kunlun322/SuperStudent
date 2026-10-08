package com.superstudent.app.features.tasks

import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.TaskState

/** What recovery does with one active row (ZLQ-103 §3 recovery table). */
enum class RecoveryAction {
    /** A live in-process submit critical section still owns this sessionless row: leave it alone. */
    WAIT_FOR_SUBMIT,

    /** Sessionless and unowned: converge to `FAILED_RETRYABLE` / `START_INTERRUPTED`. */
    CONVERGE_START_INTERRUPTED,

    /** The user cancelled before a Session existed: honour the recorded intent. */
    CONVERGE_CANCELED,

    /** `QUEUED` with a Session: replay the idempotent send, then observe. Never a new Session. */
    REPLAY_AND_OBSERVE,

    /** Active with a Session: just re-attach the observer. */
    OBSERVE,

    /** Terminal or unparseable: nothing to recover. */
    NONE,
}

/**
 * The recovery decision table, kept free of Room and of the network so it can be tested directly.
 *
 * The invariant it protects: a row that never got a `session_id` has no cloud owner and no observer
 * that could ever conclude it, so it must be converged locally at the next start — but only when no
 * live submit critical section is holding it, and only ever into a terminal state. Rebuilding a
 * Session for such a row is forbidden: the previous process may already have created one that the
 * sessionId write never recorded, and a second Session would double-charge the student.
 */
object TaskRecovery {

    /** errorCode of a run killed between `createAttempt` and the sessionId write. */
    const val ERROR_CODE_START_INTERRUPTED = "START_INTERRUPTED"

    /** User-facing text; the raw interruption detail stays in logcat. */
    const val ERROR_MESSAGE_START_INTERRUPTED = "本次生成在启动阶段被中断，请重试"

    fun decide(state: String?, hasSession: Boolean, heldByLiveSubmit: Boolean): RecoveryAction {
        val parsed = runCatching { TaskState.valueOf(state ?: "") }.getOrNull() ?: return RecoveryAction.NONE
        if (parsed in TaskRunner.TERMINAL_STATES) return RecoveryAction.NONE
        if (hasSession) {
            return if (parsed == TaskState.QUEUED) RecoveryAction.REPLAY_AND_OBSERVE else RecoveryAction.OBSERVE
        }
        if (heldByLiveSubmit) return RecoveryAction.WAIT_FOR_SUBMIT
        return if (parsed == TaskState.CANCEL_REQUESTED) {
            RecoveryAction.CONVERGE_CANCELED
        } else {
            RecoveryAction.CONVERGE_START_INTERRUPTED
        }
    }

    fun isConverge(action: RecoveryAction): Boolean =
        action == RecoveryAction.CONVERGE_START_INTERRUPTED || action == RecoveryAction.CONVERGE_CANCELED

    /**
     * Whether converging this run may also take its package out of `GENERATING`. All four guards are
     * required: without them a package the student already moved on to — or one whose newer attempt
     * is still legitimately running — would be flipped back to `READY` and offer generation again
     * while work is in flight.
     */
    fun resetsPackage(
        packageStatus: String,
        packageLatestTaskId: String?,
        taskId: String,
        isLatestAttempt: Boolean,
        otherActiveRuns: Int,
    ): Boolean = packageStatus == PackageStatus.GENERATING.name &&
        packageLatestTaskId == taskId &&
        isLatestAttempt &&
        otherActiveRuns == 0
}
