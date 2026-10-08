package com.superstudent.core.security

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}

/**
 * Redaction helpers per design §8.1: crash/analytics/log text must never
 * carry PAT-like material or Authorization headers.
 */
object Redaction {
    private val PAT_PATTERN = Regex("pt-[A-Za-z0-9_\\-]{8,}")
    private val BEARER_PATTERN = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s\"',}]+")

    fun redact(text: String?): String {
        if (text == null) return ""
        var out = BEARER_PATTERN.replace(text) { it.groupValues[1] + "[REDACTED]" }
        out = PAT_PATTERN.replace(out) { "pt-[REDACTED]" }
        return out
    }
}
