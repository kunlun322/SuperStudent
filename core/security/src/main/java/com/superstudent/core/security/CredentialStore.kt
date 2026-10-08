package com.superstudent.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PAT storage per design §8.1: Android Keystore AES-256-GCM, alias
 * `superstudent_pat_v1`, fresh random 12-byte IV per encryption, ciphertext
 * file inside noBackupFilesDir. Logout deletes both the file and the alias.
 *
 * Availability is [inspect], not `file.exists()`: a file that is half-written, that lost its
 * Keystore alias, or whose GCM tag no longer authenticates must read as unusable at the point it is
 * asked about, not fail somewhere downstream inside OkHttp (design §4.1).
 */
class CredentialStore(context: Context) {

    companion object {
        const val ALIAS = "superstudent_pat_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FILE_NAME = "credential.json"
        private const val TEMP_FILE_NAME = "credential.json.tmp"

        /** The only stored layout this build can read. Anything else fails closed (§4.1). */
        private const val SUPPORTED_VERSION = 1
    }

    @Serializable
    private data class StoredCredential(
        val version: Int,
        val iv: String,
        val ciphertext: String,
        val createdAt: String,
    )

    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val tempFile = File(context.noBackupFilesDir, TEMP_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * Writes the credential through a temp file that is fsynced and closed before an atomic rename,
     * so a crash mid-write can never leave a `credential.json` that exists but is half-written.
     *
     * Throws [IOException] when the rename does not land. That is deliberate: the login flow already
     * rolls back on any failure, and reporting success for a credential that is not durably there
     * would strand the student signed-in-but-unable-to-call.
     */
    fun savePat(pat: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        // AndroidKeyStore rejects caller-supplied IVs; it generates the random GCM IV itself.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(pat.toByteArray(Charsets.UTF_8))
        val stored = StoredCredential(
            version = SUPPORTED_VERSION,
            iv = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP),
            ciphertext = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP),
            createdAt = java.time.Instant.now().toString(),
        )
        val bytes = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        FileOutputStream(tempFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tempFile.renameTo(file)) {
            runCatching { tempFile.delete() }
            throw IOException("credential file could not be committed")
        }
    }

    /**
     * Answers whether a stored credential can actually be used, and why not when it cannot. Pure:
     * it discards nothing, because whether an unusable file means "signed out" or "signed in with a
     * lost credential" depends on the identity, which only the caller has.
     */
    fun inspect(): CredentialInspection {
        if (!file.exists()) return CredentialInspection.Absent
        val stored = try {
            json.decodeFromString<StoredCredential>(file.readText())
        } catch (e: Exception) {
            return CredentialInspection.Unusable(CredentialFailureReason.CORRUPT)
        }
        // Unknown layouts fail closed rather than being migrated: there is no plaintext to read them
        // from, and guessing would put an invented PAT into a request header.
        if (stored.version != SUPPORTED_VERSION) {
            return CredentialInspection.Unusable(CredentialFailureReason.CORRUPT)
        }
        val key = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()
            ?: return CredentialInspection.Unusable(CredentialFailureReason.MISSING_KEY)
        val iv = decode(stored.iv)
            ?: return CredentialInspection.Unusable(CredentialFailureReason.CORRUPT)
        val ct = decode(stored.ciphertext)
            ?: return CredentialInspection.Unusable(CredentialFailureReason.CORRUPT)
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            CredentialInspection.Usable(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (e: Exception) {
            CredentialInspection.Unusable(CredentialFailureReason.DECRYPT_FAILED)
        }
    }

    /** Returns null when no credential is stored or decryption fails. */
    fun loadPat(): String? = (inspect() as? CredentialInspection.Usable)?.pat

    /** True only when [inspect] can actually hand back a PAT. */
    fun hasPat(): Boolean = inspect() is CredentialInspection.Usable

    fun clear() {
        runCatching { file.delete() }
        runCatching { tempFile.delete() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun decode(value: String): ByteArray? =
        runCatching { android.util.Base64.decode(value, android.util.Base64.NO_WRAP) }.getOrNull()
}
