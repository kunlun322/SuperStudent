package com.superstudent.core.drive

import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ZLQ-81 contract: a student file name never reaches a Drive path. Paths are built from
 * `sourceId` + content hash + canonical extension, every segment stays inside `[A-Za-z0-9._-]`, and
 * the whitelist is never widened to Unicode to accommodate a Chinese name — the original name lives
 * in `display_name` instead.
 */
class DrivePathTest {

    private val pkg = "pkg_01"
    private val src = "src_01"
    private val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    /** Bytes carrying no recognizable container, so the name/MIME signals decide the type. */
    private val opaque = "超级学生资料内容".encodeToByteArray() + ByteArray(64)

    private fun pdfBytes() = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n".encodeToByteArray() + ByteArray(64)

    private fun jpegBytes() =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)

    private fun heicBytes() =
        ByteArray(4) + "ftypheic".encodeToByteArray() + ByteArray(64)

    private fun docxBytes() =
        "PK\u0003\u0004".encodeToByteArray() + ByteArray(32) +
            "word/document.xml".encodeToByteArray() + ByteArray(64)

    private fun objectName(displayName: String, mimeType: String?, bytes: ByteArray = opaque): String {
        val type = SourceFormat.resolve(bytes, mimeType, displayName).type
        return DrivePath.sourceObject(pkg, src, sha, type)
    }

    @Test
    fun `root layout has no leading slash and no identity nesting`() {
        assertEquals("superstudent/v1/profile.json", DrivePath.profile())
        assertEquals("superstudent/v1/index.json", DrivePath.index())
        assertEquals("superstudent/v1/progress.json", DrivePath.progress())
    }

    @Test
    fun `package layout stays under the shared root`() {
        assertEquals("superstudent/v1/packages/pkg_01/package.json", DrivePath.packageJson(pkg))
        assertEquals("superstudent/v1/packages/pkg_01/sources/src_01", DrivePath.sourceDir(pkg, src))
        assertEquals(
            "superstudent/v1/packages/pkg_01/runs/run_01/tmp",
            DrivePath.tmpDir(pkg, "run_01"),
        )
        assertEquals(
            "superstudent/v1/packages/pkg_01/results/plan.json",
            DrivePath.result(pkg, "plan.json"),
        )
    }

    @Test
    fun `frozen source layout is sourceId plus hash prefix plus canonical extension`() {
        assertEquals(
            "superstudent/v1/packages/pkg_01/sources/src_01/src_01-a1b2c3d4e5f6.pdf",
            DrivePath.sourceObject(pkg, src, sha, CanonicalType.PDF),
        )
        // An uppercase hash is normalized, so the same content always resolves to the same object.
        assertEquals(
            DrivePath.sourceObject(pkg, src, sha, CanonicalType.PDF),
            DrivePath.sourceObject(pkg, src, sha.uppercase(), CanonicalType.PDF),
        )
    }

    @Test
    fun `a pure chinese file name never reaches the path`() {
        val path = objectName("讲义.pdf", "application/pdf", pdfBytes())
        assertEquals(
            "superstudent/v1/packages/pkg_01/sources/src_01/src_01-a1b2c3d4e5f6.pdf",
            path,
        )
        assertAsciiPath(path)
        assertFalse(path.contains("讲义"))
    }

    @Test
    fun `a mixed chinese and english file name keeps only the canonical extension`() {
        val path = objectName("高数Chapter3讲义.pdf", "application/pdf", pdfBytes())
        assertAsciiPath(path)
        assertTrue(path.endsWith("/src_01-a1b2c3d4e5f6.pdf"))
        assertFalse(path.contains("Chapter3"))
    }

    @Test
    fun `a name without an extension still yields a valid path`() {
        val path = objectName("课堂笔记", "text/plain")
        assertAsciiPath(path)
        assertTrue(path.endsWith("/src_01-a1b2c3d4e5f6.txt"))
    }

    @Test
    fun `an over long chinese name is bounded by the segment limit`() {
        val longName = "高".repeat(160) + "第三章讲义.pdf"
        val path = objectName(longName, "application/pdf", pdfBytes())
        assertAsciiPath(path)
        DrivePath.validateRelative(path)
        path.split("/").forEach { assertTrue("片段过长: $it", it.length <= 120) }
    }

    @Test
    fun `spaces parentheses and hyphens in the display name are irrelevant to the path`() {
        val path = objectName("高等数学第三章 扫描版(1).pdf", "application/pdf", pdfBytes())
        assertAsciiPath(path)
        assertTrue(path.endsWith("/src_01-a1b2c3d4e5f6.pdf"))
    }

    @Test
    fun `different sources never collide on one object`() {
        val first = DrivePath.sourceObject(pkg, "src_01", sha, CanonicalType.PDF)
        val second = DrivePath.sourceObject(pkg, "src_02", sha, CanonicalType.PDF)
        val other = DrivePath.sourceObject(pkg, "src_01", "f".repeat(64), CanonicalType.PDF)
        assertEquals(3, setOf(first, second, other).size)
    }

    @Test
    fun `extension case and aliases normalize to the canonical form`() {
        assertTrue(objectName("2026秋-高数-第3讲(1).PDF", "application/pdf", pdfBytes()).endsWith(".pdf"))
        assertTrue(objectName("照片.jpeg", "image/jpeg", jpegBytes()).endsWith(".jpg"))
        assertTrue(objectName("照片.JPG", "image/jpeg", jpegBytes()).endsWith(".jpg"))
        assertTrue(objectName("照片.heic", "image/heic", heicBytes()).endsWith(".heic"))
        assertTrue(objectName("报告.docx", null, docxBytes()).endsWith(".docx"))
    }

    @Test
    fun `unrecognized content falls back to bin and stays non generatable`() {
        val result = SourceFormat.resolve(opaque, null, "未知资料")
        assertEquals(CanonicalType.UNKNOWN, result.type)
        assertFalse(result.generatable)
        assertTrue(objectName("未知资料", null, opaque).endsWith("/src_01-a1b2c3d4e5f6.bin"))
    }

    @Test
    fun `content wins over a disagreeing extension`() {
        // A PDF renamed to .txt must not be stored as text: the signature is ground truth.
        assertEquals(CanonicalType.PDF, SourceFormat.resolve(pdfBytes(), "text/plain", "讲义.txt").type)
    }

    @Test
    fun `sanitize replaces spaces and strips every non ascii character`() {
        assertEquals("my_file.pdf", DrivePath.sanitizeSegment("my file.pdf"))
        assertEquals("ab", DrivePath.sanitizeSegment("a/b"))
        assertEquals("_3.1.pdf", DrivePath.sanitizeSegment("讲义 3.1.pdf"))
        assertEquals("Chapter3.pdf", DrivePath.sanitizeSegment("高数Chapter3讲义.pdf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitize rejects a name that is entirely non ascii`() {
        // Nothing usable remains, so it throws instead of silently substituting the raw name.
        DrivePath.sanitizeSegment("讲义")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitize rejects parent directory traversal`() {
        DrivePath.sanitizeSegment("..")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitize rejects an over long ascii segment`() {
        DrivePath.sanitizeSegment("a".repeat(121))
    }

    @Test
    fun `sanitize strips separators so a traversal attempt stays inert`() {
        val cleaned = DrivePath.sanitizeSegment("../etc/passwd")
        assertFalse(cleaned.contains("/"))
        assertFalse(cleaned == "..")
        // Dropping the separator turns the attack into one harmless name that cannot climb out.
        assertTrue(DrivePath.isUnder(DrivePath.under(DrivePath.ROOT, cleaned), DrivePath.ROOT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects leading slash`() {
        DrivePath.validateRelative("/superstudent/v1/index.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects traversal segment`() {
        DrivePath.validateRelative("superstudent/v1/../v2/index.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects percent encoded traversal`() {
        DrivePath.validateRelative("superstudent/v1/%2e%2e%2findex.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects trailing slash`() {
        DrivePath.validateRelative("superstudent/v1/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source object rejects a traversal source id`() {
        DrivePath.sourceObject(pkg, "../../evil", sha, CanonicalType.PDF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source object rejects a short or non hex hash`() {
        DrivePath.sourceObject(pkg, src, "abc", CanonicalType.PDF)
    }

    @Test
    fun `isUnder keeps every generated path inside the root`() {
        assertTrue(DrivePath.isUnder(DrivePath.packageJson(pkg), DrivePath.ROOT))
        assertFalse(DrivePath.isUnder("superstudent/v2/index.json", DrivePath.ROOT))
    }

    @Test
    fun `a path a v1 install stored can still be recognised as stale`() {
        val dir = DrivePath.sourceDir(pkg, src)
        // What a pre-ZLQ-81 install wrote: the display name as the object name. `isUnder` throws on
        // it by design, and the stale-object cleanup runs after a successful upload, so throwing
        // there would record an upload that landed as a failure.
        val legacy = "$dir/高等数学讲义.pdf"
        assertTrue(DrivePath.isUnderAllowingLegacy(legacy, dir))
        assertTrue(DrivePath.isUnderAllowingLegacy(DrivePath.sourceObject(pkg, src, sha, CanonicalType.PDF), dir))
        assertFalse(DrivePath.isUnderAllowingLegacy("$dir/../../profile.json", dir))
        assertFalse(DrivePath.isUnderAllowingLegacy("$dir//secret.pdf", dir))
        assertFalse(DrivePath.isUnderAllowingLegacy("${dir}x/file.pdf", dir))
        assertFalse(DrivePath.isUnderAllowingLegacy(dir, dir))
        assertFalse(DrivePath.isUnderAllowingLegacy("/$legacy", dir))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireSegment rejects separator injection`() {
        DrivePath.under(DrivePath.ROOT, "packages", "a/b")
    }

    private fun assertAsciiPath(path: String) {
        DrivePath.validateRelative(path)
        assertTrue("路径含非 ASCII 字符: $path", path.all { it.code < 128 })
        assertTrue("路径片段越界: $path", path.split("/").all { SEGMENT.matches(it) })
    }

    private companion object {
        /** The same whitelist `DrivePath` enforces, restated so a widening fails this test too. */
        val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    }
}
