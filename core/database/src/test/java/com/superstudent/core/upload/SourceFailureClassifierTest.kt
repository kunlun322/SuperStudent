package com.superstudent.core.upload

import com.superstudent.core.network.PresignedStatusException
import com.superstudent.core.network.PresignedTransferException
import com.superstudent.core.network.PresignedUrlExpiredException
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The classifier decides two things the UI cannot recover from later: which failures are retried
 * without the student, and which text reaches the screen. Issue ZLQ-91 P0-5 requires that text to be
 * a fixed template per code, because the raw cause of an upload failure routinely carries a
 * presigned URL, an HTTP body or a credential.
 */
class SourceFailureClassifierTest {

    /** What a real Drive rejection looks like from inside OkHttp: everything at once. */
    private val dirtyMessage =
        "PUT https://drive.example.com/o/source?X-Goog-Signature=8f3a1c&key=AKIAEXAMPLE " +
            "failed: 403 {\"error\":{\"message\":\"token pt-AbCdEfGhIj0123456789AbCd rejected for " +
            "user 01a0efc1-93b1-724a-a4db-695e303b4413 (张三)\"}}"

    private fun qca(kind: QcaErrorKind, retryAfter: Long? = null) = QcaException(
        kind = kind,
        httpCode = 403,
        code = "forbidden",
        requestId = "req_1",
        message = dirtyMessage,
        retryAfterSeconds = retryAfter,
    )

    @Test
    fun `a dirty server response is reduced to a fixed template`() {
        for (kind in QcaErrorKind.entries) {
            val failure = SourceFailureClassifier.classify(qca(kind))
            val text = failure.message
            assertFalse("$kind leaked a URL", text.contains("http", ignoreCase = true))
            assertFalse("$kind leaked a signature", text.contains("Signature", ignoreCase = true))
            assertFalse("$kind leaked a token", text.contains("pt-", ignoreCase = true))
            assertFalse("$kind leaked a user id", text.contains("01a0efc1"))
            assertFalse("$kind leaked a username", text.contains("张三"))
            assertFalse("$kind leaked the http code", text.contains("403"))
            assertFalse("$kind leaked the raw cause", text.contains("failed:"))
        }
    }

    /**
     * ZLQ-136 C2's 追加硬约束, second half: which `QcaErrorKind` reaches `SourceErrorCode.NOT_FOUND`,
     * and therefore which server failure a student reads as 云端目录不存在，请重试.
     *
     * Asserted as a complete table over `QcaErrorKind.entries` rather than as only the two arms that
     * matter, because the constraint is about the *set*. A future kind that quietly fell into
     * `NOT_FOUND` would put folder copy on a failure the folder has nothing to do with, and the
     * classifier's exhaustive `when` cannot catch that on its own — exhaustiveness only guarantees
     * the new kind is handled *somewhere*. The HTTP statuses behind these kinds are pinned on the
     * other side of the module boundary, by `core:network`'s `NotFoundStatusMappingTest`; neither
     * half is decidable from here alone, because `retrofit` is not visible to this module.
     */
    @Test
    fun `the two 404 kinds and only they become a missing cloud folder`() {
        val expected = mapOf(
            QcaErrorKind.NETWORK to SourceErrorCode.NETWORK_UNAVAILABLE,
            QcaErrorKind.RATE_LIMITED to SourceErrorCode.SERVER_BUSY,
            QcaErrorKind.RETRYABLE to SourceErrorCode.SERVER_BUSY,
            QcaErrorKind.AUTH_EXPIRED to SourceErrorCode.AUTH_EXPIRED,
            QcaErrorKind.AUTH_REQUIRED to SourceErrorCode.AUTH_EXPIRED,
            QcaErrorKind.ACCESS_DENIED to SourceErrorCode.ACCESS_DENIED,
            QcaErrorKind.IDENTITY_INVALID to SourceErrorCode.ACCESS_DENIED,
            QcaErrorKind.IDENTITY_DISABLED to SourceErrorCode.ACCESS_DENIED,
            QcaErrorKind.NOT_FOUND to SourceErrorCode.NOT_FOUND,
            QcaErrorKind.SESSION_NOT_FOUND to SourceErrorCode.NOT_FOUND,
            QcaErrorKind.BAD_REQUEST_PERMANENT to SourceErrorCode.REQUEST_REJECTED,
            QcaErrorKind.CONFLICT to SourceErrorCode.REQUEST_REJECTED,
            QcaErrorKind.TURN_ALREADY_RUNNING to SourceErrorCode.REQUEST_REJECTED,
            QcaErrorKind.UNKNOWN to SourceErrorCode.UNKNOWN,
        )
        assertEquals(
            "QcaErrorKind grew or shrank: extend this table and re-check C2's NOT_FOUND mapping",
            QcaErrorKind.entries.toSet(), expected.keys,
        )
        expected.forEach { (kind, code) ->
            assertEquals(kind.name, code, SourceFailureClassifier.classify(qca(kind)).code)
        }

        // C2 adopts the design's 云端目录不存在，请重试 over R5's 文件已不存在，请重新选择或删除 — the file
        // is fine, the cloud folder is what is missing, so pointing the student at the picker sends
        // them to re-pick a file that was never the problem. And a missing folder is not something an
        // auto-retry can bring back, so it must not burn the student's attempt budget.
        for (kind in listOf(QcaErrorKind.NOT_FOUND, QcaErrorKind.SESSION_NOT_FOUND)) {
            val failure = SourceFailureClassifier.classify(qca(kind))
            assertEquals(kind.name, "云端目录不存在，请重试", failure.message)
            assertFalse("$kind was marked auto-retryable", failure.retryable)
            assertFalse("$kind landed on an auto-retryable code",
                SourceErrorCode.isAutoRetryable(failure.code))
        }

        // The arm that must NOT be here: a 404 naming a missing identity is a login problem, and
        // dressing it as a missing folder is the exact confusion C2's pin exists to prevent.
        assertEquals(SourceErrorCode.ACCESS_DENIED,
            SourceFailureClassifier.classify(qca(QcaErrorKind.IDENTITY_INVALID)).code)
    }

    @Test
    fun `local failures never echo the exception message either`() {
        val cases = listOf(
            SourceTooLargeException(SourceFailureClassifier.MAX_BYTES + 1),
            SourceUnsupportedFormatException(conflicting = true),
            SourceUnsupportedFormatException(conflicting = false),
            SourceHashMismatchException(),
            SourceUnreadableException(IOException(dirtyMessage)),
            SourceCancelledException(),
            SourceNoCredentialException(),
            ManifestPublishException(IOException(dirtyMessage)),
            PresignedUrlExpiredException(403),
            SocketTimeoutException(dirtyMessage),
            IOException(dirtyMessage),
            IllegalStateException(dirtyMessage),
        )
        for (t in cases) {
            val text = SourceFailureClassifier.classify(t).message
            assertFalse("${t::class.simpleName} leaked the cause", text.contains(dirtyMessage))
            assertFalse("${t::class.simpleName} leaked a URL", text.contains("http", ignoreCase = true))
        }
    }

    @Test
    fun `transient failures are the only ones retried without the student`() {
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(IOException("network down")).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(SocketTimeoutException("slow")).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(qca(QcaErrorKind.RATE_LIMITED, 30)).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(qca(QcaErrorKind.RETRYABLE)).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(PresignedUrlExpiredException(403)).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(ManifestPublishException()).code))
        assertTrue(SourceErrorCode.isAutoRetryable(
            SourceFailureClassifier.classify(IllegalStateException("boom")).code))
    }

    @Test
    fun `permanent failures are not auto-retried and say what the student must do`() {
        val permanent = listOf(
            SourceTooLargeException(1) to SourceErrorCode.FILE_TOO_LARGE,
            SourceUnsupportedFormatException(true) to SourceErrorCode.UNSUPPORTED_FORMAT,
            SourceHashMismatchException() to SourceErrorCode.HASH_MISMATCH,
            SourceUnreadableException() to SourceErrorCode.URI_PERMISSION_REQUIRED,
            SourceCancelledException() to SourceErrorCode.USER_CANCELLED,
            SourceNoCredentialException() to SourceErrorCode.AUTH_EXPIRED,
            qca(QcaErrorKind.AUTH_EXPIRED) to SourceErrorCode.AUTH_EXPIRED,
            qca(QcaErrorKind.ACCESS_DENIED) to SourceErrorCode.ACCESS_DENIED,
            qca(QcaErrorKind.IDENTITY_INVALID) to SourceErrorCode.ACCESS_DENIED,
            qca(QcaErrorKind.NOT_FOUND) to SourceErrorCode.NOT_FOUND,
            qca(QcaErrorKind.CONFLICT) to SourceErrorCode.REQUEST_REJECTED,
        )
        for ((throwable, expected) in permanent) {
            val failure = SourceFailureClassifier.classify(throwable)
            assertEquals(expected, failure.code)
            assertFalse("$expected must not auto-retry", failure.retryable)
        }
    }

    @Test
    fun `a revoked grant asks for the file again, not for a retry`() {
        val failure = SourceFailureClassifier.classify(SourceUnreadableException())
        assertEquals(SourceErrorCode.URI_PERMISSION_REQUIRED, failure.code)
        assertTrue(failure.message, failure.message.contains("请重新选择文件"))
        assertNull(failure.retryAfterSeconds)
    }

    /**
     * ZLQ-131: this row's entry set is `{delete}` only, so the copy has to name the exit that exists.
     * The string is the PM's verbatim ruling and is the one truth source for both the persisted
     * `error_message` and what the row renders, so pinning it here pins both.
     */
    @Test
    fun `a rejected re-pick points at delete-and-readd, not at an entry the row does not have`() {
        val failure = SourceFailureClassifier.classify(SourceHashMismatchException())
        assertEquals(SourceErrorCode.HASH_MISMATCH, failure.code)
        assertEquals("所选文件与原资料内容不一致，请删除后重新添加符合要求的资料", failure.message)
        assertFalse(failure.retryable)
        assertFalse(failure.message, failure.message.contains("请重新选择原来的文件"))
        assertFalse(failure.message, failure.message.contains("重新选择文件"))
        // The adjacent branch keeps its 重新选择 entry, so its copy must stay exactly as it was.
        assertEquals(
            "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            SourceFailureClassifier.classify(SourceUnreadableException()).message,
        )
    }

    @Test
    fun `a missing credential is a login problem, not a retryable network problem`() {
        val failure = SourceFailureClassifier.classify(SourceNoCredentialException())
        assertEquals(SourceErrorCode.AUTH_EXPIRED, failure.code)
        assertEquals("登录已过期，请重新登录后再上传", failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun `conflicting signals are told apart from an unrecognised file`() {
        assertTrue(SourceFailureClassifier.classify(SourceUnsupportedFormatException(conflicting = true))
            .message.contains("内容与扩展名不一致"))
        assertEquals(
            "暂不支持此文件格式",
            SourceFailureClassifier.classify(SourceUnsupportedFormatException(conflicting = false)).message,
        )
    }

    @Test
    fun `the size cap is 50 MiB and the message quotes the same number`() {
        assertEquals(50L * 1024 * 1024, SourceFailureClassifier.MAX_BYTES)
        val message = SourceFailureClassifier.classify(
            SourceTooLargeException(SourceFailureClassifier.MAX_BYTES + 1),
        ).message
        assertTrue(message, message.contains("50 MB"))
    }

    @Test
    fun `rate limiting passes the server backoff through`() {
        val failure = SourceFailureClassifier.classify(qca(QcaErrorKind.RATE_LIMITED, retryAfter = 42))
        assertEquals(SourceErrorCode.SERVER_BUSY, failure.code)
        assertEquals(42L, failure.retryAfterSeconds)
        assertTrue(failure.retryable)
    }

    /**
     * ZLQ-105 / AC-E 7: the read-stage boundaries. A file the student deleted or moved used to escape
     * as a bare `FileNotFoundException`, which is an `IOException`, so it was reported as an
     * unavailable network and burned all six automatic attempts on bytes that no longer exist. Only a
     * failure from the cloud stage may enter that budget.
     */
    @Test
    fun `every local read failure is a re-pick, never a network outage`() {
        val boundaries = listOf(
            // The original behind a persisted URI was deleted or moved.
            FileNotFoundException("报告.docx") to SourceErrorCode.URI_PERMISSION_REQUIRED,
            // The grant was revoked, or the provider refuses us outright.
            SecurityException("no permission") to SourceErrorCode.URI_PERMISSION_REQUIRED,
            // openInputStream returned null, which SourceAccess raises as unreadable.
            SourceUnreadableException() to SourceErrorCode.URI_PERMISSION_REQUIRED,
            // Any other local read failure, a cloud provider streaming over a dead radio included.
            IOException("stream broke") to SourceErrorCode.READ_FAILED,
        )
        for ((thrown, expected) in boundaries) {
            val failure = SourceFailureClassifier.classify(normalizeLocalReadFailure(thrown))
            assertEquals("${thrown::class.simpleName} became ${failure.code}", expected, failure.code)
            assertFalse("${thrown::class.simpleName} must not auto-retry", failure.retryable)
            assertFalse(failure.code, SourceErrorCode.isAutoRetryable(failure.code))
            assertTrue(failure.message, failure.message.contains("请重新选择文件"))
            assertFalse(failure.message, failure.message.contains("自动重试"))
            assertFalse(failure.message, failure.message.contains("网络"))
        }
    }

    @Test
    fun `the same timeout is permanent at the read stage and transient at the cloud stage`() {
        val atReadStage = SourceFailureClassifier.classify(
            normalizeLocalReadFailure(SocketTimeoutException("provider stalled"))
        )
        assertEquals(SourceErrorCode.READ_FAILED, atReadStage.code)
        assertFalse(atReadStage.retryable)

        val atCloudStage = SourceFailureClassifier.classify(SocketTimeoutException("PUT stalled"))
        assertEquals(SourceErrorCode.TIMEOUT, atCloudStage.code)
        assertTrue(atCloudStage.retryable)
    }

    /** A read site that forgets to normalize must still not land in the network bucket. */
    @Test
    fun `a bare FileNotFoundException cannot fall through to the IOException fallback`() {
        val failure = SourceFailureClassifier.classify(FileNotFoundException("gone"))
        assertEquals(SourceErrorCode.URI_PERMISSION_REQUIRED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `normalizing only rewrites failures of the read stage`() {
        listOf(
            SourceTooLargeException(1),
            SourceCancelledException(),
            SourceUnsupportedFormatException(conflicting = true),
            IllegalStateException("boom"),
        ).forEach { original ->
            assertSame(original::class.simpleName, original, normalizeLocalReadFailure(original))
        }
    }

    /**
     * ZLQ-138 §5.2 wraps a presigned transport failure so the log can say which host was unreachable.
     * The wrapper must be transparent here: it answers a *logging* question, and the row's code and copy
     * still belong to the throwable underneath. Matched as a plain `IOException` instead, a presigned
     * connect timeout would silently move from TIMEOUT to NETWORK_UNAVAILABLE for no reason but the tag.
     */
    @Test
    fun `a presigned transport wrapper does not change the code its cause would have got`() {
        val cases = listOf(
            SocketTimeoutException("connect timed out") to SourceErrorCode.TIMEOUT,
            UnknownHostException("storage.example.com") to SourceErrorCode.NETWORK_UNAVAILABLE,
            IOException("Connection reset") to SourceErrorCode.NETWORK_UNAVAILABLE,
        )
        cases.forEach { (cause, expected) ->
            val bare = SourceFailureClassifier.classify(cause)
            val wrapped = SourceFailureClassifier.classify(PresignedTransferException("storage.example.com", cause))
            assertEquals(cause.javaClass.simpleName, expected, wrapped.code)
            assertEquals(bare.message, wrapped.message)
            assertEquals(bare.retryable, wrapped.retryable)
        }
    }

    @Test
    fun `the status failures keep their own codes and their original messages`() {
        val expired = SourceFailureClassifier.classify(PresignedUrlExpiredException(403))
        assertEquals(SourceErrorCode.PRESIGNED_URL_EXPIRED, expired.code)
        // An answer from the host is not a transport failure, so it is not wrapped and not re-coded.
        val rejected = SourceFailureClassifier.classify(PresignedStatusException("upload", 500))
        assertEquals(SourceErrorCode.NETWORK_UNAVAILABLE, rejected.code)
        assertEquals("upload failed: HTTP 500", PresignedStatusException("upload", 500).message)
    }
}
