package com.superstudent.core.model

/**
 * qmind Notebook naming convention (design §7.2): `ss-{identity_id 末 12 位}-{usernameSlug}`.
 *
 * The name exists only so a first ingest can de-duplicate against `notebook list`; the real binding
 * is the Notebook ID stored in profile.json. This is LOGICAL isolation — a naming and mapping
 * convention, not a platform-enforced permission boundary.
 */
object QmindNaming {

    private const val IDENTITY_TAIL = 12
    private const val SLUG_MAX = 24

    fun identityTail(identityId: String): String =
        identityId.takeLast(IDENTITY_TAIL).ifBlank { identityId }

    fun usernameSlug(displayName: String): String {
        val slug = displayName.lowercase()
            .map { c -> if (c.isAsciiLetterOrDigit()) c else '-' }
            .joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        val clipped = slug.take(SLUG_MAX).trim('-')
        return clipped.ifBlank { "student" }
    }

    fun notebookName(identityId: String, displayName: String): String =
        "ss-${identityTail(identityId)}-${usernameSlug(displayName)}"

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
