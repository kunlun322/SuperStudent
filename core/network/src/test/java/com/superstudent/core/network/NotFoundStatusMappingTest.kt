package com.superstudent.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * ZLQ-136 C2's 追加硬约束: 钉定「哪个服务端错误 / HTTP 状态映射到 NOT_FOUND」.
 *
 * The reason this needs a pin at all is downstream. `SourceFailure` turns `QcaErrorKind.NOT_FOUND`
 * into the row copy 云端目录不存在，请重试 with `retryable = false`, so whatever lands on that kind is
 * what a student reads when an upload fails — and a kind that also collects "the credential is
 * unknown to the server" would tell a student their folder is missing when the real fix is 重新登录.
 * Pinning only the positive branch would leave that hole open, so the neighbours are asserted too:
 * the set of statuses that reach `NOT_FOUND` is exactly `{404 with no recognised error code}`.
 *
 * Three 404s are distinguished, and the distinction is the server's `error.code`, not the status:
 * `identity_not_found` is an identity problem, `session_not_found` is a session problem, and only a
 * 404 that names neither is a missing object.
 *
 * Driven through a synthetic `retrofit2.Response` rather than a socket: [ErrorMapper.fromHttp] reads
 * exactly three things — the status, the error body's bytes and the response headers — and all three
 * are supplied here faithfully. What a live server would add is Retrofit's own conversion of a wire
 * response into `Response.error`, which is Retrofit's contract rather than ours.
 */
class NotFoundStatusMappingTest {

    @Test
    fun `NOT_FOUND is reached by a bare 404 and by no other status`() {
        assertEquals(QcaErrorKind.NOT_FOUND, mapped(404, null))

        // Every other status keeps its own kind. Collapsing any of these into NOT_FOUND would make
        // the upload row claim 云端目录不存在 for a failure the folder has nothing to do with.
        val neighbours = listOf(
            400 to QcaErrorKind.BAD_REQUEST_PERMANENT,
            401 to QcaErrorKind.AUTH_EXPIRED,
            403 to QcaErrorKind.ACCESS_DENIED,
            409 to QcaErrorKind.CONFLICT,
            429 to QcaErrorKind.RATE_LIMITED,
            500 to QcaErrorKind.RETRYABLE,
            503 to QcaErrorKind.RETRYABLE,
            418 to QcaErrorKind.UNKNOWN,
        )
        neighbours.forEach { (status, kind) ->
            assertEquals("HTTP $status", kind, mapped(status, null))
        }
    }

    @Test
    fun `a 404 that names a missing identity is not a missing folder`() {
        assertEquals(
            "the student would be told 云端目录不存在 when the real fix is 重新登录",
            QcaErrorKind.IDENTITY_INVALID,
            mapped(404, """{"error":{"code":"identity_not_found"}}"""),
        )
    }

    @Test
    fun `a 404 that names a missing session keeps SESSION_NOT_FOUND`() {
        // `SourceFailure` maps this one onto the same NOT_FOUND row copy, deliberately: from the
        // upload's point of view a session that is gone is a resource that is gone. What must not
        // happen is it being read as an identity problem and routed to re-authentication instead.
        assertEquals(
            QcaErrorKind.SESSION_NOT_FOUND,
            mapped(404, """{"error":{"code":"session_not_found"}}"""),
        )
    }

    @Test
    fun `a 404 whose body is not our error JSON still maps to NOT_FOUND`() {
        // The realistic Drive case: the folder is gone and the gateway answers with an HTML page or
        // nothing at all. Lenient parsing fails, `error.code` stays null, and the bare-404 branch
        // still applies — a failure to parse must not downgrade the status into UNKNOWN, or the row
        // would lose its copy and its 重试 entry.
        assertEquals(QcaErrorKind.NOT_FOUND, mapped(404, "<html>not found</html>"))
        assertEquals(QcaErrorKind.NOT_FOUND, mapped(404, """{"error":{}}"""))
        assertEquals(QcaErrorKind.NOT_FOUND, mapped(404, """{"error":{"code":"folder_gone"}}"""))
    }

    @Test
    fun `the status and the server error code survive onto the exception for the log`() {
        val e = ErrorMapper.map(httpError(404, """{"error":{"code":"x","message":"gone"},"request_id":"req_9"}"""))
        assertEquals(404, e.httpCode)
        assertEquals("x", e.code)
        assertEquals("req_9", e.requestId)
    }

    private fun mapped(status: Int, body: String?): QcaErrorKind =
        ErrorMapper.map(httpError(status, body)).kind

    /**
     * A null [body] is sent as an empty one, because that is what a bodyless status line looks like by
     * the time Retrofit hands it over: `Response.body` is never null, so `errorBody().string()` reads
     * `""` and lenient parsing yields no `error.code` — the same place the mapper ends up either way.
     */
    private fun httpError(status: Int, body: String?): HttpException {
        val payload = (body ?: "").toResponseBody("application/json".toMediaType())
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://127.0.0.1:1/api/v1/drive/files/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("HTTP $status")
            .body(payload)
            .build()
        return HttpException(Response.error<Unit>(payload, raw))
    }
}
