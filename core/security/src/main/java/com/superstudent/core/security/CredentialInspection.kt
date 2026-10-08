package com.superstudent.core.security

/**
 * Why a stored credential could not be turned back into a usable PAT.
 *
 * Enum only, by design §4.2: this value travels into logs, `StateFlow`s and UI copy, so it must not
 * be able to carry the PAT or the ciphertext with it. A reason names a category, never the material.
 */
enum class CredentialFailureReason {
    /** No credential file at all while an identity is still signed in. */
    MISSING_FILE,

    /** The file is there but the Keystore alias that encrypted it is not. */
    MISSING_KEY,

    /** The file is not parseable, or carries a version this build refuses to read. */
    CORRUPT,

    /** The file parses and the alias exists, but GCM authentication failed. */
    DECRYPT_FAILED,

    /** The credential decrypted fine and the server rejected it anyway. */
    REMOTE_401,
}

/**
 * The three-state answer to "can this installation make an authenticated call right now".
 *
 * Replaces the old `file.exists()` judgement, which called a half-written or undecryptable file
 * "available" and let the failure surface much later as an uncaught exception inside OkHttp.
 */
sealed interface CredentialInspection {

    /** Nothing is stored: a fresh install, or a completed logout. */
    data object Absent : CredentialInspection

    /**
     * Something is stored and cannot be used. The invalid material is still on disk; discarding it
     * is the caller's decision, because the identity has to be read first to know whether this is a
     * signed-out installation or a signed-in one that lost its credential.
     */
    data class Unusable(val reason: CredentialFailureReason) : CredentialInspection

    data class Usable(val pat: String) : CredentialInspection {
        // A data class would otherwise print the plaintext into any log line that interpolates it.
        override fun toString(): String = "Usable(pat=<redacted>)"
    }
}
