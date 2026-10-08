package com.superstudent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameNormalizerTest {

    @Test
    fun `normalizes with NFKC trim and root lowercase`() {
        assertEquals("alice", UsernameNormalizer.normalize("  Alice  "))
    }

    @Test
    fun `full width username folds to ascii`() {
        assertEquals("bob", UsernameNormalizer.normalize("\uFF22\uFF4F\uFF42"))
    }

    @Test
    fun `same username always converges to the same external id`() {
        val a = UsernameNormalizer.externalId(UsernameNormalizer.normalize("Alice"))
        val b = UsernameNormalizer.externalId(UsernameNormalizer.normalize("  alice "))
        assertEquals(a, b)
        assertTrue(a.startsWith("superstudent:v1:"))
        assertEquals("superstudent:v1:".length + 64, a.length)
    }

    @Test
    fun `different usernames never collide`() {
        val a = UsernameNormalizer.externalId(UsernameNormalizer.normalize("alice"))
        val b = UsernameNormalizer.externalId(UsernameNormalizer.normalize("bob"))
        assertNotEquals(a, b)
    }

    @Test(expected = UsernameNormalizer.InvalidUsernameException::class)
    fun `rejects too short username`() {
        UsernameNormalizer.normalize("ab")
    }

    @Test(expected = UsernameNormalizer.InvalidUsernameException::class)
    fun `rejects too long username`() {
        UsernameNormalizer.normalize("a".repeat(41))
    }

    @Test(expected = UsernameNormalizer.InvalidUsernameException::class)
    fun `rejects control characters`() {
        UsernameNormalizer.normalize("ali\u0000ce")
    }
}
