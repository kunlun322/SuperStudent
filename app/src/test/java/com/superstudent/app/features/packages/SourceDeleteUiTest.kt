package com.superstudent.app.features.packages

import com.superstudent.app.features.tasks.QmindDeleter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDeleteUiTest {

    @Test
    fun `pending summary carries the step that stopped`() {
        val ui = SourceDeleteUi(
            sourceId = "src_1",
            outcome = QmindDeleter.Outcome.DELETE_PENDING,
            message = "云端原件删除失败：HTTP 409 conflict",
        )
        val summary = ui.summary.orEmpty()
        assertTrue(summary, summary.contains("内容已隐藏"))
        assertTrue(summary, summary.contains("云端原件删除失败"))
    }

    @Test
    fun `pending without a reason keeps the generic wording`() {
        assertEquals(
            "知识库删除未确认，内容已隐藏，可稍后重试",
            SourceDeleteUi("src_1", outcome = QmindDeleter.Outcome.DELETE_PENDING).summary,
        )
    }

    @Test
    fun `deleted outcome never shows a stale reason`() {
        assertEquals(
            "已从知识库与云端删除",
            SourceDeleteUi("src_1", outcome = QmindDeleter.Outcome.DELETED).summary,
        )
    }

    @Test
    fun `busy state has no summary and a thrown error is shown verbatim`() {
        assertNull(SourceDeleteUi("src_1", busy = true).summary)
        assertEquals(
            "删除失败",
            SourceDeleteUi("src_1", message = "删除失败").summary,
        )
    }
}
