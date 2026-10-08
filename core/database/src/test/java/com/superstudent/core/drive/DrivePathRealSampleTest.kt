package com.superstudent.core.drive

import com.superstudent.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Runs the real path generator over real files on disk (issue ZLQ-91 regression item 1/2/5).
 *
 * The directory comes from the `SS_REAL_SAMPLES` environment variable, so the test skips itself in
 * CI and in a normal `./gradlew test` run: the point is a recorded evidence run against actual
 * non-ASCII file names and actual container bytes, not a fixture that restates the unit tests in
 * [DrivePathTest]. Run it with:
 *
 * ```
 * SS_REAL_SAMPLES=/path/to/samples ./gradlew :core:database:testDebugUnitTest \
 *     --tests '*DrivePathRealSampleTest*'
 * ```
 *
 * Every file is reported on stdout, which Gradle keeps in `build/test-results/…/TEST-*.xml`.
 */
class DrivePathRealSampleTest {

    private val packageId = "pkg_realsample"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `real non-ASCII files produce ASCII-only canonical object paths`() {
        val env = System.getenv("SS_REAL_SAMPLES")
        assumeTrue("SS_REAL_SAMPLES not set", env != null)
        val dir = File(env!!)
        assumeTrue("not a directory: $env", dir.isDirectory)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no sample files in $dir", files.isNotEmpty())

        val seen = mutableMapOf<String, String>()
        val bySha = mutableMapOf<String, MutableSet<String>>()

        println("== DrivePathRealSample: ${files.size} real files from ${dir.absolutePath}")
        for ((index, file) in files.withIndex()) {
            val displayName = file.name
            val bytes = file.readBytes()
            val sourceId = "src_%02d".format(index + 1)
            val sha = sha256(file)
            val resolved = SourceFormat.resolve(bytes, null, displayName)
            val path = DrivePath.sourceObject(packageId, sourceId, sha, resolved.type)
            val again = DrivePath.sourceObject(packageId, sourceId, sha256(file), resolved.type)

            // The whitelist survives contact with a real file name.
            assertTrue("$displayName → non-ASCII in path: $path", path.all { it.code < 128 })
            path.split("/").forEach { segment ->
                assertTrue("$displayName → illegal segment '$segment'", SEGMENT.matches(segment))
                assertTrue("$displayName → segment too long (${segment.length})", segment.length <= 120)
            }
            assertTrue("$displayName → leading slash", !path.startsWith("/"))
            assertFalse("$displayName → traversal", path.contains(".."))
            assertEquals("$displayName → not under the frozen root",
                "superstudent/v1/packages/$packageId/sources/$sourceId/$sourceId-${sha.take(12)}.${resolved.type.ext}",
                path)

            // A retry of the same source is the same object, never a second one.
            assertEquals("$displayName → path is not stable across attempts", path, again)

            // The display name is data: it never reaches the locator.
            val stem = displayName.substringBeforeLast('.')
            if (stem.any { it.code >= 128 }) {
                assertFalse("$displayName → stem leaked into $path", path.contains(stem))
            }

            // Same content under a different name is still one object name.
            bySha.getOrPut(sha) { mutableSetOf() } += path.substringAfterLast('/').substringAfter("-")
            seen[path] = displayName

            println(
                "  %-28s | %8d B | sha256=%s | %-6s generatable=%-5s conflicting=%-5s | %s".format(
                    displayName.take(28), bytes.size.toLong(), sha.take(12),
                    resolved.type.name, resolved.type.generatable, resolved.conflicting, path,
                ),
            )
        }

        assertEquals("two different sources resolved to one object path", files.size, seen.size)
        bySha.forEach { (sha, suffixes) ->
            assertEquals("content $sha produced more than one object name", 1, suffixes.size)
        }
        println("== distinct object paths: ${seen.size}; distinct contents: ${bySha.size}; " +
            "contents picked under more than one name: " +
            bySha.keys.count { sha -> files.count { sha256(it) == sha } > 1 })
    }

    private companion object {
        val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    }
}
