package com.superstudent.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The logging half of ZLQ-138 §5.2, pinned off the source because it cannot run on the JVM: every line
 * goes through `android.util.Log`, which does not exist outside a device, and this repo has no
 * Robolectric or instrumented harness.
 *
 * What is being pinned is a property the classifier's own tests cannot see — that the *presigned* client
 * logs at all. It shipped without a logger, which is why a student whose route to the storage region was
 * broken produced a report containing nothing but 「网络不可用」: the one hop nobody could see was the one
 * that was failing. The second property is that the failure line is written in a release build too,
 * because the field failure is the only one anybody ever gets to read.
 */
class NetworkFactoryLoggingTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val source = File(
        repoRoot,
        "core/network/src/main/java/com/superstudent/core/network/NetworkFactory.kt",
    ).readText()

    /** Whitespace-stripped, so a reformat does not read as a semantic change. */
    private val flat = source.replace("\\s".toRegex(), "")

    /** The logger's own half of the file: `intercept` is a name both interceptors use. */
    private val logger = source.substringAfter("private class RedactedLogger")

    private fun block(marker: String, haystack: String = source): String {
        val start = haystack.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in haystack.indexOf('{', start) until haystack.length) {
            when (haystack[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return haystack.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    /**
     * An expression-bodied function's text: from [marker] to the next member declaration. `block` cannot
     * serve here, because these bodies have no `{` of their own and it would run on into the next
     * function — straight through the `AuthInterceptor` the transfer client must not carry.
     */
    private fun declaration(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        val member = Regex("""\n\s*(?:private\s+)?(?:fun|val|class)\s""")
        val next = member.find(source, start + marker.length)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    @Test
    fun `both hops of the login chain carry the same redacted logger, each labelled with its hop`() {
        assertTrue(flat.contains("RedactedLogger(debug,NetworkHop.API)"))
        // The presigned client had no interceptor at all before ZLQ-138.
        assertTrue(flat.contains("RedactedLogger(debug,NetworkHop.PRESIGNED)"))
        assertEquals(
            "a client without the logger is a hop nobody can diagnose",
            2,
            Regex("""addInterceptor\(RedactedLogger\(""").findAll(source).count(),
        )
    }

    @Test
    fun `a failed call is logged in every build, not only in a debug one`() {
        val intercept = block("override fun intercept(chain: Interceptor.Chain): Response", logger)
        val failure = intercept.substring(intercept.indexOf("catch (e: java.io.IOException)"))
        assertTrue(failure.contains("android.util.Log.w(TAG, failureLine(req, e))"))
        // The request/response lines stay behind the debug flag; the failure line must not be, or the
        // only report that ever reaches a developer is the one nobody can reproduce.
        assertFalse(failure.contains("verboseBodies"))
        assertTrue(failure.contains("throw e"))
    }

    @Test
    fun `the failure line names the hop, the cause, the host and the exception class`() {
        val line = block("private fun failureLine(req: Request, e: java.io.IOException)", logger)
        assertTrue(line.contains("hop=\${hop.wire}"))
        assertTrue(line.contains("cause=\$cause"))
        assertTrue(line.contains("host=\${req.url.host}"))
        assertTrue(line.contains("exception=\${e.javaClass.name}"))
        // A missing credential is an IOException by construction (ZLQ-119 §4.3); reporting it as a
        // connection failure would send the diagnosis at the network.
        assertTrue(line.contains("NetworkCauses.of(e)"))
        assertTrue(line.contains("\"none\""))
        assertFalse("no message text: it can carry the URL, and the URL carries the signature", line.contains("e.message"))
    }

    @Test
    fun `the presigned query never reaches the log, because the query is the signature`() {
        val target = block("private fun target(req: Request)", logger)
        assertTrue(target.contains("hop == NetworkHop.API"))
        assertTrue(target.contains("encodedQuery"))
        // Only the API hop's branch may name it: one `encodedQuery` in the whole function is the guard.
        assertEquals(1, Regex("""encodedQuery""").findAll(target).count())
        assertTrue(
            "the storage host is the useful half of the line and is not a secret",
            target.contains("url.host"),
        )
    }

    @Test
    fun `the transfer client still carries no auth interceptor and keeps its long IO timeouts`() {
        val transfer = declaration("fun transferClient(debug: Boolean")
        assertTrue(transfer.contains("RedactedLogger(debug, NetworkHop.PRESIGNED)"))
        assertFalse("the signature is in the URL; an Authorization header would only confuse the host",
            transfer.contains("AuthInterceptor"))
        assertTrue(transfer.contains("connectTimeout(15, TimeUnit.SECONDS)"))
        assertTrue(transfer.contains("readTimeout(120, TimeUnit.SECONDS)"))
        assertTrue(transfer.contains("writeTimeout(120, TimeUnit.SECONDS)"))
    }

    @Test
    fun `the api client keeps the timeouts the login retry budget is sized against`() {
        val api = declaration("fun apiClient(patProvider: PatProvider, debug: Boolean)")
        assertTrue(api.contains("connectTimeout(10, TimeUnit.SECONDS)"))
        assertTrue(api.contains("callTimeout(30, TimeUnit.SECONDS)"))
        assertTrue(api.contains("AuthInterceptor(patProvider)"))
        assertTrue(api.contains("RedactedLogger(debug, NetworkHop.API)"))
    }
}
