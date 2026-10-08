package com.superstudent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatValidatorTest {

    // Synthetic token, not a real credential: `pt-` + 24-char body + `_` + UUID = 64 chars.
    private val wellFormed = "pt-abcdefghijklmnopqrstuvwx_01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun `accepts a well formed token`() {
        assertEquals(64, wellFormed.length)
        assertNull(PatValidator.validate(wellFormed))
    }

    @Test
    fun `trims surrounding whitespace before accepting`() {
        assertNull(PatValidator.validate("  $wellFormed\n"))
    }

    @Test
    fun `rejects the truncated prefix that Markdown escaping produces`() {
        val truncated = wellFormed.substringBefore('_')
        assertEquals(27, truncated.length)
        val reason = PatValidator.validate(truncated)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不完整"))
        assertTrue(reason.contains("64"))
    }

    @Test
    fun `rejects a masked placeholder`() {
        assertNotNull(PatValidator.validate("pt-s0Qn…d365f"))
    }

    @Test
    fun `rejects blank input`() {
        assertEquals("请输入访问令牌", PatValidator.validate("   "))
    }

    @Test
    fun `rejects interior whitespace from a broken paste`() {
        val broken = wellFormed.substring(0, 20) + " " + wellFormed.substring(20)
        assertTrue(PatValidator.validate(broken)!!.contains("空格"))
    }

    @Test
    fun `rejects a token without the pt prefix`() {
        assertTrue(PatValidator.validate(wellFormed.removePrefix("pt-"))!!.contains("pt-"))
    }

    @Test
    fun `rejects a token whose uuid tail is malformed`() {
        assertNotNull(PatValidator.validate("pt-abcdefghijklmnopqrstuvwx_not-a-uuid"))
    }
}
