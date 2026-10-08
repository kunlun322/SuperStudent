package com.superstudent.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Whether a Session status means the turn is over.
 *
 * `idle` counts, not just `terminated`: a Session can sit `idle` long after its turn ended — one was
 * still `idle` 96 minutes past completion — so waiting only for `terminated` never converged such a
 * run and its usage was never written (ZLQ-84). Single-sourced here because both the observer's
 * no-event probe and cancel confirmation have to agree on it (ZLQ-114 §5.3).
 */
fun sessionConcluded(status: String?): Boolean = status == "idle" || status == "terminated"

/**
 * What one cancel attempt established about the remote Session (ZLQ-114 §5.3).
 *
 * There is no third value on purpose. The cloud cancel API takes no Idempotency-Key, so this is
 * *state convergence*, not exactly-once delivery: either the Session is known to be finished, or it
 * is not known. "Not known" must never be reported as finished — the caller keeps the
 * `cleanup_pending` marker and retries, which is the only way a cancel that happened offline can
 * still stop the billing.
 */
enum class RemoteCancelOutcome {
    /** The Session is `idle`, `terminated`, or gone. Safe to delete tmp and project the terminal. */
    CONVERGED,

    /** Unproven either way: unreachable, rejected for auth, or still active after the poll budget. */
    UNCONFIRMED,
}

/**
 * Drives one Session to a confirmed cancel: GET before POST, POST only while it is still active, and
 * confirm after the POST (ZLQ-114 §5.3).
 *
 * The order is what makes a replay harmless. A crash between the POST and the marker clear used to
 * mean a second POST on the next attempt, with no way to tell whether the first one had landed; now
 * the replay GETs first, sees a concluded Session, and returns without touching the cancel endpoint
 * again — so no business side effect is produced twice.
 */
class RemoteCancelConverger(
    private val api: QcaApi,
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
    private val pollDelayMillis: Long = DEFAULT_POLL_DELAY_MILLIS,
) {

    suspend fun ensureCanceled(sessionId: String): RemoteCancelOutcome {
        when (probe(sessionId)) {
            Probe.CONCLUDED -> return RemoteCancelOutcome.CONVERGED
            // Unreachable or rejected: nothing is known about the Session, so nothing may be cleaned
            // up behind it. A 401/403 lands here too, which is exactly why the marker survives it.
            Probe.UNKNOWN -> return RemoteCancelOutcome.UNCONFIRMED
            Probe.ACTIVE -> Unit
        }

        try {
            qcaCall { api.cancelSession(sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QcaException) {
            // A rejected cancel is not a failed cancel: "already canceled / canceling / terminal" is
            // the state this call was trying to reach, so the authoritative GET below decides instead
            // of the POST's status code. Only a network failure short-circuits, because every
            // confirmation poll would fail the same way and burn the budget for nothing.
            if (e.kind == QcaErrorKind.NETWORK) return RemoteCancelOutcome.UNCONFIRMED
        }
        return confirm(sessionId)
    }

    private suspend fun confirm(sessionId: String): RemoteCancelOutcome {
        repeat(pollAttempts) {
            if (probe(sessionId) == Probe.CONCLUDED) return RemoteCancelOutcome.CONVERGED
            delay(pollDelayMillis)
        }
        return RemoteCancelOutcome.UNCONFIRMED
    }

    private enum class Probe { CONCLUDED, ACTIVE, UNKNOWN }

    /** A missing Session is concluded, not unknown: there is nothing left to cancel or to bill. */
    private suspend fun probe(sessionId: String): Probe = try {
        if (sessionConcluded(qcaCall { api.getSession(sessionId) }.status)) Probe.CONCLUDED else Probe.ACTIVE
    } catch (e: CancellationException) {
        throw e
    } catch (e: QcaException) {
        when (e.kind) {
            QcaErrorKind.SESSION_NOT_FOUND, QcaErrorKind.NOT_FOUND -> Probe.CONCLUDED
            else -> Probe.UNKNOWN
        }
    }

    companion object {
        const val DEFAULT_POLL_ATTEMPTS = 20
        const val DEFAULT_POLL_DELAY_MILLIS = 1_000L
    }
}
