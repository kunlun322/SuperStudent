package com.superstudent.core.upload

import com.superstudent.core.network.PresignedTransferException
import com.superstudent.core.network.PresignedUrlExpiredException
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Stable error codes persisted in `source_asset.error_code` (design increment §3).
 *
 * The UI switches on these, never on message text, so wording can change without breaking a
 * client. `retryable` means *auto*-retryable and nothing else: it drives the scheduler's budget, and
 * must never be read as "this row has no way out". Which manual entries a row owes the student is
 * decided by `SourceRowPresenter.of`, so an exhausted `FAILED` row still offers 重试上传 and an
 * unreadable one still offers 重新选择文件.
 */
object SourceErrorCode {
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    const val TIMEOUT = "TIMEOUT"
    const val SERVER_BUSY = "SERVER_BUSY"
    const val PRESIGNED_URL_EXPIRED = "PRESIGNED_URL_EXPIRED"
    const val MANIFEST_PUBLISH_FAILED = "MANIFEST_PUBLISH_FAILED"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val URI_PERMISSION_REQUIRED = "URI_PERMISSION_REQUIRED"
    const val READ_FAILED = "READ_FAILED"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val AUTH_EXPIRED = "AUTH_EXPIRED"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val NOT_FOUND = "NOT_FOUND"
    const val REQUEST_REJECTED = "REQUEST_REJECTED"
    const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val UNKNOWN = "UNKNOWN"

    val AUTO_RETRYABLE = setOf(
        NETWORK_UNAVAILABLE,
        TIMEOUT,
        SERVER_BUSY,
        PRESIGNED_URL_EXPIRED,
        MANIFEST_PUBLISH_FAILED,
        PROCESS_INTERRUPTED,
        UNKNOWN,
    )

    fun isAutoRetryable(code: String?): Boolean = code in AUTO_RETRYABLE
}

/** The picked content is larger than the product's hard cap. */
class SourceTooLargeException(val sizeBytes: Long) : Exception("source exceeds the size cap")

/** The grant was revoked, the original file is gone, or the stream could not be opened. */
class SourceUnreadableException(cause: Throwable? = null) : Exception("source is not readable", cause)

/**
 * The handle opened but its bytes could not be read on this device. Distinct from
 * [SourceUnreadableException] only in wording: both are permanent, both end in `LOCAL_ONLY`, and
 * neither may be retried behind the student's back.
 */
class SourceReadFailedException(cause: Throwable? = null) : Exception("source read failed", cause)

/**
 * Rewrites a failure thrown while reading the picked bytes *on this device* into the typed exception
 * that carries the stage, so [SourceFailureClassifier] can classify by stage instead of by exception
 * inheritance.
 *
 * A Downloads document deleted behind our back makes `openInputStream` throw [FileNotFoundException]
 * rather than return null; it is an [IOException], so left raw it was reported as an unavailable
 * network and burned the whole auto-retry budget on a file that no longer exists (ZLQ-105). A cloud
 * document provider streaming over a dead radio is indistinguishable from that at this stage, and
 * both are deliberately mapped to the permanent local codes: handing the student a re-pick entry on
 * the first attempt beats six misleading "check your network" retries.
 *
 * Applied only at the local read site. A failure from the presign/PUT/manifest stage never passes
 * through here, so it keeps its network classification.
 */
fun normalizeLocalReadFailure(t: Throwable): Throwable = when (t) {
    is SourceTooLargeException, is SourceUnreadableException, is SourceReadFailedException -> t
    is FileNotFoundException, is SecurityException -> SourceUnreadableException(t)
    is IOException -> SourceReadFailedException(t)
    else -> t
}

/** Signature, provider MIME and extension could not be reconciled into one canonical type. */
class SourceUnsupportedFormatException(val conflicting: Boolean) :
    Exception(if (conflicting) "source format signals conflict" else "source format not recognized")

/** After a re-pick the content hash no longer matches the recorded source. */
class SourceHashMismatchException : Exception("re-picked content differs from the recorded source")

/** The user cancelled from the notification or the page. */
class SourceCancelledException : Exception("upload cancelled by user")

/**
 * No usable credential is stored on this device. The attempt must never reach OkHttp: the auth
 * interceptor throws a non-`IOException` when the PAT is missing, and OkHttp rethrows that on its
 * dispatcher thread, which kills the process instead of surfacing a failure the UI can show.
 */
class SourceNoCredentialException : Exception("no local credential")

/** The object reached Drive but `package.json` / `index.json` could not be re-published. */
class ManifestPublishException(cause: Throwable? = null) :
    Exception("manifest publish failed", cause)

/**
 * One classified upload failure. [message] is built from a fixed template per code and is the only
 * text the UI may render: it never carries an exception message, a signed URL, an HTTP body or any
 * credential. The raw cause stays out of Room entirely and is logged redacted by the caller.
 */
data class SourceFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = SourceErrorCode.isAutoRetryable(code),
    val retryAfterSeconds: Long? = null,
)

object SourceFailureClassifier {

    /** Single-file hard cap (PRD FR-01); also the value the add-source page advertises. */
    const val MAX_BYTES = 50L * 1024 * 1024

    fun classify(t: Throwable): SourceFailure = when (t) {
        is SourceCancelledException -> SourceFailure(
            SourceErrorCode.USER_CANCELLED, "已取消上传", retryable = false
        )
        is SourceTooLargeException -> SourceFailure(
            SourceErrorCode.FILE_TOO_LARGE,
            "文件超过 ${MAX_BYTES / 1024 / 1024} MB 上限，请压缩或拆分后重新选择",
            retryable = false,
        )
        is SourceUnsupportedFormatException -> SourceFailure(
            SourceErrorCode.UNSUPPORTED_FORMAT,
            if (t.conflicting) "文件内容与扩展名不一致，暂不支持此文件格式" else "暂不支持此文件格式",
            retryable = false,
        )
        is SourceHashMismatchException -> SourceFailure(
            // ZLQ-131: this row's entry set is {delete} only, so the copy must not point at a
            // 重新选择 action that no longer exists on it.
            SourceErrorCode.HASH_MISMATCH, "所选文件与原资料内容不一致，请删除后重新添加符合要求的资料", retryable = false
        )
        is SourceUnreadableException -> SourceFailure(
            SourceErrorCode.URI_PERMISSION_REQUIRED,
            "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            retryable = false,
        )
        is SourceReadFailedException -> SourceFailure(
            SourceErrorCode.READ_FAILED,
            "无法读取该文件内容，可能文件已被移动或损坏，请重新选择文件",
            retryable = false,
        )
        // Both are unambiguous "the file is not there / not ours to read" signals at any stage, so
        // they are matched before the IOException fallback below: a deleted original must never be
        // reported as an unavailable network (ZLQ-105).
        is FileNotFoundException, is SecurityException -> SourceFailure(
            SourceErrorCode.URI_PERMISSION_REQUIRED,
            "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            retryable = false,
        )
        is ManifestPublishException -> SourceFailure(
            SourceErrorCode.MANIFEST_PUBLISH_FAILED,
            "文件已上传，但资料清单更新失败，请重试",
        )
        is SourceNoCredentialException -> SourceFailure(
            SourceErrorCode.AUTH_EXPIRED, "登录已过期，请重新登录后再上传", retryable = false
        )
        is PresignedUrlExpiredException -> SourceFailure(
            SourceErrorCode.PRESIGNED_URL_EXPIRED, "上传链接已过期，正在重新获取"
        )
        // Transparent on purpose. The wrapper exists to say *which host* was unreachable (ZLQ-138), and
        // that is a logging question, not a classification one: the throwable underneath still decides
        // the code. Matching it as a plain IOException instead would relabel a presigned connect timeout
        // from TIMEOUT to NETWORK_UNAVAILABLE for no reason but the new tag.
        is PresignedTransferException -> t.cause?.let { classify(it) }
            ?: SourceFailure(SourceErrorCode.NETWORK_UNAVAILABLE, "网络不可用，请检查网络后重试")
        is QcaException -> fromQca(t)
        is SocketTimeoutException -> SourceFailure(SourceErrorCode.TIMEOUT, "上传超时，请检查网络后重试")
        is IOException -> SourceFailure(
            SourceErrorCode.NETWORK_UNAVAILABLE, "网络不可用，请检查网络后重试"
        )
        else -> SourceFailure(SourceErrorCode.UNKNOWN, "上传失败，请重试")
    }

    private fun fromQca(t: QcaException): SourceFailure = when (t.kind) {
        QcaErrorKind.NETWORK -> SourceFailure(
            SourceErrorCode.NETWORK_UNAVAILABLE, "网络不可用，请检查网络后重试"
        )
        QcaErrorKind.RATE_LIMITED -> SourceFailure(
            SourceErrorCode.SERVER_BUSY, "上传过于频繁，请稍后自动重试",
            retryAfterSeconds = t.retryAfterSeconds,
        )
        QcaErrorKind.RETRYABLE -> SourceFailure(
            SourceErrorCode.SERVER_BUSY, "服务暂时不可用，稍后自动重试"
        )
        QcaErrorKind.AUTH_EXPIRED -> SourceFailure(
            SourceErrorCode.AUTH_EXPIRED, "登录已过期，请重新登录后再上传", retryable = false
        )
        // Never sent at all, so there is nothing transient about it: same student-facing outcome as
        // an expired login, and no new error code for the upload contract to grow.
        QcaErrorKind.AUTH_REQUIRED -> SourceFailure(
            SourceErrorCode.AUTH_EXPIRED, "登录已过期，请重新登录后再上传", retryable = false
        )
        QcaErrorKind.ACCESS_DENIED, QcaErrorKind.IDENTITY_INVALID, QcaErrorKind.IDENTITY_DISABLED ->
            SourceFailure(
                SourceErrorCode.ACCESS_DENIED, "当前账号没有上传权限，请联系管理员", retryable = false
            )
        QcaErrorKind.NOT_FOUND, QcaErrorKind.SESSION_NOT_FOUND -> SourceFailure(
            // C2 (ZLQ-136): the design's copy wins over R5's. Both arms are HTTP 404 — see
            // QcaException.kt:72-73 — which is the mapping this code is pinned to.
            SourceErrorCode.NOT_FOUND, "云端目录不存在，请重试", retryable = false
        )
        QcaErrorKind.BAD_REQUEST_PERMANENT, QcaErrorKind.CONFLICT, QcaErrorKind.TURN_ALREADY_RUNNING ->
            SourceFailure(
                SourceErrorCode.REQUEST_REJECTED, "云端拒绝了该文件，请更换文件后重试", retryable = false
            )
        QcaErrorKind.UNKNOWN -> SourceFailure(SourceErrorCode.UNKNOWN, "上传失败，请重试")
    }
}
