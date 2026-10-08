package com.superstudent.app.features.auth

import com.superstudent.core.network.NetworkCauses
import com.superstudent.core.network.PresignedTransferException
import com.superstudent.core.network.PresignedUrlExpiredException
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.qcaCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * What a failed login says and what it does to the credential (ZLQ-138 §6).
 *
 * Both are decisions the student feels. The copy is what turns 「网络不可用」 — which sent an overseas
 * report chasing a global block that does not exist — into a sentence that names a cause and an action.
 * The credential rule is the other half: deleting a PAT that no server ever judged cost the reporter a
 * re-paste on every attempt, and keeping one that *was* judged is how a device ends up signing into the
 * wrong account.
 */
class LoginFailurePolicyTest {

    private val storageHost = "storage.cn-hangzhou.example.com"

    private fun presigned(cause: Throwable) = PresignedTransferException(storageHost, cause)

    // ---- copy -------------------------------------------------------------------------------------

    @Test
    fun `every connection cause gets a sentence that names a cause and an action`() {
        val cases = listOf(
            UnknownHostException("api.qoder.com") to "DNS",
            SocketTimeoutException("connect timed out") to "超时",
            SocketTimeoutException("Read timed out") to "超时",
            SSLHandshakeException("bad certificate") to "安全连接失败",
            IOException("Connection reset") to "中断",
            IOException("Network is unreachable") to "无法到达",
            IOException("Connection refused") to "拒绝连接",
        )
        cases.forEach { (throwable, expectedFragment) ->
            val copy = LoginFailurePolicy.describe(throwable)
            assertTrue("${throwable.message} produced no copy", copy != null)
            assertTrue("「$copy」 does not say $expectedFragment", copy!!.contains(expectedFragment))
            assertTrue("「$copy」 gives the student nothing to do", copy.contains("重试"))
        }
    }

    @Test
    fun `the copy for a failure that cannot be retried does not invite a retry that will fail again`() {
        val tls = LoginFailurePolicy.describe(SSLHandshakeException("bad certificate")).orEmpty()
        // The action is "check the clock", not "press again": a certificate the device does not trust
        // fails identically on every attempt.
        assertTrue(tls.contains("系统时间"))
        assertFalse(NetworkCauses.isRetryable(SSLHandshakeException("bad certificate")))
    }

    @Test
    fun `a presigned failure names the storage host, because nothing else in the app knows it`() {
        val copy = LoginFailurePolicy.describe(presigned(UnknownHostException(storageHost))).orEmpty()
        assertTrue("「$copy」 lost the host", copy.contains(storageHost))
        assertTrue(copy.contains("DNS"))
        val expired = LoginFailurePolicy.describe(PresignedUrlExpiredException(403)).orEmpty()
        assertTrue(expired.contains("链接"))
    }

    @Test
    fun `no copy carries the URL, the signature or the token from the failure it describes`() {
        val dirty = presigned(
            IOException(
                "Failed to connect to https://$storageHost/o/profile.json?X-Goog-Signature=8f3a1c " +
                    "with Authorization: Bearer pt-AbCdEfGhIj0123456789",
            ),
        )
        val copy = LoginFailurePolicy.describe(dirty).orEmpty()
        assertFalse(copy.contains("http", ignoreCase = true))
        assertFalse(copy.contains("Signature", ignoreCase = true))
        assertFalse(copy.contains("pt-"))
        assertFalse(copy.contains("Bearer", ignoreCase = true))
        // The host is the part worth keeping, and it is not a secret.
        assertTrue(copy.contains(storageHost))
    }

    @Test
    fun `a failure the server decided keeps the caller's own copy`() {
        assertNull(LoginFailurePolicy.describe(rejected()))
        assertNull(LoginFailurePolicy.describe(IllegalStateException("bad state")))
    }

    // ---- credential -------------------------------------------------------------------------------

    @Test
    fun `a connection failure on a first login keeps the credential, because nothing judged it`() {
        assertTrue(LoginFailurePolicy.keepsCredential(UnknownHostException("api.qoder.com"), identityBound = false))
        assertTrue(LoginFailurePolicy.keepsCredential(SocketTimeoutException("connect timed out"), identityBound = false))
        // Even the causes that are not worth an automatic retry: a certificate problem says nothing
        // about whether the token is valid, and re-pasting it would not fix the clock.
        assertTrue(LoginFailurePolicy.keepsCredential(SSLHandshakeException("bad cert"), identityBound = false))
        assertTrue(LoginFailurePolicy.keepsCredential(presigned(UnknownHostException("h")), identityBound = false))
    }

    @Test
    fun `a connection failure on a device that still has an identity bound drops it`() {
        // `logout` keeps the Room rows on purpose, so an identity is still bound after one — and
        // `AuthSession.resolve` publishes `Authenticated(identityId)` for any usable credential it finds
        // next to an identity. Keeping a *different* student's token there signs the next cold start into
        // the previous account.
        assertFalse(LoginFailurePolicy.keepsCredential(UnknownHostException("api.qoder.com"), identityBound = true))
    }

    @Test
    fun `a failure the server decided drops the credential, as it always did`() {
        assertFalse(LoginFailurePolicy.keepsCredential(rejected(), identityBound = false))
        assertFalse(LoginFailurePolicy.keepsCredential(IllegalStateException("bad state"), identityBound = false))
    }

    @Test
    fun `a rejected token still reaches the login screen as an expired login, not as a network failure`() =
        runTest {
            val mapped = runCatching { qcaCall { throw rejected() } }.exceptionOrNull()
            assertTrue(mapped is QcaException)
            assertEquals(QcaErrorKind.AUTH_EXPIRED, (mapped as QcaException).kind)
            assertNull(mapped.network)
        }

    // ---- diagnostics ------------------------------------------------------------------------------

    @Test
    fun `the log line carries the hop, the cause and the exception class`() {
        val lines = mutableListOf<String>()
        LoginDiagnostics { lines += it }.loginFailed(UnknownHostException("api.qoder.com"))
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("login_failed"))
        assertTrue(line.contains("hop=api cause=dns"))
        assertTrue(line.contains("java.net.UnknownHostException"))
    }

    @Test
    fun `the log line keeps the presigned host but never its signature`() {
        val lines = mutableListOf<String>()
        LoginDiagnostics { lines += it }.loginFailed(
            IOException(
                "unexpected end of stream on https://$storageHost/o/profile.json?X-Goog-Signature=8f3a1c",
                presigned(IOException("boom")),
            ),
        )
        val line = lines.single()
        assertTrue("the host is the diagnosis", line.contains(storageHost))
        assertFalse("the query is the signature", line.contains("8f3a1c"))
        assertFalse(line.contains("X-Goog-Signature"))
    }

    @Test
    fun `the shape the transfer actually throws keeps its signed URL out of the log too`() {
        val lines = mutableListOf<String>()
        // Not the mirror image of the test above but the real one: `PresignedTransfer.transport` wraps
        // the raw OkHttp failure, so the throwable carrying the signed URL is the *cause*. It is also the
        // one `NetworkCauses` classifies from, which is what makes this the leak that would ship.
        LoginDiagnostics { lines += it }.loginFailed(
            presigned(
                IOException("unexpected end of stream on https://$storageHost/o/profile.json?X-Goog-Signature=8f3a1c"),
            ),
        )
        val line = lines.single()
        assertFalse("the query is the signature", line.contains("8f3a1c"))
        assertFalse(line.contains("X-Goog-Signature"))
        assertFalse("no message text from the classified throwable either", line.contains("unexpected end of stream"))
        assertTrue("the host is the diagnosis", line.contains("host=$storageHost"))
        assertTrue("classified from the cause, not from the wrapper", line.contains("cause=reset"))
    }

    @Test
    fun `the line names both throwables once each, under different keys`() {
        val lines = mutableListOf<String>()
        LoginDiagnostics { lines += it }.loginFailed(presigned(UnknownHostException(storageHost)))
        val line = lines.single()
        // `exception=` is the throwable that carried the classification, `thrown=` what the chain raised.
        // One key each: a reader parsing this after a field failure must not have to guess which is which.
        assertEquals(1, Regex("""exception=""").findAll(line).count())
        assertEquals(1, Regex("""thrown=""").findAll(line).count())
        assertTrue(line.contains("exception=java.net.UnknownHostException"))
        assertTrue(line.contains("thrown=com.superstudent.core.network.PresignedTransferException"))
    }

    @Test
    fun `the log line redacts a token that rode along in a message`() {
        val lines = mutableListOf<String>()
        LoginDiagnostics { lines += it }.loginFailed(
            IOException("rejected pt-AbCdEfGhIj0123456789 for Authorization: Bearer pt-AbCdEfGhIj0123456789"),
        )
        val line = lines.single()
        assertFalse(line.contains("AbCdEfGhIj0123456789"))
        assertTrue(line.contains("pt-[REDACTED]"))
    }

    @Test
    fun `a failure that was not a connection failure says so instead of inventing a cause`() {
        val lines = mutableListOf<String>()
        LoginDiagnostics { lines += it }.loginFailed(rejected())
        assertTrue(lines.single().contains("cause=none"))
    }

    private fun rejected() = QcaException(
        QcaErrorKind.AUTH_EXPIRED, 401, "unauthorized", "req_1", "HTTP 401 unauthorized",
    )
}
