package com.superstudent.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

/**
 * How hard a chain of idempotent calls pushes back against a connection failure (ZLQ-138 §6).
 *
 * [maxAttempts] counts the first try, so the default is one attempt plus two retries. Attempts are
 * bounded by count rather than by a wall-clock budget on purpose: a budget needs a clock, and the only
 * clock that behaves under `runTest` is the virtual one, which would make the bound untestable. The
 * count is exact in both.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val timeoutAttempts: Int = 2,
    val firstBackoffMillis: Long = 400L,
    val backoffMultiplier: Long = 3L,
    val jitterMillis: Long = 200L,
) {
    init {
        require(maxAttempts >= 1) { "at least one attempt" }
        require(timeoutAttempts in 1..maxAttempts) { "timeout attempts must not exceed max attempts" }
    }

    companion object {
        /**
         * The login chain's policy.
         *
         * Sized against the timeouts the client is built with: a black-holed route costs the full 10s
         * connect timeout, so the two timeout attempts put the worst case at roughly 20s of "登录中…"
         * before the student gets an answer, while a fast failure — DNS above all, which is what a
         * blocked route to one host looks like — gets all three attempts inside about two seconds.
         */
        val LOGIN = RetryPolicy()
    }
}

/**
 * Runs [block], repeating it while it fails for a retryable connection reason.
 *
 * Only connection-class failures are repeated, and only ones [NetworkCauses] marks retryable: a 4xx, a
 * conflict, an ambiguous identity and a cancelled call all pass straight through, so this cannot turn
 * a decision the server made into a loop. That is what makes it safe to wrap calls that are not GETs —
 * `createIdentity` carries an `Idempotency-Key` the server deduplicates on, and the profile writes are
 * whole-object overwrites.
 *
 * Cancellation is rethrown rather than retried, and re-checked before each backoff: OkHttp reports a
 * cancelled call as an `IOException`, so without the second check a coroutine cancelled during a
 * connect would spend its own backoff sleeping and then try again.
 */
suspend fun <T> retryOnConnectionFailure(
    policy: RetryPolicy = RetryPolicy.LOGIN,
    random: Random = Random.Default,
    block: suspend () -> T,
): T {
    var attempt = 0
    var backoff = policy.firstBackoffMillis
    while (true) {
        attempt++
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val cause = NetworkCauses.of(t)?.cause
            if (cause == null || !cause.retryable) throw t
            val limit = if (cause.timeout) policy.timeoutAttempts else policy.maxAttempts
            if (attempt >= limit) throw t
            currentCoroutineContext().ensureActive()
            val jitter = if (policy.jitterMillis > 0) random.nextLong(policy.jitterMillis + 1) else 0L
            delay(backoff + jitter)
            backoff *= policy.backoffMultiplier
        }
    }
}
