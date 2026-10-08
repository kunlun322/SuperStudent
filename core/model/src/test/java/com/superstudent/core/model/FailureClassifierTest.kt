package com.superstudent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZLQ-79 / AC-e: the cloud's own failure report must win over the downstream Drive 404, and nothing
 * internal may reach the text a student reads.
 */
class FailureClassifierTest {

    private val now = "2026-09-30T02:11:00Z"

    /** The verbatim envelope QA captured for the D2 defect. */
    private val qmindFailure = """{"status":"FAILED","stage":"QMIND_INDEX","reason":"compile_rejected_COMPILATION_RETIRED"}"""

    private fun agentMessage(text: String, id: String = "evt_1") = SessionEventDto(
        id = id,
        type = "agent.message",
        processedAt = now,
        content = listOf(ContentBlock(type = "text", text = text)),
    )

    @Test
    fun `the QA envelope is classified as a retryable cloud failure carrying its stage`() {
        val report = FailureClassifier.fromEvent(agentMessage(qmindFailure), now)

        assertEquals(FailureCode.CLOUD_GENERATION_FAILED, report?.code)
        assertEquals(TaskStage.QMIND_INDEX, report?.stage)
        assertEquals(TaskState.FAILED_RETRYABLE, report?.state)
        assertEquals("云端生成失败（阶段：知识索引），可重试", report?.message)
        // AC-a: the cloud's reason survives verbatim, for troubleshooting only.
        assertEquals("compile_rejected_COMPILATION_RETIRED", report?.detail)
        assertTrue(report!!.authoritative)
    }

    /**
     * The event QA actually captured for ZLQ-79 (`sess_00qybmh43ha0ww5mwnm4` /
     * `evt_00qybps3gfh1cv0s6tmp`): the envelope is unfenced and trails the Agent's own prose, so a
     * parser that only accepts a bare JSON body would miss it and fall through to the Drive 404.
     */
    @Test
    fun `the real ZLQ-79 event is classified from its prose-plus-envelope body`() {
        val real = "compile 被服务端拒绝（`COMPILATION_RETIRED`：知识卡编译功能已下线）。" +
            "契约规定 compile 失败不得静默跳过，必须按失败收尾。\n\n$qmindFailure"
        val event = SessionEventDto(
            id = "evt_00qybps3gfh1cv0s6tmp",
            type = "agent.message",
            sessionId = "sess_00qybmh43ha0ww5mwnm4",
            processedAt = "2026-09-29T22:27:28.15767Z",
            content = listOf(ContentBlock(type = "text", text = real)),
        )

        val report = FailureClassifier.fromEvent(event, now)

        assertEquals(FailureCode.CLOUD_GENERATION_FAILED, report?.code)
        assertEquals(TaskStage.QMIND_INDEX, report?.stage)
        assertEquals(TaskState.FAILED_RETRYABLE, report?.state)
        assertEquals("云端生成失败（阶段：知识索引），可重试", report?.message)
        assertEquals("compile_rejected_COMPILATION_RETIRED", report?.detail)
        assertEquals("2026-09-29T22:27:28.15767Z", report?.occurredAt)
        assertEquals("evt_00qybps3gfh1cv0s6tmp", report?.lastEventId)
    }

    @Test
    fun `a fenced envelope inside an Agent message is still parsed`() {
        val fenced = FailureClassifier.fromEvent(agentMessage("生成结束。\n```json\n$qmindFailure\n```"), now)
        assertEquals(TaskStage.QMIND_INDEX, fenced?.stage)

        val embedded = FailureClassifier.fromEvent(
            agentMessage("前置说明 $qmindFailure 后置说明"),
            now,
        )
        assertEquals(TaskStage.QMIND_INDEX, embedded?.stage)
    }

    @Test
    fun `an unknown stage still reports a cloud failure without inventing a stage label`() {
        val report = FailureClassifier.fromEvent(
            agentMessage("""{"status":"FAILED","stage":"SOME_NEW_STAGE","reason":"boom"}"""),
            now,
        )
        assertEquals(FailureCode.CLOUD_GENERATION_FAILED, report?.code)
        assertNull(report?.stage)
        assertEquals("云端生成失败，可重试", report?.message)
        assertEquals("boom", report?.detail)
    }

    @Test
    fun `a missing envelope yields null so the caller degrades to artifact validation`() {
        assertNull(FailureClassifier.parseEnvelope("阶段：GENERATE_PLAN"))
        assertNull(FailureClassifier.fromEvent(agentMessage("阶段：GENERATE_PLAN"), now))
        assertNull(FailureClassifier.parseEnvelope(""))
    }

    @Test
    fun `a malformed envelope yields null instead of throwing`() {
        assertNull(FailureClassifier.parseEnvelope("""{"status":"FAILED","stage":"""))
        assertNull(FailureClassifier.parseEnvelope("""{"status":}"""))
        assertNull(FailureClassifier.parseEnvelope("not json at all {"))
        assertNull(FailureClassifier.fromEvent(agentMessage("""{"status":"FAILED","stage":"""), now))
    }

    @Test
    fun `a SUCCEEDED manifest is not a failure`() {
        assertNull(
            FailureClassifier.parseEnvelope(
                """{"status":"SUCCEEDED","stage":"PUBLISH","artifacts":[]}""",
            ),
        )
        assertNull(FailureClassifier.fromEvent(agentMessage("完成"), now))
    }

    @Test
    fun `session error maps to a retryable cloud failure per design 1_5`() {
        val event = SessionEventDto(
            id = "evt_9",
            type = "session.error",
            processedAt = now,
            error = QcaErrorBody(code = "agent_crashed", message = "Drive 对象不存在: superstudent/v1/x"),
        )
        val report = FailureClassifier.fromEvent(event, now)

        assertEquals(FailureCode.CLOUD_GENERATION_FAILED, report?.code)
        assertEquals(TaskState.FAILED_RETRYABLE, report?.state)
        assertEquals("evt_9", report?.lastEventId)
        assertEquals("云端生成失败，可重试", report?.message)
        assertTrue(report!!.authoritative)
        assertTrue(report.detail!!.contains("agent_crashed"))
    }

    @Test
    fun `a failed model request is retryable but never aborts a run on its own`() {
        val failing = SessionEventDto(
            id = "evt_3",
            type = "span.model_request_end",
            processedAt = now,
            isError = true,
        )
        val report = FailureClassifier.fromEvent(failing, now)
        assertEquals(FailureCode.CLOUD_GENERATION_FAILED, report?.code)
        assertEquals(TaskState.FAILED_RETRYABLE, report?.state)
        assertFalse(report!!.authoritative)

        val healthy = failing.copy(id = "evt_4", isError = false)
        assertNull(FailureClassifier.fromEvent(healthy, now))
    }

    @Test
    fun `the event timestamp is preferred and an absent one falls back to the scan time`() {
        val withTime = FailureClassifier.fromEvent(agentMessage(qmindFailure), "ignored")
        assertEquals(now, withTime?.occurredAt)

        val noTime = FailureClassifier.fromEvent(agentMessage(qmindFailure).copy(processedAt = null), now)
        assertEquals(now, noTime?.occurredAt)
    }

    @Test
    fun `scanning a page prefers the staged envelope over generic signals`() {
        val events = listOf(
            SessionEventDto(id = "e1", type = "span.model_request_end", processedAt = now, isError = true),
            SessionEventDto(
                id = "e2",
                type = "session.error",
                processedAt = now,
                error = QcaErrorBody(code = "late", message = "later generic error"),
            ),
            agentMessage(qmindFailure, id = "e3"),
        )
        val report = FailureClassifier.fromEvents(events, now)
        assertEquals("e3", report?.lastEventId)
        assertEquals(TaskStage.QMIND_INDEX, report?.stage)
    }

    @Test
    fun `scanning a page with only generic signals still reports a cloud failure`() {
        val events = listOf(
            SessionEventDto(id = "e1", type = "span.model_request_end", processedAt = now, isError = true),
            SessionEventDto(
                id = "e2",
                type = "session.error",
                processedAt = now,
                error = QcaErrorBody(code = "boom", message = "kaboom"),
            ),
        )
        val report = FailureClassifier.fromEvents(events, now)
        assertEquals("e2", report?.lastEventId)
        assertTrue(report!!.authoritative)
        assertFalse(FailureClassifier.fromEvents(events.take(1), now)!!.authoritative)
        assertNull(FailureClassifier.fromEvents(emptyList(), now))
    }

    @Test
    fun `a Drive 404 becomes artifact validation and its path stays out of the user text`() {
        val raw = "Drive 对象不存在: superstudent/v1/packages/pkg_01/results/citations.json"
        val report = FailureClassifier.artifactValidation(raw, now, "evt_7")

        assertEquals(FailureCode.ARTIFACT_VALIDATION_FAILED, report.code)
        assertEquals(TaskState.FAILED_RETRYABLE, report.state)
        assertEquals(TaskStage.VALIDATE, report.stage)
        assertEquals("云端未产出完整结果，请重试", report.message)
        assertEquals(raw, report.detail)
        assertNoInternalPath(report.message)
    }

    @Test
    fun `no classifier ever puts raw failure text into the user-visible message`() {
        val raw = "HTTP 404 not_found Drive 对象不存在: superstudent/v1/packages/pkg_01/results/plan.json"
        val reports = listOf(
            FailureClassifier.artifactValidation(raw, now),
            FailureClassifier.cloudSignal(raw, now),
            FailureClassifier.fromEnvelope(CloudFailureEnvelope("GENERATE_DECK", raw), now),
            FailureClassifier.requestFailed("not_found", TaskState.FAILED_PERMANENT, raw, now),
            FailureClassifier.requestFailed(null, TaskState.RETRY_WAIT, raw, now),
            FailureClassifier.requestFailed(null, TaskState.AUTH_EXPIRED, raw, now),
            FailureClassifier.requestFailed(null, TaskState.ACCESS_DENIED, raw, now),
            FailureClassifier.requestFailed(null, TaskState.IDENTITY_INVALID, raw, now),
        )
        for (report in reports) {
            assertNoInternalPath(report.message)
            assertTrue(report.message.isNotBlank())
            // A truncated detail is what makes the raw text safe to persist.
            assertTrue(report.detail == null || report.detail!!.length <= 500)
        }
    }

    @Test
    fun `retryable states say retryable and permanent ones do not`() {
        assertTrue(FailureClassifier.requestFailed(null, TaskState.RETRY_WAIT, null, now).message.contains("重试"))
        assertTrue(FailureClassifier.cloudSignal("x", now).message.contains("可重试"))
        assertFalse(FailureClassifier.requestFailed(null, TaskState.FAILED_PERMANENT, null, now).message.contains("可重试"))
        assertTrue(FailureClassifier.requestFailed(null, TaskState.AUTH_EXPIRED, null, now).message.contains("登录"))
    }

    @Test
    fun `a failed request keeps its QCA code instead of joining the generation taxonomy`() {
        val report = FailureClassifier.requestFailed("identity_not_found", TaskState.IDENTITY_INVALID, "x", now)
        assertEquals("identity_not_found", report.code)
        assertEquals(TaskState.IDENTITY_INVALID, report.state)

        assertEquals(
            TaskState.AUTH_EXPIRED,
            FailureClassifier.requestFailed(null, TaskState.AUTH_EXPIRED, null, now).code.let {
                TaskState.valueOf(it)
            },
        )
    }

    @Test
    fun `every stage has user-facing wording`() {
        for (stage in TaskStage.entries) {
            assertTrue(stage.name, FailureClassifier.stageLabel(stage)!!.isNotBlank())
        }
        assertNull(FailureClassifier.stageLabel(null))
    }

    @Test
    fun `task json error carries all six fields the PM requires`() {
        val report = FailureClassifier.fromEvent(agentMessage(qmindFailure, "evt_1"), now)!!
        val error = report.toTaskError(attempt = 2)

        assertEquals("cloud_generation_failed", error.code)
        assertEquals("云端生成失败（阶段：知识索引），可重试", error.message)
        assertEquals(TaskStage.QMIND_INDEX, error.stage)
        assertEquals(2, error.attempt)
        assertEquals("compile_rejected_COMPILATION_RETIRED", error.detail)
        assertEquals(now, error.occurredAt)
        assertEquals("evt_1", error.lastEventId)
    }

    @Test
    fun `task json error round-trips through the serializer used to publish it`() {
        val error = FailureClassifier.artifactValidation(
            "Drive 对象不存在: superstudent/v1/packages/pkg_01/results/deck.pptx",
            now,
        ).toTaskError(attempt = 1)

        val encoded = ssJson.encodeToString(TaskErrorJson.serializer(), error)
        val decoded = ssJson.decodeFromString(TaskErrorJson.serializer(), encoded)

        assertEquals(error, decoded)
        for (key in listOf("code", "message", "stage", "attempt", "detail", "occurredAt")) {
            assertTrue("missing $key in $encoded", encoded.contains("\"$key\""))
        }
        // The published `error` block itself must stay user-safe; `detail` is the internal one.
        assertNoInternalPath(decoded.message!!)
        assertTrue(decoded.detail!!.contains("superstudent/v1/"))
        assertEquals(TaskStage.VALIDATE, decoded.stage)
    }

    @Test
    fun `the QA scenario publishes a complete error block to task json`() {
        val error = FailureClassifier.fromEvent(agentMessage(qmindFailure, "evt_1"), now)!!.toTaskError(attempt = 1)
        val encoded = ssJson.encodeToString(TaskErrorJson.serializer(), error)

        for (key in listOf("code", "message", "stage", "attempt", "detail", "occurredAt", "lastEventId")) {
            assertTrue("missing $key in $encoded", encoded.contains("\"$key\""))
        }
        assertTrue(encoded.contains("\"stage\":\"QMIND_INDEX\""))
        assertTrue(encoded.contains("compile_rejected_COMPILATION_RETIRED"))
    }

    private fun assertNoInternalPath(text: String) {
        assertFalse("internal Drive path leaked: $text", text.contains("superstudent/v1/"))
        assertFalse("internal Drive path leaked: $text", text.contains("Drive 对象不存在"))
        assertFalse("raw exception text leaked: $text", text.contains("HTTP "))
        assertFalse("internal file name leaked: $text", text.contains(".json"))
        assertFalse("internal file name leaked: $text", text.contains(".pptx"))
    }
}
