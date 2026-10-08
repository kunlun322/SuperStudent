package com.superstudent.core.network

import com.superstudent.core.model.QcaErrorResponse
import com.superstudent.core.model.ssJsonLenient
import retrofit2.HttpException

/** App-facing error kinds per design §1.5 mapping. */
enum class QcaErrorKind {
    BAD_REQUEST_PERMANENT,
    AUTH_EXPIRED,

    /**
     * No usable local credential, so no request was ever made (ZLQ-119 design §7.2). Distinct from
     * [AUTH_EXPIRED], which means the server saw the PAT and rejected it: this one is decided on
     * device, carries no HTTP code, and must never be auto-retried.
     */
    AUTH_REQUIRED,
    ACCESS_DENIED,
    IDENTITY_INVALID,
    SESSION_NOT_FOUND,
    NOT_FOUND,
    CONFLICT,
    TURN_ALREADY_RUNNING,
    IDENTITY_DISABLED,
    RATE_LIMITED,
    RETRYABLE,
    NETWORK,
    UNKNOWN,
}

class QcaException(
    val kind: QcaErrorKind,
    val httpCode: Int?,
    val code: String?,
    val requestId: String?,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
    /**
     * What the socket said, when [kind] is [QcaErrorKind.NETWORK] (ZLQ-138 §5.1).
     *
     * It is computed once here, where the raw throwable is still in hand, because the alternative is
     * every call site re-walking the cause chain — and the two that matter most cannot: `qcaCall`
     * hands its callers a [QcaException] whose cause is an OkHttp wrapper, and by the time the failure
     * reaches the UI the distinction between "the API host did not resolve" and "the storage host did"
     * is the whole diagnosis.
     */
    val network: NetworkFailure? = null,
) : Exception(message, cause)

object ErrorMapper {

    fun map(t: Throwable): QcaException = when (t) {
        is QcaException -> t
        is HttpException -> fromHttp(t)
        // Before the generic IOException branch: a missing credential is an IOException so OkHttp
        // treats it as an ordinary call failure, but it must not be reported as "network down".
        is MissingCredentialException -> QcaException(
            QcaErrorKind.AUTH_REQUIRED, null, MissingCredentialException.CODE, null,
            MissingCredentialException.MESSAGE, t,
        )
        is java.io.IOException -> QcaException(
            QcaErrorKind.NETWORK, null, null, null,
            "网络不可用: ${t.javaClass.simpleName}", t,
            network = NetworkCauses.of(t),
        )
        else -> QcaException(QcaErrorKind.UNKNOWN, null, null, null, t.message ?: "未知错误", t)
    }

    private fun fromHttp(t: HttpException): QcaException {
        val code = t.code()
        val bodyRaw = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
        val parsed = bodyRaw?.let { runCatching { ssJsonLenient.decodeFromString<QcaErrorResponse>(it) }.getOrNull() }
        val errCode = parsed?.error?.code
        val msg = parsed?.error?.message ?: t.message()
        val reqId = parsed?.requestId
        val retryAfter = runCatching { t.response()?.headers()?.get("Retry-After")?.toLongOrNull() }.getOrNull()
        val kind = when {
            code == 400 -> QcaErrorKind.BAD_REQUEST_PERMANENT
            code == 401 -> QcaErrorKind.AUTH_EXPIRED
            code == 403 -> QcaErrorKind.ACCESS_DENIED
            code == 404 && errCode == "identity_not_found" -> QcaErrorKind.IDENTITY_INVALID
            code == 404 && errCode == "session_not_found" -> QcaErrorKind.SESSION_NOT_FOUND
            code == 404 -> QcaErrorKind.NOT_FOUND
            code == 409 && errCode == "turn_already_running" -> QcaErrorKind.TURN_ALREADY_RUNNING
            code == 409 && errCode == "identity_disabled" -> QcaErrorKind.IDENTITY_DISABLED
            code == 409 -> QcaErrorKind.CONFLICT
            code == 429 -> QcaErrorKind.RATE_LIMITED
            code in 500..599 -> QcaErrorKind.RETRYABLE
            else -> QcaErrorKind.UNKNOWN
        }
        return QcaException(
            kind,
            code,
            errCode,
            reqId,
            "HTTP $code ${errCode ?: ""} $msg".trim(),
            retryAfterSeconds = retryAfter,
        )
    }
}

/**
 * Runs a Retrofit call and normalizes every failure into [QcaException],
 * so the data layer never needs retrofit types. Cancellation passes through.
 */
suspend fun <T> qcaCall(block: suspend () -> T): T = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (t: Throwable) {
    throw ErrorMapper.map(t)
}
