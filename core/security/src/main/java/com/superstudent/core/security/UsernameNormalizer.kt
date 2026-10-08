package com.superstudent.core.security

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Username normalization + deterministic external_id per design §2.2.
 * NFKC -> trim -> Locale.ROOT lowercase; length 3..40; control chars rejected.
 */
object UsernameNormalizer {

    class InvalidUsernameException(reason: String) : IllegalArgumentException(reason)

    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        val lower = nfkc.lowercase(Locale.ROOT)
        if (lower.length !in 3..40) {
            throw InvalidUsernameException("用户名长度需为 3~40 个字符")
        }
        if (lower.any { it.isISOControl() }) {
            throw InvalidUsernameException("用户名不能包含控制字符")
        }
        return lower
    }

    fun externalId(normalizedUsername: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedUsername.toByteArray(Charsets.UTF_8))
        return "superstudent:v1:" + digest.joinToString("") { "%02x".format(it) }
    }
}
