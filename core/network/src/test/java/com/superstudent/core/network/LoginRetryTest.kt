package com.superstudent.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

/**
 * The login chain's retry loop (ZLQ-138 §6).
 *
 * The behaviour worth pinning is not "it retries" — it is the two edges that decide whether retrying is
 * safe at all. One: it must not retry anything a server decided, or a 409 from `createIdentity` becomes
 * three identities. Two: it must not outlive a cancellation, or a student who backs out of the login
 * screen leaves a coroutine that keeps dialling a host it was just told to stop dialling.
 *
 * Driven under `runTest` so the backoff is virtual time: the assertions are about *when* the next
 * attempt happens, not about how long the test took.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginRetryTest {

    /** No jitter, so the backoff schedule is an exact number. */
    private val policy = RetryPolicy(jitterMillis = 0)

    @Test
    fun `a call that succeeds is made exactly once`() = runTest {
        val calls = AtomicInteger()
        val result = retryOnConnectionFailure(policy) { calls.incrementAndGet() }
        assertEquals(1, result)
        assertEquals(1, calls.get())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a failure that resolves in milliseconds gets the full attempt budget with growing backoff`() =
        runTest {
            val calls = AtomicInteger()
            val result = retryOnConnectionFailure(policy) {
                if (calls.incrementAndGet() < 3) throw UnknownHostException("api.qoder.com")
                "ok"
            }
            assertEquals("ok", result)
            assertEquals(3, calls.get())
            // 400ms, then 400 * 3. The student is looking at 登录中… for that long, so the schedule is
            // part of the contract and not an implementation detail.
            assertEquals(1_600L, currentTime)
        }

    @Test
    fun `a failure that already spent a timeout gets a smaller budget`() = runTest {
        val calls = AtomicInteger()
        val thrown = runCatching {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                throw SocketTimeoutException("connect timed out")
            }
        }.exceptionOrNull()
        assertTrue(thrown is SocketTimeoutException)
        // A black-holed route costs the whole 10s connect timeout per attempt; three of them would hold
        // the login screen for half a minute to reach the same answer.
        assertEquals(policy.timeoutAttempts, calls.get())
        assertEquals(policy.firstBackoffMillis, currentTime)
    }

    @Test
    fun `a handshake failure is not retried, because it will fail identically`() = runTest {
        val calls = AtomicInteger()
        val failure = SSLHandshakeException("Certificate not trusted")
        val thrown = runCatching {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(1, calls.get())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a decision the server made is passed straight through`() = runTest {
        val calls = AtomicInteger()
        val conflict = QcaException(QcaErrorKind.CONFLICT, 409, "conflict", "req_1", "HTTP 409")
        val thrown = runCatching {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                throw conflict
            }
        }.exceptionOrNull()
        assertSame("a 409 from createIdentity must not become three identities", conflict, thrown)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a cancelled call is not another attempt`() = runTest {
        val calls = AtomicInteger()
        val thrown = runCatching {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                // What OkHttp reports when the call is cancelled from under a blocking read.
                throw IOException("Canceled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancellation of the coroutine itself is rethrown, not retried`() = runTest {
        val calls = AtomicInteger()
        val thrown = runCatching {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                throw CancellationException("scope went away")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a coroutine cancelled during the backoff does not spend it and try again`() = runTest {
        val calls = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            retryOnConnectionFailure(policy) {
                calls.incrementAndGet()
                throw UnknownHostException("api.qoder.com")
            }
        }
        // Undispatched, so the first attempt has run and the loop is inside its delay right now.
        assertEquals(1, calls.get())
        job.cancel()
        advanceUntilIdle()
        assertEquals("the student left the screen; nothing may dial again", 1, calls.get())
    }

    @Test
    fun `an unnormalized transport failure from the presigned hop is retried too`() = runTest {
        val calls = AtomicInteger()
        val result = retryOnConnectionFailure(policy) {
            if (calls.incrementAndGet() < 2) {
                // What the Drive calls in the login chain throw: not wrapped in `qcaCall`, so the loop
                // has to classify the raw throwable rather than a mapped one.
                throw PresignedTransferException("storage.example.com", UnknownHostException("x"))
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun `jitter stays inside its bound and spreads attempts apart`() = runTest {
        val bounded = RetryPolicy(jitterMillis = 200)
        val seen = mutableSetOf<Long>()
        repeat(8) {
            val before = currentTime
            runCatching {
                retryOnConnectionFailure(bounded, Random(it)) { throw UnknownHostException("x") }
            }
            val waited = currentTime - before
            // One wait per policy: maxAttempts=3 means two backoffs, 400 and 1200, each up to 200 longer.
            assertTrue("waited $waited", waited in 1_600..2_000)
            seen += waited
        }
        assertTrue("jitter that never varies is not jitter", seen.size > 1)
    }

    @Test
    fun `the policy refuses a budget that cannot make sense`() {
        runCatching { RetryPolicy(maxAttempts = 0) }.exceptionOrNull().let {
            assertTrue(it is IllegalArgumentException)
        }
        runCatching { RetryPolicy(maxAttempts = 2, timeoutAttempts = 3) }.exceptionOrNull().let {
            assertTrue(it is IllegalArgumentException)
        }
    }
}
