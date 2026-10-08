package com.superstudent.core.model

/**
 * Resolves a picked file to a [CanonicalType] (design increment §4).
 *
 * Priority is content signature, then provider MIME, then the lowercased extension whitelist.
 * A file whose remaining signals disagree is reported as `conflicting` so callers can fail closed
 * with `UNSUPPORTED_FORMAT` instead of guessing; an unrecognised file becomes `UNKNOWN` (`.bin`),
 * which is a storage fallback only — `generatable` stays false and the UI must say so.
 */
object SourceFormat {

    /** How far into the file the container scan looks for OOXML part names. */
    private const val SIGNATURE_WINDOW = 256 * 1024

    data class Result(val type: CanonicalType, val conflicting: Boolean = false) {
        val generatable: Boolean get() = type.generatable
    }

    fun resolve(bytes: ByteArray, mimeType: String?, displayName: String?): Result {
        fromSignature(bytes)?.let { return Result(it) }
        val byMime = fromMime(mimeType)
        val byExt = fromExtension(displayName)
        return when {
            byMime == null && byExt == null -> Result(CanonicalType.UNKNOWN)
            byMime == null -> Result(byExt!!)
            byExt == null -> Result(byMime)
            compatible(byMime, byExt) -> Result(byExt)
            else -> Result(CanonicalType.UNKNOWN, conflicting = true)
        }
    }

    /** Null means "the bytes carry no recognizable container"; content is ground truth when it does. */
    fun fromSignature(bytes: ByteArray): CanonicalType? {
        if (bytes.size < 12) return null
        if (startsWith(bytes, PDF_MAGIC)) return CanonicalType.PDF
        if (startsWith(bytes, JPEG_MAGIC)) return CanonicalType.JPG
        if (startsWith(bytes, PNG_MAGIC)) return CanonicalType.PNG
        if (startsWith(bytes, ZIP_MAGIC)) return fromZipContainer(bytes)
        if (startsWith(bytes, RIFF_MAGIC) && contains(bytes, WEBP_MAGIC, 0, minOf(bytes.size, 16))) {
            return CanonicalType.WEBP
        }
        return fromIsoContainer(bytes)
    }

    private fun fromZipContainer(bytes: ByteArray): CanonicalType? {
        val window = minOf(bytes.size, SIGNATURE_WINDOW)
        return when {
            contains(bytes, DOCX_PART, 0, window) -> CanonicalType.DOCX
            contains(bytes, PPTX_PART, 0, window) -> CanonicalType.PPTX
            contains(bytes, DOCX_DIR, 0, window) -> CanonicalType.DOCX
            contains(bytes, PPTX_DIR, 0, window) -> CanonicalType.PPTX
            else -> null
        }
    }

    /** ISO-BMFF: `ftyp` at offset 4, brand at offset 8. Only HEIC/HEIF are supported. */
    private fun fromIsoContainer(bytes: ByteArray): CanonicalType? {
        if (bytes.size < 12) return null
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
        ) return null
        val brand = bytes.decodeToString(8, 12)
        return when (brand) {
            "heic", "heix", "hevc", "hevx", "heim", "heis" -> CanonicalType.HEIC
            "heif", "mif1", "msf1" -> CanonicalType.HEIF
            else -> null
        }
    }

    fun fromMime(mimeType: String?): CanonicalType? {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        if (mime.isEmpty() || mime == "*/*" || mime.contains('*')) return null
        return when (mime) {
            UNINFORMATIVE_MIME -> null
            "application/pdf" -> CanonicalType.PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> CanonicalType.DOCX
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> CanonicalType.PPTX
            "text/plain" -> CanonicalType.TXT
            "text/markdown", "text/x-markdown" -> CanonicalType.MD
            "image/jpeg", "image/jpg" -> CanonicalType.JPG
            "image/png" -> CanonicalType.PNG
            "image/webp" -> CanonicalType.WEBP
            "image/heic", "image/heic-sequence" -> CanonicalType.HEIC
            "image/heif", "image/heif-sequence" -> CanonicalType.HEIF
            else -> CanonicalType.UNKNOWN
        }
    }

    /** Null means "no extension to judge by"; an extension outside the whitelist is `UNKNOWN`. */
    fun fromExtension(displayName: String?): CanonicalType? {
        val name = displayName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return when (name.substring(dot + 1).lowercase()) {
            "pdf" -> CanonicalType.PDF
            "docx" -> CanonicalType.DOCX
            "pptx" -> CanonicalType.PPTX
            "txt" -> CanonicalType.TXT
            "md", "markdown" -> CanonicalType.MD
            "jpg", "jpeg" -> CanonicalType.JPG
            "png" -> CanonicalType.PNG
            "webp" -> CanonicalType.WEBP
            "heic" -> CanonicalType.HEIC
            "heif" -> CanonicalType.HEIF
            else -> CanonicalType.UNKNOWN
        }
    }

    /**
     * Signals that describe the same content are not a conflict. Plain text is the transport for
     * Markdown, and HEIC/HEIF share the same container family.
     */
    private fun compatible(a: CanonicalType, b: CanonicalType): Boolean = when {
        a == b -> true
        a == CanonicalType.UNKNOWN || b == CanonicalType.UNKNOWN -> false
        (a == CanonicalType.TXT || a == CanonicalType.MD) &&
            (b == CanonicalType.TXT || b == CanonicalType.MD) -> true
        (a == CanonicalType.HEIC || a == CanonicalType.HEIF) &&
            (b == CanonicalType.HEIC || b == CanonicalType.HEIF) -> true
        else -> false
    }

    private val UNINFORMATIVE_MIME = "application/octet-stream"
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val RIFF_MAGIC = "RIFF".encodeToByteArray()
    private val WEBP_MAGIC = "WEBP".encodeToByteArray()
    private val DOCX_PART = "word/document.xml".encodeToByteArray()
    private val PPTX_PART = "ppt/presentation.xml".encodeToByteArray()
    private val DOCX_DIR = "word/".encodeToByteArray()
    private val PPTX_DIR = "ppt/".encodeToByteArray()

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    private fun contains(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Boolean {
        if (needle.isEmpty() || to - from < needle.size) return false
        val last = minOf(to, bytes.size) - needle.size
        var i = maxOf(from, 0)
        while (i <= last) {
            var matched = true
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) { matched = false; break }
            }
            if (matched) return true
            i++
        }
        return false
    }
}
