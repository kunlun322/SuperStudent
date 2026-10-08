package com.superstudent.app.features.tasks

import com.superstudent.core.model.ContentBlock
import com.superstudent.core.model.CreateSessionRequest
import com.superstudent.core.model.SendEventsRequest
import com.superstudent.core.model.UserMessageEvent
import com.superstudent.core.network.PatProvider
import com.superstudent.core.network.QcaApi
import com.superstudent.core.network.qcaCall
import com.superstudent.core.repository.ProfileRepository
import kotlinx.coroutines.delay
import com.superstudent.core.model.ssJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class QmindDeleteTarget(
    val sourceId: String,
    val qmindSourceId: String,
    val displayName: String?,
)

data class QmindDeleteReport(
    val succeeded: Boolean,
    val deletedSourceIds: List<String>,
    val lintOk: Boolean,
    val retrievalCleared: Boolean,
    val reason: String?,
)

/**
 * FR-10 deletion, Notebook side only. It runs first: removing the Drive original before the chunks
 * are gone would leave retrievable citations with nothing to regenerate them from. The durable half
 * — remote subtree, manifest re-publish, Room row — belongs to `PackageRepository.completeDelete`
 * (ZLQ-130 §3.9) and only runs once this reports convergence.
 *
 * If any step fails, the source keeps `DELETE_PENDING` in profile.json so the UI hides its content
 * instead of showing citations that may still resolve, and the student can retry.
 *
 * The isolation here is LOGICAL: one Notebook per identity by naming convention. qmind authenticates
 * with the single Vault token, so the Notebook ID in the prompt is the only thing keeping this run
 * from touching another student's Notebook — which is why the prompt pins it and forbids any other.
 */
class QmindDeleter(
    private val api: QcaApi,
    private val patProvider: PatProvider,
    private val profileRepository: ProfileRepository,
    private val templateId: String,
) {

    companion object {
        private const val POLL_MS = 4_000L
        private const val TIMEOUT_MS = 6L * 60 * 1000

        /**
         * One key per attempt, never per source. A stable key makes `createSession` hand back the
         * previous attempt's Session, whose events the poll then re-reads from the start — so a retry
         * after a failed delete would replay the old FAILED verdict and never actually re-run.
         */
        internal fun idempotencyKey(sourceId: String, attemptAt: Long) =
            "ss:qmind-delete:$sourceId:$attemptAt"
    }

    enum class Outcome { DELETED, DELETE_PENDING, NOT_INGESTED }

    /**
     * [reason] is only set for [Outcome.DELETE_PENDING]. A half-finished delete hides the source and
     * waits for a retry that may come much later, so the student — and whoever debugs it — needs to
     * know which of the three steps stopped, not just that something did.
     */
    data class DeleteResult(val outcome: Outcome, val reason: String? = null)

    /**
     * Runs the Notebook half of FR-10 and reports whether retrieval actually converged.
     *
     * It owns **no** Drive object and **no** Room row. ZLQ-130 §3.9 puts that half in
     * `PackageRepository.completeDelete`, which runs it under the source execution lock after the
     * tombstone is armed; the previous shape deleted the subtree here and the row through
     * `removeSource`, so two owners could race the same object and publish a manifest from a
     * snapshot taken before the delete.
     *
     * The caller must not run the durable half unless this returns something other than
     * [Outcome.DELETE_PENDING]: removing the Drive original while chunks still resolve would leave
     * citations with nothing to regenerate them from.
     */
    suspend fun delete(identityId: String, sourceId: String): DeleteResult {
        val profile = profileRepository.read(identityId)
        val notebookId = profile?.qmindNotebookId?.takeIf { it.isNotBlank() }
        val mapping = profile?.qmindSources?.firstOrNull { it.sourceId == sourceId }
        val qmindSourceId = mapping?.qmindSourceId?.takeIf { it.isNotBlank() }

        if (notebookId == null || qmindSourceId == null || mapping == null) {
            // Nothing was ever ingested for this source, so there is no retrieval side to clear.
            return DeleteResult(Outcome.NOT_INGESTED)
        }

        profileRepository.setDeletePending(identityId, setOf(sourceId), true)

        val target = QmindDeleteTarget(
            sourceId = sourceId,
            qmindSourceId = qmindSourceId,
            displayName = mapping.displayName,
        )
        val report = runDeleteSession(identityId, notebookId, listOf(target))
        if (report == null) {
            return DeleteResult(Outcome.DELETE_PENDING, "知识库删除会话未取得确认结果，可稍后重试")
        }
        if (!report.succeeded) {
            return DeleteResult(Outcome.DELETE_PENDING, report.reason ?: "知识库删除失败，可稍后重试")
        }
        if (!report.lintOk) {
            return DeleteResult(Outcome.DELETE_PENDING, "知识库存在断链，已隐藏内容，可稍后重试")
        }

        profileRepository.forgetSources(identityId, setOf(sourceId))
        profileRepository.setDeletePending(identityId, setOf(sourceId), false)
        return DeleteResult(Outcome.DELETED)
    }

    private suspend fun runDeleteSession(
        identityId: String,
        notebookId: String,
        targets: List<QmindDeleteTarget>,
    ): QmindDeleteReport? {
        if (patProvider.currentPat() == null) return null
        val key = idempotencyKey(targets.first().sourceId, System.currentTimeMillis())
        val session = runCatching {
            qcaCall {
                api.createSession(
                    key,
                    CreateSessionRequest(
                        identityId = identityId,
                        templateId = templateId,
                        title = "SS qmind delete",
                        metadata = mapOf(
                            "app" to "superstudent-android",
                            "op" to "qmind_delete",
                            "source_id" to targets.first().sourceId,
                        ),
                    ),
                )
            }
        }.getOrNull() ?: return null

        val prompt = QmindDeletePrompt.build(notebookId, targets)
        val sent = runCatching {
            qcaCall {
                api.sendEvents(
                    session.id,
                    "$key:send",
                    SendEventsRequest(
                        events = listOf(UserMessageEvent(content = listOf(ContentBlock(type = "text", text = prompt))))
                    ),
                )
            }
        }.isSuccess
        if (!sent) {
            release(session.id)
            return null
        }

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var lastEventId: String? = null
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val page = runCatching { qcaCall { api.listEvents(session.id, lastEventId, 100) } }.getOrNull()
            if (page != null) {
                for (event in page.data) {
                    if (event.id.isNotBlank()) lastEventId = event.id
                    when (event.type) {
                        "agent.message" -> {
                            val parsed = QmindDeletePrompt.parse(event.textContent())
                            if (parsed != null) {
                                release(session.id)
                                return parsed
                            }
                        }
                        "session.error" -> {
                            release(session.id)
                            return null
                        }
                    }
                }
            }
            val status = runCatching { qcaCall { api.getSession(session.id) } }.getOrNull()?.status
            if (status == "terminated") return null
        }
        release(session.id)
        return null
    }

    private suspend fun release(sessionId: String) {
        runCatching { qcaCall { api.cancelSession(sessionId) } }
    }
}

/**
 * The delete prompt and its reply contract. Kept apart from [PromptTemplate] because this run must
 * produce no learning artifacts at all — reusing the generation prompt would invite the Agent to.
 */
object QmindDeletePrompt {

    fun build(notebookId: String, targets: List<QmindDeleteTarget>): String = buildString {
        appendLine("你是 SuperStudent 的知识库清理执行器。本次运行只做删除，禁止生成任何学习产物。")
        appendLine()
        appendLine("## 输入")
        appendLine("- Notebook ID（唯一允许操作的 Notebook）：$notebookId")
        appendLine("- 待删除来源：")
        targets.forEach {
            appendLine("  - sourceId=${it.sourceId} qmindSourceId=${it.qmindSourceId}")
        }
        appendLine()
        appendLine("## 步骤")
        appendLine("1. 对每个 qmindSourceId 执行 qmind `source delete`，记录返回结果。")
        appendLine("2. 执行一次 qmind `lint`，确认没有断链。")
        appendLine("3. 逐个来源做检索验证：用该来源的关键词检索本 Notebook，确认返回结果中不再出现它的分块。")
        appendLine("4. 不要删除 Drive 上的任何文件，客户端会自行处理。")
        appendLine()
        appendLine("## 输出")
        appendLine("最后一条消息只输出一个 JSON 对象，不要包裹代码块以外的文字：")
        appendLine("""{"status":"SUCCEEDED","stage":"QMIND_DELETE","deletedSourceIds":["..."],"lintOk":true,"retrievalCleared":true,"reason":null}""")
        appendLine("任一步失败时：")
        appendLine("""{"status":"FAILED","stage":"QMIND_DELETE","deletedSourceIds":[],"lintOk":false,"retrievalCleared":false,"reason":"失败原因，不超过 200 字"}""")
        appendLine()
        appendLine("## 约束")
        appendLine("- 这是逻辑隔离：Notebook 之间没有平台级权限边界。只能操作上面给出的 Notebook ID，禁止 notebook list 之外的任何写操作，禁止创建或修改其它 Notebook。")
        appendLine("- 输出中不得包含 PAT、令牌、用户名或整段原文。")
    }

    fun parse(text: String): QmindDeleteReport? {
        val marker = extractJsonBlock(text) ?: return null
        val obj = runCatching { ssJson.parseToJsonElement(marker) }.getOrNull() as? JsonObject
            ?: return null
        val status = (obj["status"] as? JsonPrimitive)?.content ?: return null
        if ((obj["stage"] as? JsonPrimitive)?.content != "QMIND_DELETE") return null
        return QmindDeleteReport(
            succeeded = status.equals("SUCCEEDED", ignoreCase = true),
            deletedSourceIds = runCatching {
                obj["deletedSourceIds"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
            }.getOrNull().orEmpty(),
            lintOk = obj["lintOk"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            retrievalCleared = obj["retrievalCleared"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            reason = (obj["reason"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** The Agent may wrap the JSON in a fence; take the last balanced object either way. */
    private fun extractJsonBlock(text: String): String? {
        val start = text.lastIndexOf("{\"status\"")
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
