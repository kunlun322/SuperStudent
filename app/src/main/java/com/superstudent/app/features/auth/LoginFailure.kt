package com.superstudent.app.features.auth

import com.superstudent.core.network.NetworkCause
import com.superstudent.core.network.NetworkCauses
import com.superstudent.core.network.NetworkFailure
import com.superstudent.core.network.NetworkHop
import com.superstudent.core.security.Redaction

/**
 * What a failed login tells the student, and what it does to the credential (ZLQ-138 §6).
 *
 * Both answers used to be one line each inside `LoginViewModel`: every connection failure said
 * 「网络不可用，请检查网络后重试」 and every failure at all deleted the PAT. That pair is what made the
 * overseas report undiagnosable — the student's own words carried no more information than "the network
 * is down", and the retry cost them a fresh paste of a 40-character token.
 *
 * Pure and injected-free so the copy table is drivable on the JVM: this repo has no Robolectric, and
 * the classification behind it is the part worth testing.
 */
object LoginFailurePolicy {

    /**
     * Whether the PAT this attempt saved may stay on the device.
     *
     * A connection failure never reached a server, so nothing has judged the credential and deleting it
     * only costs the student a re-paste. Two conditions, both required:
     *
     * - It really was a connection failure. A 401, a conflict or an ambiguous identity is a decision,
     *   and the credential it rejected is dropped exactly as before.
     * - No identity is bound on this device. [com.superstudent.app.auth.AuthSession.resolve] publishes
     *   `Authenticated(identityId)` for any usable credential it finds next to an identity, and
     *   `AccountRepository.logout` deliberately keeps the Room rows — so after a logout the identity is
     *   still bound. Keeping a *different* student's PAT there would sign the next cold start into the
     *   previous account, which is the leak §4.1's rollback exists to prevent. With no identity bound
     *   there is nothing to be confused with.
     */
    fun keepsCredential(t: Throwable, identityBound: Boolean): Boolean =
        !identityBound && NetworkCauses.of(t) != null

    /**
     * The sentence for a connection failure, or null when [t] is not one and the caller's own mapping
     * applies.
     *
     * Every branch names a cause and an action. The presigned branches also name the host: the storage
     * host is chosen by the cloud at signing time and is invisible in the build, so a student reporting
     * "cannot reach drive.example-region.com" is the only way anyone downstream learns which region the
     * login chain was pointed at. The host is a hostname, not a secret; the URL that carries it is, and
     * never appears here.
     */
    fun describe(t: Throwable): String? {
        val failure = NetworkCauses.of(t) ?: return null
        return if (failure.hop == NetworkHop.PRESIGNED) presigned(failure) else api(failure.cause)
    }

    private fun api(cause: NetworkCause): String = when (cause) {
        NetworkCause.DNS -> "无法解析登录服务器地址（DNS 失败），请检查网络或更换 DNS 后重试"
        NetworkCause.CONNECT_TIMEOUT -> "连接登录服务器超时，请检查网络后重试"
        NetworkCause.READ_TIMEOUT -> "登录服务器响应超时，请检查网络后重试"
        NetworkCause.CALL_TIMEOUT -> "登录请求超时，请检查网络后重试"
        NetworkCause.TLS -> "与登录服务器建立安全连接失败，请检查系统时间是否正确后重试"
        NetworkCause.UNREACHABLE -> "当前网络无法到达登录服务器，请切换网络后重试"
        NetworkCause.CONNECTION_REFUSED -> "登录服务器拒绝连接，请稍后重试或切换网络"
        NetworkCause.RESET -> "与登录服务器的连接被中断，请检查网络后重试"
        NetworkCause.CANCELED -> "登录已取消"
        NetworkCause.PRESIGNED_REJECTED -> "云端拒绝了资料文件的访问链接，请重试登录"
        NetworkCause.OTHER -> "网络不可用，请检查网络后重试"
    }

    private fun presigned(failure: NetworkFailure): String {
        val host = failure.host ?: "云端存储"
        return when (failure.cause) {
            NetworkCause.PRESIGNED_REJECTED -> "云端拒绝了资料文件的访问链接，请重试登录"
            NetworkCause.DNS -> "无法解析云端存储地址 $host（DNS 失败），请检查网络后重试"
            NetworkCause.CONNECT_TIMEOUT,
            NetworkCause.CALL_TIMEOUT -> "连接云端存储 $host 超时，请检查网络后重试"
            NetworkCause.READ_TIMEOUT -> "云端存储 $host 响应超时，请检查网络后重试"
            NetworkCause.TLS -> "与云端存储 $host 建立安全连接失败，请检查系统时间是否正确后重试"
            NetworkCause.CANCELED -> "登录已取消"
            NetworkCause.UNREACHABLE,
            NetworkCause.CONNECTION_REFUSED,
            NetworkCause.RESET,
            NetworkCause.OTHER -> "无法连接云端存储 $host，请检查网络后重试"
        }
    }
}

/**
 * Writes one line to logcat when a login attempt fails, carrying the cause the UI copy summarizes.
 *
 * It exists because `LoginViewModel` may not touch `android.util.Log` at all — a wiring test scans that
 * file for it, since a ViewModel that logs is a ViewModel that can leak the PAT it is holding — and
 * because the interceptor's own `FAILED` line answers a narrower question: it says one call to one host
 * failed, not that the login as a whole did, and it cannot see a failure raised between calls.
 *
 * The message is redacted and then stripped of every query string. Redaction alone is not enough:
 * `Redaction` masks PAT-like text and `Authorization` headers, while an OkHttp `IOException` can carry
 * the whole URL it failed on — and on the presigned hop the query *is* the signature.
 */
class LoginDiagnostics(
    private val log: (String) -> Unit = { android.util.Log.w(TAG, it) },
) {

    fun loginFailed(t: Throwable) {
        val failure = NetworkCauses.of(t)
        // Two class names, two keys: `wire()`'s is the throwable that carried the classification, which
        // is usually a cause, and this one is what the login chain actually threw.
        log(
            "login_failed ${failure?.wire() ?: "hop=- cause=none"} " +
                "thrown=${t.javaClass.name} message=${safeMessage(t)}",
        )
    }

    private fun safeMessage(t: Throwable): String =
        Redaction.redact(t.message).replace(QUERY, "?[redacted]")

    private companion object {
        const val TAG = "SsNet"
        val QUERY = Regex("""\?[^\s"']+""")
    }
}
