package com.superstudent.core.drive

import com.superstudent.core.model.CanonicalType

/**
 * Drive path rules (issue ruling 6 / design §8.2, extended by the ZLQ-81 design increment §4):
 * relative paths without leading slash, root `superstudent/v1/`, segments limited to
 * `[A-Za-z0-9._-]`, never `..`, never empty, never containing path separators.
 *
 * The whitelist is a security contract and is deliberately ASCII-only. The charset is defined once
 * in [isSegmentChar] and [SEGMENT] is the same rule expressed as a regex, so filtering and
 * validation can never drift apart again — every generator ends by calling [requireSegment].
 *
 * A user-chosen file name is data, never a path component: it is stored as `display_name` and
 * rendered from there. Only [sourceObject] builds source paths, and it derives the object name from
 * `sourceId` + content hash + canonical extension.
 */
object DrivePath {

    const val ROOT = "superstudent/v1"

    private const val MAX_SEGMENT_LENGTH = 120
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val HEX = "0123456789abcdefABCDEF"

    private fun isSegmentChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'

    /**
     * ASCII-safe transcription of one path segment. Whitespace collapses to `_`; anything outside
     * the whitelist (including every non-ASCII character) is dropped; leading/trailing dots are
     * trimmed. Throws when nothing usable remains — callers must not substitute the raw name.
     */
    fun sanitizeSegment(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { c ->
                when {
                    isSegmentChar(c) -> append(c)
                    c == ' ' || c == '\u3000' -> append('_')
                }
            }
        }.trim('.')
        return requireSegment(cleaned)
    }

    fun requireSegment(raw: String): String {
        require(raw.isNotEmpty()) { "非法路径片段: 空" }
        require(SEGMENT.matches(raw)) { "非法路径片段: 仅允许 [A-Za-z0-9._-]" }
        require(raw != "." && raw != "..") { "非法路径片段: 不得为 . 或 .." }
        require(raw.length <= MAX_SEGMENT_LENGTH) { "路径片段过长: ${raw.length} > $MAX_SEGMENT_LENGTH" }
        return raw
    }

    /** Joins validated single-name segments into a relative path. */
    fun join(vararg segments: String): String {
        require(segments.isNotEmpty()) { "路径为空" }
        segments.forEach { requireSegment(it) }
        return finalize(segments.joinToString("/"))
    }

    /** Appends validated single-name segments to an existing validated relative prefix. */
    fun under(prefix: String, vararg segments: String): String {
        validateRelative(prefix)
        require(segments.isNotEmpty()) { "路径为空" }
        segments.forEach { requireSegment(it) }
        return finalize(prefix + "/" + segments.joinToString("/"))
    }

    private fun finalize(path: String): String {
        require(!path.startsWith("/")) { "路径不得以 / 开头: $path" }
        require(!path.contains("//")) { "路径不得包含空片段: $path" }
        require(!path.endsWith("/")) { "路径不得以 / 结尾: $path" }
        require(!path.split("/").any { it == ".." || it == "." }) { "路径不得包含 ..: $path" }
        return path
    }

    fun validateRelative(path: String): String {
        require(path.isNotEmpty()) { "路径为空" }
        require(!path.startsWith("/")) { "路径不得以 / 开头" }
        require(!path.endsWith("/")) { "路径不得以 / 结尾" }
        require(!path.contains("//")) { "路径不得包含空片段" }
        path.split("/").forEach { requireSegment(it) }
        return path
    }

    fun parentOf(path: String): String? {
        validateRelative(path)
        val idx = path.lastIndexOf('/')
        return if (idx < 0) null else path.substring(0, idx)
    }

    fun fileName(path: String): String {
        validateRelative(path)
        return path.substringAfterLast('/')
    }

    fun isUnder(path: String, prefix: String): Boolean {
        validateRelative(path)
        requireSegment(prefix.split("/").last())
        return path == prefix || path.startsWith("$prefix/")
    }

    /**
     * Prefix test for a path that may predate the current naming rule: a row migrated from v1 still
     * holds a `drive_path` built from the raw display name, so its segments are deliberately not
     * re-validated here — [isUnder] would throw on exactly the rows that need cleaning up. Empty,
     * `.` and `..` segments are still rejected and the prefix must match in full, which is what
     * confines a delete to that one directory.
     */
    fun isUnderAllowingLegacy(path: String, prefix: String): Boolean {
        validateRelative(prefix)
        if (!path.startsWith("$prefix/")) return false
        return path.split("/").none { it.isEmpty() || it == "." || it == ".." }
    }

    // ---- layout helpers (all relative, no leading slash) ----

    fun profile() = under(ROOT, "profile.json")
    fun index() = under(ROOT, "index.json")
    fun progress() = under(ROOT, "progress.json")

    fun packageDir(packageId: String) = under(ROOT, "packages", requireSegment(packageId))
    fun packageJson(packageId: String) = under(packageDir(packageId), "package.json")
    fun sourceDir(packageId: String, sourceId: String) =
        under(packageDir(packageId), "sources", requireSegment(sourceId))

    /**
     * Frozen source layout (design increment §4):
     * `packages/{packageId}/sources/{sourceId}/{sourceId}-{sha256 前 12 位}.{canonicalExt}`.
     *
     * The display name is not an input, so a Chinese, emoji-bearing or 255-character name cannot
     * reach a path. The same source content always resolves to the same object, which is what makes
     * a retry idempotent: it re-PUTs this one path instead of creating a second object.
     */
    fun sourceObject(packageId: String, sourceId: String, sha256: String, type: CanonicalType): String {
        require(sha256.length >= 12 && sha256.all { it in HEX }) { "sha256 非法: ${sha256.length} 位" }
        val objectName = "$sourceId-${sha256.lowercase().take(12)}.${type.ext}"
        return under(sourceDir(packageId, sourceId), requireSegment(objectName))
    }

    fun taskJson(packageId: String, taskId: String) =
        under(packageDir(packageId), "tasks", requireSegment(taskId), "task.json")

    fun runsDir(packageId: String) = under(packageDir(packageId), "runs")
    fun runDir(packageId: String, runId: String) = under(runsDir(packageId), requireSegment(runId))
    fun tmpDir(packageId: String, runId: String) = under(runDir(packageId, runId), "tmp")

    fun resultsDir(packageId: String) = under(packageDir(packageId), "results")
    fun result(packageId: String, fileName: String) =
        under(resultsDir(packageId), requireSegment(fileName))

    fun planJson(packageId: String) = result(packageId, "plan.json")
    fun cardsJson(packageId: String) = result(packageId, "cards.json")
    fun citationsJson(packageId: String) = result(packageId, "citations.json")
    fun mindmapJson(packageId: String) = result(packageId, "mindmap.json")
    fun mindmapHtml(packageId: String) = result(packageId, "mindmap.html")
    fun mindmapPng(packageId: String) = result(packageId, "mindmap.png")
    fun deckPptx(packageId: String) = result(packageId, "deck.pptx")
    fun deckManifest(packageId: String) = result(packageId, "deck.manifest.json")
    fun exercisesJson(packageId: String) = result(packageId, "exercises.json")

    fun cardImagesDir(packageId: String) = under(resultsDir(packageId), "cards", "img")

    /** ImageGen naming rule (design §6): `cards/img/{cardId}-{sha256(canonicalPrompt)[0..11]}.png`. */
    fun cardImage(packageId: String, cardId: String, promptHash12: String): String {
        requireSegment(cardId)
        require(promptHash12.length == 12 && promptHash12.all { it in "0123456789abcdef" }) {
            "promptHash 必须是 12 位小写十六进制: $promptHash12"
        }
        return under(cardImagesDir(packageId), "$cardId-$promptHash12.png")
    }
}
