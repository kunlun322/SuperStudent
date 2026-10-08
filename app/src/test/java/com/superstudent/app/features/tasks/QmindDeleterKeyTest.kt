package com.superstudent.app.features.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QmindDeleterKeyTest {

    @Test
    fun `each attempt gets its own idempotency key`() {
        val first = QmindDeleter.idempotencyKey("src_1", 1_000L)
        val second = QmindDeleter.idempotencyKey("src_1", 2_000L)
        assertNotEquals(first, second)
    }

    @Test
    fun `key stays within the platform charset and length limit`() {
        val key = QmindDeleter.idempotencyKey(
            "src_01a0ee53-1cdb-7625-9d44-9292b7f21363",
            System.currentTimeMillis(),
        )
        assertTrue(key, key.length <= 128)
        assertTrue(key, key.matches(Regex("^[A-Za-z0-9._:-]+$")))
        // ":send" is appended for the follow-up request and must fit too.
        assertTrue("${key}:send", (key + ":send").length <= 128)
    }

    @Test
    fun `key keeps the source id so a run stays traceable`() {
        assertEquals(
            "ss:qmind-delete:src_9:42",
            QmindDeleter.idempotencyKey("src_9", 42L),
        )
    }
}
