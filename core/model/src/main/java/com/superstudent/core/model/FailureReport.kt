package com.superstudent.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The two errorCode values FR-13 distinguishes, so first-attempt success rate can be computed from
 * the local library and from `task.json` alike.
 */
object FailureCode {
    /** The cloud itself reported the run failed: a FAILED envelope, `session.error`, or a model request error. */
    const val CLOUD_GENERATION_FAILED = "cloud_generation_failed"

    /** The cloud finished normally but a required artifact is missing or fails schema validation. */
    const val ARTIFACT_VALIDATION_FAILED = "artifact_validation_failed"
}

/** The `status` / `stage` / `reason` triple of the Agent's failure envelope. */
data class CloudFailureEnvelope(val stage: String?, val reason: String?)

/**
 * One classified run failure (design §1.5 / FR-13).
 *
 * [message] is the ONLY field allowed to reach the UI. It is always built from a fixed template here,
 * never copied from an exception or an API response, so an internal Drive path or other raw failure
 * text cannot leak into it. [detail] keeps that raw text for logcat and `task.json` only.
 */
data class FailureReport(
    val code: String,
    val message: String,
    val stage: TaskStage?,
    val detail: String?,
    val state: TaskState,
    val occurredAt: String,
    val lastEventId: String? = null,
    /**
     * True when the cloud stated the run failed outright — a FAILED envelope or `session.error`.
     * False for a model-request error, which a run can survive: on its own it must not abort a turn
     * whose artifacts go on to validate.
     */
    val authoritative: Boolean = true,
) {
    fun toTaskError(attempt: Int): TaskErrorJson = TaskErrorJson(
        code = code,
        message = message,
        stage = stage,
        attempt = attempt,
        detail = detail,
        occurredAt = occurredAt,
        lastEventId = lastEventId,
    )
}

object FailureClassifier {

    private const val MAX_DETAIL_CHARS = 500

    /** Stage wording used in user-facing text; mirrors the labels the task notification shows. */
    fun stageLabel(stage: TaskStage?): String? = when (stage) {
        TaskStage.UPLOAD -> "上传资料"
        TaskStage.PARSE -> "解析资料"
        TaskStage.QMIND_INDEX -> "知识索引"
        TaskStage.GENERATE_PLAN -> "生成学习计划"
        TaskStage.GENERATE_CARDS -> "生成记忆卡片"
        TaskStage.GENERATE_MINDMAP -> "生成思维导图"
        TaskStage.GENERATE_DECK -> "生成幻灯片"
        TaskStage.GENERATE_EXERCISES -> "生成习题"
        TaskStage.VALIDATE -> "校验产物"
        TaskStage.PUBLISH -> "发布结果"
        null -> null
    }

    /**
     * Parses the Agent's failure envelope `{"status":"FAILED","stage":"...","reason":"..."}` out of a
     * message. Returns null when the message carries no failure envelope — a missing envelope and a
     * malformed one both yield null, so the caller degrades to artifact validation instead of
     * inventing a reason.
     */
    fun parseEnvelope(text: String): CloudFailureEnvelope? {
        val obj = firstJsonObject(text) ?: return null
        val status = (obj["status"] as? JsonPrimitive)?.content ?: return null
        if (!status.equals("FAILED", ignoreCase = true)) return null
        return CloudFailureEnvelope(
            stage = (obj["stage"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            reason = (obj["reason"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
        )
    }

    /** Reads the failure report one event carries, or null when it carries none. */
    fun fromEvent(event: SessionEventDto, now: String): FailureReport? {
        val at = event.processedAt?.takeIf { it.isNotBlank() } ?: now
        return when (event.type) {
            "session.error" -> cloudSignal(
                detail = "session.error[${event.error?.code ?: "-"}]: ${event.error?.message ?: "-"}",
                occurredAt = at,
                eventId = event.id,
            )

            "span.model_request_end" -> if (event.isError == true) {
                cloudSignal("span.model_request_end is_error=true", at, event.id, authoritative = false)
            } else {
                null
            }

            "agent.message" -> parseEnvelope(event.textContent())?.let { fromEnvelope(it, at, event.id) }

            else -> null
        }
    }

    /**
     * Scans a page of session events for the run's failure report. An envelope wins over a generic QCA
     * failure signal because only the envelope carries the failing stage and the reason verbatim;
     * within each kind the latest event wins.
     */
    fun fromEvents(events: List<SessionEventDto>, now: String): FailureReport? {
        var weak: FailureReport? = null
        var authoritative: FailureReport? = null
        var staged: FailureReport? = null
        for (event in events) {
            val report = fromEvent(event, now) ?: continue
            if (!report.authoritative) {
                weak = report
                continue
            }
            authoritative = report
            if (report.stage != null) staged = report
        }
        return staged ?: authoritative ?: weak
    }

    /** A cloud-reported FAILED envelope: the authoritative reason for this run. */
    fun fromEnvelope(envelope: CloudFailureEnvelope, occurredAt: String, eventId: String? = null): FailureReport {
        val stage = envelope.stage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() }
        return FailureReport(
            code = FailureCode.CLOUD_GENERATION_FAILED,
            message = cloudMessage(stage),
            stage = stage,
            detail = (envelope.reason ?: envelope.stage)?.take(MAX_DETAIL_CHARS),
            state = TaskState.FAILED_RETRYABLE,
            occurredAt = occurredAt,
            lastEventId = eventId,
        )
    }

    /** A QCA-side failure signal (`session.error`, model request `is_error=true`) with no stage attached. */
    fun cloudSignal(
        detail: String,
        occurredAt: String,
        eventId: String? = null,
        authoritative: Boolean = true,
    ): FailureReport = FailureReport(
        code = FailureCode.CLOUD_GENERATION_FAILED,
        message = cloudMessage(null),
        stage = null,
        detail = detail.take(MAX_DETAIL_CHARS),
        state = TaskState.FAILED_RETRYABLE,
        occurredAt = occurredAt,
        lastEventId = eventId,
        authoritative = authoritative,
    )

    /**
     * The cloud ended the turn normally but the published results are missing or fail validation.
     * The cause — typically a Drive path — is internal and stays in [FailureReport.detail].
     */
    fun artifactValidation(causeMessage: String?, occurredAt: String, eventId: String? = null): FailureReport =
        FailureReport(
            code = FailureCode.ARTIFACT_VALIDATION_FAILED,
            message = "云端未产出完整结果，请重试",
            stage = TaskStage.VALIDATE,
            detail = causeMessage?.take(MAX_DETAIL_CHARS),
            state = TaskState.FAILED_RETRYABLE,
            occurredAt = occurredAt,
            lastEventId = eventId,
        )

    /**
     * A QCA API call failed (§1.5). [code] keeps the existing QCA error code — these are not part of
     * the two-class generation taxonomy — while the user text is derived from the mapped state so the
     * raw `HTTP 4xx …` message stays out of the UI.
     */
    fun requestFailed(code: String?, state: TaskState, detail: String?, occurredAt: String): FailureReport =
        FailureReport(
            code = code ?: state.name,
            message = requestMessage(state),
            stage = null,
            detail = detail?.take(MAX_DETAIL_CHARS),
            state = state,
            occurredAt = occurredAt,
        )

    private fun cloudMessage(stage: TaskStage?): String {
        val label = stageLabel(stage)
        return if (label != null) "云端生成失败（阶段：$label），可重试" else "云端生成失败，可重试"
    }

    private fun requestMessage(state: TaskState): String = when (state) {
        TaskState.AUTH_EXPIRED -> "登录已失效，请重新登录后再试"
        TaskState.ACCESS_DENIED -> "没有访问权限，请确认账号后重试"
        TaskState.IDENTITY_INVALID -> "账号异常，请重新登录后再试"
        TaskState.FAILED_PERMANENT -> "云端拒绝了本次请求，请稍后再试"
        else -> "网络或云端暂时不可用，请稍后重试"
    }

    private fun firstJsonObject(text: String): JsonObject? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        asJsonObject(trimmed)?.let { return it }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return asJsonObject(trimmed.substring(start, end + 1))
    }

    private fun asJsonObject(candidate: String): JsonObject? {
        if (!candidate.startsWith("{")) return null
        return runCatching { ssJson.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
    }
}
