package com.superstudent.core.repository

import com.superstudent.core.database.AccountDao
import com.superstudent.core.database.AccountEntity
import com.superstudent.core.database.Prefs
import com.superstudent.core.drive.DriveNotFoundException
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CreateIdentityRequest
import com.superstudent.core.model.IdentityDto
import com.superstudent.core.model.ProfileJson
import com.superstudent.core.model.QmindNaming
import com.superstudent.core.network.QcaApi
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.qcaCall
import com.superstudent.core.network.retryOnConnectionFailure
import com.superstudent.core.security.Hashing
import com.superstudent.core.security.UsernameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class AmbiguousIdentityException : Exception("同一用户名匹配到多个 Identity，已停止登录以避免错误绑定")

class IdentityDisabledException(val identityId: String) : Exception("Identity 已停用: $identityId")

class ProfileConflictException(val driveIdentityId: String, val resolvedIdentityId: String) :
    Exception("Drive profile 与登录 Identity 不一致")

data class AccountSession(
    val identityId: String,
    val externalId: String,
    val username: String,
    val createdNewIdentity: Boolean,
)

/**
 * Login convergence exactly per design §2.2 / issue ruling 2.
 * identity_id is ALWAYS server-issued — never generated or guessed locally.
 */
class AccountRepository(
    private val api: QcaApi,
    private val drive: DriveRepository,
    private val accountDao: AccountDao,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()

    suspend fun login(rawUsername: String): AccountSession = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = UsernameNormalizer.normalize(rawUsername)
            val externalId = UsernameNormalizer.externalId(normalized)

            val (identity, createdNew) = resolveIdentity(externalId, normalized)
            val verified = loginCall { api.getIdentity(identity.id) }
            if (!verified.enabled) throw IdentityDisabledException(verified.id)

            val profile = ensureProfile(verified.id, externalId, normalized)
            if (profile.identityId != verified.id) {
                throw ProfileConflictException(profile.identityId, verified.id)
            }

            accountDao.upsert(
                AccountEntity(
                    identityId = verified.id,
                    externalId = externalId,
                    username = normalized,
                    profileRevision = 1,
                    lastSyncAt = Instant.now().toString(),
                )
            )
            prefs.setCurrentAccount(verified.id, normalized)
            AccountSession(verified.id, externalId, normalized, createdNewIdentity = createdNew)
        }
    }

    private suspend fun resolveIdentity(externalId: String, displayName: String): Pair<IdentityDto, Boolean> {
        val page = loginCall { api.listIdentities(externalId, 2) }
        val exact = page.data.filter { it.externalId == externalId }
        when {
            exact.size == 1 -> return exact.first() to false
            exact.size > 1 -> throw AmbiguousIdentityException()
        }
        // 0 hits → create, idempotency key derived from external_id so concurrent logins converge.
        val key = "ss:create-identity:${Hashing.sha256Hex(externalId)}"
        return try {
            loginCall {
                api.createIdentity(
                    key,
                    CreateIdentityRequest(
                        externalId = externalId,
                        name = displayName,
                        metadata = mapOf("app" to "superstudent-android", "schema_version" to "1"),
                    )
                )
            } to true
        } catch (e: QcaException) {
            if (e.kind != QcaErrorKind.CONFLICT) throw e
            requery(externalId) to false
        }
    }

    private suspend fun requery(externalId: String): IdentityDto {
        val exact = loginCall { api.listIdentities(externalId, 2) }.data.filter { it.externalId == externalId }
        if (exact.size != 1) throw AmbiguousIdentityException()
        return exact.first()
    }

    /**
     * One call in the login chain: normalized, then repeated while it fails for a retryable connection
     * reason (ZLQ-138 §6).
     *
     * Everything this wraps is idempotent, which is what makes an automatic retry safe here and not
     * elsewhere: the reads are GETs, `createIdentity` carries an `Idempotency-Key` the server
     * deduplicates on, and the profile writes are whole-object overwrites of a fixed path. The retry is
     * outside `qcaCall`, never inside it, so the two loops cannot multiply — and the Drive calls are
     * wrapped whole, so a transport failure re-requests the presigned URL instead of replaying a
     * signature that may already have been rejected.
     */
    private suspend fun <T> loginCall(block: suspend () -> T): T = retryOnConnectionFailure { qcaCall(block) }

    private suspend fun ensureProfile(identityId: String, externalId: String, displayName: String): ProfileJson {
        val path = DrivePath.profile()
        val now = Instant.now().toString()
        val existing = try {
            retryOnConnectionFailure { drive.readJson(identityId, path, ProfileJson.serializer()) }
        } catch (_: DriveNotFoundException) {
            null
        }
        if (existing != null) {
            if (existing.identityId != identityId) throw ProfileConflictException(existing.identityId, identityId)
            if (existing.qmindNotebookName.isNullOrBlank()) {
                // Legacy profile from before the qmind naming rule existed; back-fill it once.
                val named = existing.copy(
                    qmindNotebookName = QmindNaming.notebookName(identityId, displayName),
                    updatedAt = now,
                )
                retryOnConnectionFailure { drive.writeJson(identityId, path, named, ProfileJson.serializer()) }
                return named
            }
            return existing
        }
        val fresh = ProfileJson(
            identityId = identityId,
            externalIdHash = Hashing.sha256Hex(externalId),
            displayName = displayName,
            isolationMode = "LOGICAL",
            qmindNotebookName = QmindNaming.notebookName(identityId, displayName),
            createdAt = now,
            updatedAt = now,
        )
        retryOnConnectionFailure { drive.writeJson(identityId, path, fresh, ProfileJson.serializer()) }
        return fresh
    }

    suspend fun currentIdentityId(): String? {
        val (id, _) = prefs.snapshotAccount()
        if (id.isNotBlank()) return id
        return withContext(Dispatchers.IO) { accountDao.all().firstOrNull()?.identityId }
    }

    suspend fun requireIdentityId(): String =
        currentIdentityId() ?: throw IllegalStateException("尚未登录")

    suspend fun account(identityId: String): AccountEntity? = withContext(Dispatchers.IO) {
        accountDao.findById(identityId)
    }

    suspend fun logout(identityId: String) {
        prefs.clearCurrentAccount()
        // Room rows stay: they are a cache scoped by identity_id and hold no secrets.
    }
}
