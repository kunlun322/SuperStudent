package com.superstudent.core.repository

import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.ProfileJson
import com.superstudent.core.model.QmindDeletionState
import com.superstudent.core.model.QmindSourceRefJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Owns the qmind binding stored in profile.json (design §7.2).
 *
 * The Notebook ID is the only authoritative link: ingest, retrieval and delete all resolve through
 * it. `isolationMode` stays LOGICAL — one Notebook per identity by naming convention, not a
 * platform-enforced permission boundary.
 */
class ProfileRepository(private val drive: DriveRepository) {

    private val mutex = Mutex()

    suspend fun read(identityId: String): ProfileJson? =
        drive.readJsonOrNull(identityId, DrivePath.profile(), ProfileJson.serializer())

    suspend fun update(identityId: String, mutate: (ProfileJson) -> ProfileJson): ProfileJson? =
        mutex.withLock {
            val current = read(identityId) ?: return@withLock null
            val next = mutate(current).copy(updatedAt = Instant.now().toString())
            drive.writeJson(identityId, DrivePath.profile(), next, ProfileJson.serializer())
            next
        }

    /** sourceId → ingested qmindSourceId, so an unchanged sha256 can be skipped on re-ingest. */
    suspend fun ingestedSourceIds(identityId: String): Map<String, String?> =
        read(identityId)?.qmindSources?.associate { it.sourceId to it.qmindSourceId } ?: emptyMap()

    suspend fun notebookId(identityId: String): String? = read(identityId)?.qmindNotebookId

    /**
     * Merges what the Agent reported after QMIND_INDEX. Sources the report does not mention keep
     * their previous mapping; a re-ingested source drops any stale DELETE_PENDING marker.
     */
    suspend fun applyIngestReport(
        identityId: String,
        notebookId: String?,
        ownerUserHash: String?,
        ingested: List<QmindSourceRefJson>,
    ) {
        update(identityId) { p ->
            val merged = p.qmindSources.associateBy { it.sourceId }.toMutableMap()
            ingested.forEach { ref ->
                val existing = merged[ref.sourceId]
                merged[ref.sourceId] = ref.copy(
                    qmindSourceId = ref.qmindSourceId ?: existing?.qmindSourceId,
                    displayName = ref.displayName ?: existing?.displayName,
                    ingestedAt = ref.ingestedAt ?: Instant.now().toString(),
                    deletion = QmindDeletionState.NONE,
                )
            }
            p.copy(
                qmindNotebookId = notebookId?.takeIf { it.isNotBlank() } ?: p.qmindNotebookId,
                qmindOwnerUserHash = ownerUserHash?.takeIf { it.isNotBlank() } ?: p.qmindOwnerUserHash,
                qmindSources = merged.values.toList(),
            )
        }
    }

    /**
     * Flags an unconfirmed delete so the UI hides the affected content instead of showing chunks
     * that may still be retrievable (FR-10). An empty [sourceIds] flags the whole profile.
     */
    suspend fun setDeletePending(identityId: String, sourceIds: Set<String>, pending: Boolean) {
        val state = if (pending) QmindDeletionState.DELETE_PENDING else QmindDeletionState.NONE
        update(identityId) { p ->
            if (sourceIds.isEmpty()) {
                p.copy(qmindDeletion = state)
            } else {
                p.copy(
                    qmindSources = p.qmindSources.map {
                        if (it.sourceId in sourceIds) it.copy(deletion = state) else it
                    },
                )
            }
        }
    }

    /** Drops a source mapping once both `source delete` and the Drive removal are confirmed. */
    suspend fun forgetSources(identityId: String, sourceIds: Set<String>) {
        update(identityId) { p ->
            p.copy(qmindSources = p.qmindSources.filterNot { it.sourceId in sourceIds })
        }
    }
}
