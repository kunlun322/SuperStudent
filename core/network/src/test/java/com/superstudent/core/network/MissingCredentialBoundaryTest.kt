package com.superstudent.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ZLQ-119 §4.3's exception boundary, driven through a real OkHttp client.
 *
 * The defect this pins: an interceptor that throws anything which is not an `IOException` does not
 * fail the call — OkHttp rethrows it on the dispatcher thread, where nothing catches it, so it becomes
 * `FATAL EXCEPTION: main`, the process dies, and `START_REDELIVER_INTENT` brings it straight back for
 * another pass over the same missing credential. What is asserted here is therefore not "the call
 * fails" but "the failure stays inside the call": synchronously as a throwable the caller can catch,
 * asynchronously as an `onFailure` the dispatcher delivers, and never as an uncaught exception.
 *
 * No server is involved: the interceptor throws before the chain proceeds, so the URL is never
 * dialled. That is the point — a missing credential must not cost a socket.
 */
class MissingCredentialBoundaryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    private fun request(): Request = Request.Builder()
        .url("https://127.0.0.1:1/api/v1/forward/sessions/sess_x")
        .build()

    private fun clientWithoutCredential() = NetworkFactory.apiClient(PatProvider { null }, false)

    @Test
    fun `a synchronous call with no credential throws a catchable IOException`() {
        val thrown = runCatching { clientWithoutCredential().newCall(request()).execute() }
            .exceptionOrNull()
            ?: throw AssertionError("the call must fail, not silently go out unauthenticated")
        assertTrue(
            "an IOException is what OkHttp treats as a call failure; anything else escapes the " +
                "dispatcher as an uncaught exception, which is the FATAL this replaces",
            thrown is IOException,
        )
        assertTrue(thrown is MissingCredentialException)
        assertEquals(MissingCredentialException.MESSAGE, thrown.message)
    }

    @Test
    fun `an asynchronous call reports it to onFailure and nothing reaches the uncaught handler`() {
        val escaped = AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped.set(e) }
        val delivered = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        try {
            clientWithoutCredential().newCall(request()).enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        delivered.set(e)
                        done.countDown()
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                        done.countDown()
                    }
                },
            )
            assertTrue("the callback never fired", done.await(10, TimeUnit.SECONDS))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertNull("the failure escaped the call and killed the dispatcher thread", escaped.get())
        assertTrue("onFailure must receive the typed failure", delivered.get() is MissingCredentialException)
    }

    @Test
    fun `the mapper normalizes it into a structured AUTH_REQUIRED with no HTTP code behind it`() {
        val mapped = ErrorMapper.map(MissingCredentialException())
        assertEquals(QcaErrorKind.AUTH_REQUIRED, mapped.kind)
        assertEquals(MissingCredentialException.CODE, mapped.code)
        assertNull("nothing was sent, so there is no status to report", mapped.httpCode)
        assertNull(mapped.requestId)
        assertNull(mapped.retryAfterSeconds)
        assertEquals(MissingCredentialException.MESSAGE, mapped.message)
        // Not a retryable failure: no backoff can produce a credential.
        assertFalse(mapped.kind == QcaErrorKind.RETRYABLE || mapped.kind == QcaErrorKind.NETWORK)
    }

    @Test
    fun `qcaCall hands the data layer the typed failure instead of an OkHttp type`() = runTest {
        val e = runCatching {
            qcaCall { clientWithoutCredential().newCall(request()).execute() }
        }.exceptionOrNull()
        assertTrue("qcaCall must normalize, not rethrow raw", e is QcaException)
        val qca = e as QcaException
        assertEquals(QcaErrorKind.AUTH_REQUIRED, qca.kind)
        assertTrue("the typed cause is kept for the log", qca.cause is MissingCredentialException)
    }

    @Test
    fun `a real 401 keeps its own kind, because the wire protocol did not change`() {
        // AUTH_REQUIRED is the never-sent case; AUTH_EXPIRED stays the server-rejected one, and the two
        // must not be collapsed or a 401 would start reporting "no credential stored" to the student.
        val source = read("core/network/src/main/java/com/superstudent/core/network/QcaException.kt")
        assertTrue(source.contains("code == 401 -> QcaErrorKind.AUTH_EXPIRED"))
        assertTrue(
            "the never-sent branch must be matched before the generic IOException one, or it would be " +
                "normalized as a network failure and retried forever",
            source.indexOf("is MissingCredentialException ->") in 1 until source.indexOf("is java.io.IOException ->"),
        )
    }

    @Test
    fun `the interceptor throws only the dedicated failure and never prints the credential`() {
        val source = read("core/network/src/main/java/com/superstudent/core/network/NetworkFactory.kt")
        val interceptor = source.substringAfter("private class AuthInterceptor")
            .substringBefore("private class RedactedLogger")
        assertTrue(interceptor.contains("throw MissingCredentialException()"))
        assertFalse(
            "a bare runtime exception from an interceptor is the FATAL this design removes",
            interceptor.contains("throw QcaException") ||
                interceptor.contains("throw IllegalStateException") ||
                interceptor.contains("throw RuntimeException") ||
                interceptor.contains("error("),
        )
        // The PAT reaches exactly one place: the header of the request that is about to be sent.
        assertEquals(1, interceptor.split("Bearer \$").size - 1)
        assertFalse(interceptor.contains("Log."))
        assertTrue(
            "the debug logger still redacts, so a present credential cannot leak through it",
            source.contains("Redaction.redact("),
        )
    }
}
