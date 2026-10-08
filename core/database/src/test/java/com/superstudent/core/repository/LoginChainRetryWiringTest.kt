package com.superstudent.core.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the login chain's retry sits (ZLQ-138 §6), pinned off the source because it cannot be driven on
 * the JVM: `AccountRepository` needs a Room database and `SharedPreferences` behind it, and this repo has
 * no Robolectric or instrumented harness. The loop's own behaviour is proven by
 * `core/network`'s `LoginRetryTest`; what is pinned here is *which calls it wraps*.
 *
 * Both edges matter. Too narrow and the defect stands: a single-host chain with no retry is what made an
 * overseas cold login fail outright on one dropped packet. Too wide and a retry duplicates a side effect
 * or multiplies with the Drive layer's own re-request loop, so one login fires nine presigned requests.
 */
class LoginChainRetryWiringTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val source = File(
        repoRoot,
        "core/database/src/main/java/com/superstudent/core/repository/AccountRepository.kt",
    ).readText()

    private val flat = source.replace("\\s".toRegex(), "")

    private fun block(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in source.indexOf('{', start) until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    private fun count(needle: String): Int = Regex.fromLiteral(needle).findAll(source).count()

    @Test
    fun `the retry wraps the mapper, not the other way round`() {
        val helper = block("private suspend fun <T> loginCall(")
        assertTrue(
            "retry outside qcaCall: the reverse would re-map the same failure three times and the loop " +
                "would never see a NetworkFailure to decide on",
            helper.contains("retryOnConnectionFailure { qcaCall(block) }"),
        )
    }

    @Test
    fun `every cloud call in the login chain is retried, and no call outside it is`() {
        // getIdentity, listIdentities, createIdentity and the post-conflict requery.
        assertEquals(4, count("loginCall {"))
        // No bare `qcaCall` is left in the file, so nothing in the chain reaches the cloud un-retried —
        // and nothing outside it gained a retry it should not have. `logout` and `currentIdentityId` are
        // local, and stay that way.
        assertEquals(0, count("qcaCall {"))
        assertEquals(1, count("qcaCall(block)"))
    }

    @Test
    fun `the Drive calls are retried whole, so a transport failure re-requests the presigned URL`() {
        val profile = block("private suspend fun ensureProfile(")
        assertTrue(profile.contains("retryOnConnectionFailure { drive.readJson("))
        assertEquals(2, Regex("""retryOnConnectionFailure\{drive\.writeJson\(""").findAll(flat).count())
        // Not retried inside `DriveRepository` as well: that would make the two loops multiply.
        assertFalse(profile.contains("loginCall"))
    }

    @Test
    fun `the local half of a login is not retried`() {
        val login = block("suspend fun login(rawUsername: String)")
        assertFalse(
            "Room and SharedPreferences cannot fail for a connection reason, and retrying them would " +
                "write the account row twice",
            login.contains("retryOnConnectionFailure"),
        )
        assertTrue(login.contains("accountDao.upsert("))
        assertTrue(login.contains("prefs.setCurrentAccount("))
        // Still ordered: the identity is verified before the profile is touched, and the row is written
        // only after both succeeded.
        assertTrue(login.indexOf("loginCall { api.getIdentity(") < login.indexOf("ensureProfile("))
        assertTrue(login.indexOf("ensureProfile(") < login.indexOf("accountDao.upsert("))
    }
}
