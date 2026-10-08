package com.superstudent.core.network

import com.superstudent.core.model.PresignedUrlResponse
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * The classification ZLQ-138 §5.1 asks for: which hop failed, and why.
 *
 * Pinned here rather than at the call sites because everything downstream reads it — the retry loop
 * decides whether to try again from `retryable`, the student-facing copy is chosen per cause, and the
 * logcat line that answers "was it the API host or the storage host" is `wire()`. A wrong answer is not
 * a wrong message, it is a wrong diagnosis.
 */
class NetworkCausesTest {

    // ---- cause table ------------------------------------------------------------------------------

    @Test
    fun `each throwable class reports the cause a student's network actually produced`() {
        val cases = listOf(
            UnknownHostException("api.qoder.com") to NetworkCause.DNS,
            SocketTimeoutException("connect timed out") to NetworkCause.CONNECT_TIMEOUT,
            SocketTimeoutException("Read timed out") to NetworkCause.READ_TIMEOUT,
            InterruptedIOException("timeout") to NetworkCause.CALL_TIMEOUT,
            InterruptedIOException("Canceled") to NetworkCause.CANCELED,
            SSLHandshakeException("Certificate not trusted") to NetworkCause.TLS,
            // The specific class first: ConnectException's own branch would say "refused".
            NoRouteToHostException("No route to host") to NetworkCause.UNREACHABLE,
            ConnectException("Failed to connect") to NetworkCause.CONNECTION_REFUSED,
            SocketException("Connection reset") to NetworkCause.RESET,
            // Android reports a dead radio through the message, not the type.
            ConnectException("ENETUNREACH (Network is unreachable)") to NetworkCause.UNREACHABLE,
            IOException("unexpected end of stream") to NetworkCause.RESET,
            IOException("something nobody has seen before") to NetworkCause.OTHER,
        )
        cases.forEach { (throwable, expected) ->
            assertEquals(
                "${throwable.javaClass.simpleName}: ${throwable.message}",
                expected,
                NetworkCauses.of(throwable)?.cause,
            )
        }
    }

    @Test
    fun `a cause that cannot be fixed by trying again is not marked retryable`() {
        assertFalse(NetworkCauses.isRetryable(SSLHandshakeException("bad cert")))
        assertFalse(NetworkCauses.isRetryable(InterruptedIOException("Canceled")))
        assertFalse(NetworkCauses.isRetryable(PresignedUrlExpiredException(403)))
        // A route that is black-holed costs the whole connect timeout per attempt, so these get a
        // smaller budget than the causes that fail in milliseconds.
        assertTrue(NetworkCause.CONNECT_TIMEOUT.timeout)
        assertTrue(NetworkCause.READ_TIMEOUT.timeout)
        assertTrue(NetworkCause.CALL_TIMEOUT.timeout)
        assertFalse(NetworkCause.DNS.timeout)
        assertTrue(NetworkCauses.isRetryable(UnknownHostException("api.qoder.com")))
    }

    // ---- what is not a connection failure ---------------------------------------------------------

    @Test
    fun `a failure that was decided by a status code is not a connection failure`() {
        val rejected = QcaException(
            QcaErrorKind.AUTH_EXPIRED, 401, "unauthorized", "req_1", "HTTP 401",
            // Even with a DNS failure as the cause: the mapping was made by the 401, and reporting this
            // as a connection failure would put a rejected token into the retry loop.
            cause = UnknownHostException("api.qoder.com"),
        )
        assertNull(NetworkCauses.of(rejected))
        assertFalse(NetworkCauses.isRetryable(rejected))
    }

    @Test
    fun `a missing credential is never reported as a connection failure`() {
        // An IOException by construction (ZLQ-119 §4.3), so it reaches this classifier; no request was
        // ever made, and "the network is down" would send the diagnosis in the wrong direction entirely.
        assertNull(NetworkCauses.of(MissingCredentialException()))
        assertNull(NetworkCauses.of(IOException("wrapper", MissingCredentialException())))
    }

    @Test
    fun `a failure with no IO anywhere in its chain is not one either`() {
        assertNull(NetworkCauses.of(null))
        assertNull(NetworkCauses.of(IllegalStateException("bad state")))
        assertNull(NetworkCauses.of(Exception("wrapper", IllegalStateException("inner"))))
    }

    // ---- hop --------------------------------------------------------------------------------------

    @Test
    fun `a failure on the way to a signed storage URL is reported as the presigned hop, with its host`() {
        val inner = UnknownHostException("storage.cn-hangzhou.example.com")
        val wrapped = PresignedTransferException("storage.cn-hangzhou.example.com", inner)
        val failure = NetworkCauses.of(wrapped)
        assertEquals(NetworkHop.PRESIGNED, failure?.hop)
        // The wrapper says where, the cause says what. Naming the wrapper instead would tell a reader
        // only that a transfer failed, which the hop already said.
        assertEquals(NetworkCause.DNS, failure?.cause)
        assertEquals("java.net.UnknownHostException", failure?.exception)
        assertEquals("storage.cn-hangzhou.example.com", failure?.host)
        assertEquals(
            "hop=presigned cause=dns host=storage.cn-hangzhou.example.com " +
                "exception=java.net.UnknownHostException",
            failure?.wire(),
        )
    }

    @Test
    fun `a failure on the way to the API host is reported as the api hop`() {
        val failure = NetworkCauses.of(UnknownHostException("api.qoder.com"))
        assertEquals(NetworkHop.API, failure?.hop)
        assertNull("the api host is compiled in, so naming it adds nothing", failure?.host)
        assertEquals("hop=api cause=dns host=- exception=java.net.UnknownHostException", failure?.wire())
    }

    @Test
    fun `a storage host that answered is still the presigned hop`() {
        assertEquals(NetworkHop.PRESIGNED, NetworkCauses.of(PresignedUrlExpiredException(403))?.hop)
        assertEquals(NetworkHop.PRESIGNED, NetworkCauses.of(PresignedStatusException("download", 500))?.hop)
    }

    @Test
    fun `the transfer tags a real refused connection with the host it was dialling`() {
        // No server: port 1 on the loopback refuses immediately, which is the fastest deterministic
        // transport failure there is. The URL carries a fake signature, because the point is that the
        // host survives onto the exception and the query does not. A bare client rather than
        // `NetworkFactory.transferClient()`: its logger writes through `android.util.Log`, which does not
        // exist on the JVM, and the logger is pinned by `NetworkFactoryWiringTest` instead.
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val thrown = runCatching {
            PresignedTransfer(client).download(
                PresignedUrlResponse(url = "https://127.0.0.1:1/o/profile.json?X-Goog-Signature=8f3a1c"),
            )
        }.exceptionOrNull()
        assertTrue("the download must fail, got $thrown", thrown is PresignedTransferException)
        assertEquals("127.0.0.1", (thrown as PresignedTransferException).host)
        assertFalse(thrown.message.orEmpty().contains("Signature"))
        val failure = NetworkCauses.of(thrown)
        assertEquals(NetworkHop.PRESIGNED, failure?.hop)
        assertEquals(NetworkCause.CONNECTION_REFUSED, failure?.cause)
    }

    // ---- through the mapper -----------------------------------------------------------------------

    @Test
    fun `qcaCall keeps the classification on the exception it hands the data layer`() {
        val mapped = ErrorMapper.map(PresignedTransferException("storage.example.com", UnknownHostException("x")))
        assertEquals(QcaErrorKind.NETWORK, mapped.kind)
        val failure = mapped.network
        assertEquals(NetworkHop.PRESIGNED, failure?.hop)
        assertEquals(NetworkCause.DNS, failure?.cause)
        // And `of` reads it back off the mapped exception rather than re-walking, which is what lets a
        // call site that only has the QcaException still tell the two hops apart.
        assertSame(failure, NetworkCauses.of(mapped))
        assertTrue(NetworkCauses.isRetryable(mapped))
    }

    @Test
    fun `a mapped failure that is not a network failure carries no classification`() {
        val mapped = ErrorMapper.map(IllegalStateException("bad state"))
        assertEquals(QcaErrorKind.UNKNOWN, mapped.kind)
        assertNull(mapped.network)
    }

    // ---- bounds -----------------------------------------------------------------------------------

    @Test
    fun `a chain longer than the bound terminates instead of walking forever`() {
        var deep: Throwable = UnknownHostException("api.qoder.com")
        repeat(30) { deep = IOException("layer", deep) }
        // The specific cause is past the bound, so the answer degrades to the fallback rather than
        // looping. Terminating at all is the assertion.
        assertEquals(NetworkCause.OTHER, NetworkCauses.of(deep)?.cause)
        assertEquals("java.io.IOException", NetworkCauses.of(deep)?.exception)
    }

    @Test
    fun `the specific cause wins over a wrapper whose own message says nothing`() {
        val wrapped = PresignedTransferException("host", SocketTimeoutException("connect timed out"))
        assertEquals(NetworkCause.CONNECT_TIMEOUT, NetworkCauses.of(wrapped)?.cause)
        val nested = IOException("outer", IOException("middle", UnknownHostException("api.qoder.com")))
        assertEquals(NetworkCause.DNS, NetworkCauses.of(nested)?.cause)
        assertEquals("java.net.UnknownHostException", NetworkCauses.of(nested)?.exception)
    }
}
