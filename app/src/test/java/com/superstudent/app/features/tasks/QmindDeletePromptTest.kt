package com.superstudent.app.features.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmindDeletePromptTest {

    private val targets = listOf(
        QmindDeleteTarget(sourceId = "s1", qmindSourceId = "qs1", displayName = "第一章.pdf"),
        QmindDeleteTarget(sourceId = "s2", qmindSourceId = "qs2", displayName = null),
    )

    private val succeeded =
        """{"status":"SUCCEEDED","stage":"QMIND_DELETE","deletedSourceIds":["qs1","qs2"],""" +
            """"lintOk":true,"retrievalCleared":true,"reason":null}"""

    @Test
    fun `parses the success report`() {
        val report = QmindDeletePrompt.parse(succeeded)
        assertTrue(report!!.succeeded)
        assertTrue(report.lintOk)
        assertTrue(report.retrievalCleared)
        assertEquals(listOf("qs1", "qs2"), report.deletedSourceIds)
        assertNull(report.reason)
    }

    @Test
    fun `parses the failure report and keeps the reason`() {
        val report = QmindDeletePrompt.parse(
            """{"status":"FAILED","stage":"QMIND_DELETE","deletedSourceIds":[],""" +
                """"lintOk":false,"retrievalCleared":false,"reason":"source delete 返回 404"}"""
        )
        assertFalse(report!!.succeeded)
        assertFalse(report.lintOk)
        assertEquals("source delete 返回 404", report.reason)
    }

    @Test
    fun `parses a fenced reply and prose around it`() {
        val report = QmindDeletePrompt.parse(
            "已执行删除。\n```json\n$succeeded\n```\n以上。"
        )
        assertTrue(report!!.succeeded)
    }

    /** A generation report must never be mistaken for a delete report — it would clear DELETE_PENDING. */
    @Test
    fun `rejects a report from another stage`() {
        assertNull(
            QmindDeletePrompt.parse(
                """{"status":"SUCCEEDED","stage":"PUBLISH","deletedSourceIds":["qs1"],""" +
                    """"lintOk":true,"retrievalCleared":true,"reason":null}"""
            )
        )
    }

    @Test
    fun `rejects a reply with no report at all`() {
        assertNull(QmindDeletePrompt.parse("删除完成，无需返回 JSON。"))
        assertNull(QmindDeletePrompt.parse("""{"status":"SUCCEEDED","stage":"QMIND_DELETE""""))
    }

    @Test
    fun `prompt pins the notebook and forbids generation`() {
        val prompt = QmindDeletePrompt.build("nb_123", targets)
        assertTrue(prompt.contains("nb_123"))
        assertTrue(prompt.contains("qs1"))
        assertTrue(prompt.contains("qs2"))
        assertTrue(prompt.contains("只做删除"))
        assertTrue(prompt.contains("source delete"))
        assertTrue(prompt.contains("lint"))
        assertTrue(prompt.contains("不要删除 Drive 上的任何文件"))
    }

    /** The wording matters: calling this permission isolation would overstate what the platform enforces. */
    @Test
    fun `prompt states logical isolation explicitly`() {
        val prompt = QmindDeletePrompt.build("nb_123", targets)
        assertTrue(prompt.contains("逻辑隔离"))
        assertTrue(prompt.contains("没有平台级权限边界"))
        assertFalse(prompt.contains("权限隔离"))
    }

    @Test
    fun `prompt carries no display name and no secret`() {
        val prompt = QmindDeletePrompt.build("nb_123", targets)
        assertFalse(prompt.contains("第一章.pdf"))
        assertTrue(prompt.contains("不得包含 PAT"))
        assertFalse(Regex("pt-[A-Za-z0-9_-]{8,}").containsMatchIn(prompt))
    }
}
