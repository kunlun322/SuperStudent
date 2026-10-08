package com.superstudent.core.upload

import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.model.UploadState

/**
 * Auto-retry budget per source. A manual retry starts a new cycle, so this caps automatic attempts
 * only — see `SourceDao.requeueForRetry`.
 */
const val SOURCE_MAX_ATTEMPTS = 6

/**
 * How long an attempt may go without reporting progress before the row presents as interrupted
 * (design §3.2 condition 4). Well above the lease, so a healthy heartbeat never trips it.
 */
const val SOURCE_NO_PROGRESS_MILLIS = 10 * 60_000L

/** The entries a source row owes the student. R6: five values, no more. */
enum class SourceAction {
    RETRY_UPLOAD,
    RESELECT_FILE,
    DELETE,
    REAUTHENTICATE,
    RETRY_DELETE,
}

/** Chip tone. Presentation only — no business rule reads it. */
enum class SourceRowTone { NEUTRAL, ACCENT, SUCCESS, DANGER }

/**
 * What one source row shows and offers (design §5.2).
 *
 * [stateText] is the matrix copy verbatim and is also what gets persisted into `error_message` when
 * a failure is recorded, so the stored text and the rendered text can never drift apart. [errorText]
 * carries a stored message only when it *differs* — a row written by an older build, whose copy this
 * version no longer produces. Dropping it would lose the only record of why that row failed.
 */
data class SourceRowPresentation(
    val stateText: String,
    val errorText: String?,
    val actions: Set<SourceAction>,
    val busy: Boolean,
    val tone: SourceRowTone,
)

/**
 * The state × entry × copy matrix (design §4), and the only place source-row wording lives.
 *
 * Two rules from ZLQ-110 survive intact and are load-bearing here:
 *
 * - `retryable` controls the *scheduler*, never the manual entries. An exhausted row still offers
 *   重试; a row with no way out still offers 删除. Gate: no state may produce an empty action set,
 *   or the student is left with a permanently failed row and no exit (ZLQ-105's shape).
 * - The delete tombstone overrides the whole matrix: a row being deleted owes nothing but 重试删除.
 */
object SourceRowPresenter {

    /** The one row whose entry set is `{DELETE}` by PM ruling (ZLQ-131), so its copy names 删除. */
    const val HASH_MISMATCH = "所选文件与原资料内容不一致，请删除后重新添加符合要求的资料"

    const val INTERRUPTED = "上传中断，请重试"

    private const val UPLOADING = "上传中"
    private const val UPLOADED = "已上传"
    private const val PENDING_DELETE = "待清理"
    private const val DELETING = "删除中…"
    private const val ILLEGAL_STATE = "资料状态异常"

    fun of(
        row: SourceAssetEntity,
        nowMillis: Long,
        deleting: Boolean = false,
        /**
         * profile.json still marks this source's retrieval side pending. Arming the Room tombstone
         * first (§3.9 item 1) makes that redundant for anything this build writes; it is the belt for
         * a row an older build left behind, whose tombstone is gone but whose chunks may still
         * resolve.
         */
        retrievalPending: Boolean = false,
    ): SourceRowPresentation {
        if (row.deletePending || retrievalPending) {
            return SourceRowPresentation(
                stateText = if (deleting) DELETING else PENDING_DELETE,
                errorText = null,
                actions = setOf(SourceAction.RETRY_DELETE),
                busy = deleting,
                tone = SourceRowTone.NEUTRAL,
            )
        }
        val state = runCatching { UploadState.valueOf(row.uploadState) }.getOrNull()
            ?: return SourceRowPresentation(
                stateText = ILLEGAL_STATE,
                errorText = row.errorMessage,
                // Defensive branch: an unparseable state must still be deletable, never a dead end.
                actions = setOf(SourceAction.DELETE),
                busy = false,
                tone = SourceRowTone.DANGER,
            )
        return when (state) {
            UploadState.PENDING -> row.presentation(UPLOADING, setOf(SourceAction.DELETE), SourceRowTone.ACCENT)
            UploadState.UPLOADING ->
                if (isInterrupted(row, nowMillis)) {
                    row.presentation(INTERRUPTED, setOf(SourceAction.RETRY_UPLOAD, SourceAction.DELETE), SourceRowTone.DANGER)
                } else {
                    row.presentation(UPLOADING, setOf(SourceAction.DELETE), SourceRowTone.ACCENT)
                }
            UploadState.UPLOADED -> row.presentation(UPLOADED, setOf(SourceAction.DELETE), SourceRowTone.SUCCESS)
            UploadState.LOCAL_ONLY -> localOnly(row)
            UploadState.FAILED -> failed(row)
        }
    }

    /**
     * Whether an `UPLOADING` row has stopped being an upload the student should wait on.
     *
     * A 120 s lease renewed every 30 s means an expired lease is already ~4 missed heartbeats, so
     * `lease_until < now` is treated as interrupted whether or not the owner process is provably
     * dead: the copy is the same either way, and only the recovery coordinator — never the UI —
     * decides whether the row may actually be reclaimed (design §3.2 「不得回收」).
     */
    fun isInterrupted(row: SourceAssetEntity, nowMillis: Long): Boolean {
        if (row.uploadState != UploadState.UPLOADING.name) return false
        if (row.interruptRequestedAt != null) return true
        if (row.leaseUntil == null || row.leaseUntil < nowMillis) return true
        val lastProgress = row.lastProgressAt ?: row.uploadStartedAt ?: return false
        return nowMillis - lastProgress > SOURCE_NO_PROGRESS_MILLIS
    }

    private fun localOnly(row: SourceAssetEntity): SourceRowPresentation = when (row.errorCode) {
        SourceErrorCode.HASH_MISMATCH ->
            row.presentation(HASH_MISMATCH, setOf(SourceAction.DELETE), SourceRowTone.DANGER)
        SourceErrorCode.URI_PERMISSION_REQUIRED -> row.presentation(
            "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件",
            setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceRowTone.DANGER,
        )
        SourceErrorCode.READ_FAILED -> row.presentation(
            "无法读取该文件内容，可能文件已被移动或损坏，请重新选择文件",
            setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceRowTone.DANGER,
        )
        else -> row.presentation(
            "需重新选择文件",
            setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE),
            SourceRowTone.DANGER,
        )
    }

    private fun failed(row: SourceAssetEntity): SourceRowPresentation {
        val code = row.errorCode
        val (text, actions) = when (code) {
            SourceErrorCode.NETWORK_UNAVAILABLE ->
                copy(exhausted(row), "网络不可用，请检查网络后重试", "网络仍不可用，请检查网络后重试") to RETRY
            SourceErrorCode.TIMEOUT ->
                copy(exhausted(row), "上传超时，请检查网络后重试", "上传多次超时，请检查网络后重试") to RETRY
            SourceErrorCode.SERVER_BUSY -> {
                // RATE_LIMITED and RETRYABLE both land on this code, and the matrix gives them
                // different copy, so the stored message is the only surviving discriminator. It is
                // written by the classifier from a fixed template, never from an HTTP body.
                val within = row.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "服务暂时不可用，稍后自动重试"
                (if (exhausted(row)) "服务暂时不可用，请稍后重试" else within) to RETRY
            }
            SourceErrorCode.PRESIGNED_URL_EXPIRED ->
                copy(exhausted(row), "上传链接已过期，正在重新获取", "上传链接多次失效，请重试") to RETRY
            SourceErrorCode.MANIFEST_PUBLISH_FAILED ->
                "文件已上传，但资料清单更新失败，请重试" to RETRY
            SourceErrorCode.PROCESS_INTERRUPTED -> INTERRUPTED to RETRY
            SourceErrorCode.NOT_FOUND -> "云端目录不存在，请重试" to RETRY
            SourceErrorCode.USER_CANCELLED -> "已取消上传" to RETRY
            SourceErrorCode.UNKNOWN, null -> "上传失败，请重试" to RETRY
            SourceErrorCode.FILE_TOO_LARGE ->
                "文件超过 50 MB 上限，请压缩或拆分后重新选择" to RESELECT
            SourceErrorCode.UNSUPPORTED_FORMAT ->
                (row.errorMessage?.takeIf { it.isNotBlank() } ?: "暂不支持此文件格式") to RESELECT
            SourceErrorCode.URI_PERMISSION_REQUIRED ->
                "无法读取该文件，可能权限已失效或文件已被移动，请重新选择文件" to RESELECT
            SourceErrorCode.READ_FAILED ->
                "无法读取该文件内容，可能文件已被移动或损坏，请重新选择文件" to RESELECT
            SourceErrorCode.REQUEST_REJECTED -> "云端拒绝了该文件，请更换文件后重试" to RESELECT
            SourceErrorCode.HASH_MISMATCH -> HASH_MISMATCH to setOf(SourceAction.DELETE)
            SourceErrorCode.AUTH_EXPIRED ->
                "登录已过期，请重新登录后再上传" to setOf(SourceAction.REAUTHENTICATE, SourceAction.DELETE)
            // No upload permission is not something the student can fix by retrying or re-picking, so
            // the only honest entries are 删除 and an explanation naming who can help.
            SourceErrorCode.ACCESS_DENIED ->
                "当前账号没有上传权限，请联系管理员" to setOf(SourceAction.DELETE)
            // A code this build does not know: fall back to the generic retry row rather than
            // dropping the entries, which is what would strand the student.
            else -> "上传失败，请重试" to RETRY
        }
        return row.presentation(text, actions, SourceRowTone.DANGER)
    }

    private val RETRY = setOf(SourceAction.RETRY_UPLOAD, SourceAction.DELETE)
    private val RESELECT = setOf(SourceAction.RESELECT_FILE, SourceAction.DELETE)

    private fun copy(exhausted: Boolean, within: String, spent: String) = if (exhausted) spent else within

    /**
     * Whether the automatic budget is spent.
     *
     * Derived from `retryable` rather than `attempt_count`: `fail()` writes
     * `retryable = failure.retryable && !exhausted`, so a `retryable = 0` row carrying an
     * auto-retryable code is by construction an exhausted one. Permanent codes are also
     * `retryable = 0` but have no exhausted variant, so they fall through to their single copy.
     * Gate R5②: no exhausted copy here may say 自动 or 正在 — the student is being told the automatic
     * path has stopped, and wording that promises one is the defect.
     */
    private fun exhausted(row: SourceAssetEntity): Boolean =
        !row.retryable && SourceErrorCode.isAutoRetryable(row.errorCode)

    private fun SourceAssetEntity.presentation(
        stateText: String,
        actions: Set<SourceAction>,
        tone: SourceRowTone,
    ) = SourceRowPresentation(
        stateText = stateText,
        errorText = errorMessage?.takeIf { it.isNotBlank() && it != stateText },
        actions = actions,
        busy = false,
        tone = tone,
    )
}
