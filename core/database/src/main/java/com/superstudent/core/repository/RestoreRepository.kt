package com.superstudent.core.repository

import com.superstudent.core.database.ArtifactDao
import com.superstudent.core.database.ArtifactEntity
import com.superstudent.core.database.ExerciseProgressDao
import com.superstudent.core.database.ExerciseProgressEntity
import com.superstudent.core.database.FlashcardProgressDao
import com.superstudent.core.database.FlashcardProgressEntity
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.PackageDao
import com.superstudent.core.database.SsDatabase
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.SourceDao
import com.superstudent.core.database.TaskRunDao
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CardMastery
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.LearningGoal
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.PackageJson
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.ProfileJson
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.SourceFormat
import com.superstudent.core.model.TaskJson
import com.superstudent.core.model.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

enum class RestoreResult { OK, RECOVERY_REQUIRED, NO_REMOTE_DATA }

/**
 * Reinstall / device-change recovery per design §3.5.
 * Drive JSON is authoritative; Room is rebuilt from it inside one transaction.
 */
class RestoreRepository(
    private val drive: DriveRepository,
    private val db: SsDatabase,
    private val packageDao: PackageDao,
    private val sourceDao: SourceDao,
    private val taskDao: TaskRunDao,
    private val artifactDao: ArtifactDao,
    private val cardDao: FlashcardProgressDao,
    private val exerciseDao: ExerciseProgressDao,
) {

    suspend fun validateProfile(identityId: String, expectedExternalIdHash: String?): ProfileJson? =
        withContext(Dispatchers.IO) {
            val profile = drive.readJsonOrNull(identityId, DrivePath.profile(), ProfileJson.serializer())
                ?: return@withContext null
            check(profile.schemaVersion == 1) { "profile.json schemaVersion 不支持: ${profile.schemaVersion}" }
            check(profile.identityId == identityId) {
                "profile.json identityId 与登录 Identity 不一致"
            }
            if (expectedExternalIdHash != null && profile.externalIdHash.isNotBlank()) {
                check(profile.externalIdHash == expectedExternalIdHash) {
                    "profile.json externalIdHash 校验失败"
                }
            }
            profile
        }

    suspend fun restore(identityId: String): RestoreResult = withContext(Dispatchers.IO) {
        val index = drive.readJsonOrNull(identityId, DrivePath.index(), com.superstudent.core.model.IndexJson.serializer())
        val progress = drive.readJsonOrNull(
            identityId,
            DrivePath.progress(),
            com.superstudent.core.model.ProgressJson.serializer(),
        )

        if (index == null && progress == null) {
            val rebuilt = rebuildIndexFromPackages(identityId)
            if (rebuilt == null) return@withContext RestoreResult.NO_REMOTE_DATA
            applyIndex(identityId, rebuilt)
            return@withContext RestoreResult.RECOVERY_REQUIRED
        }

        if (index != null) {
            applyIndex(identityId, index)
        } else {
            val rebuilt = rebuildIndexFromPackages(identityId)
            if (rebuilt != null) applyIndex(identityId, rebuilt, recovery = true)
        }

        progress?.let { applyProgress(identityId, it) }
        RestoreResult.OK
    }

    private suspend fun applyIndex(
        identityId: String,
        index: com.superstudent.core.model.IndexJson,
        recovery: Boolean = false,
    ) {
        check(index.schemaVersion == 1) { "index.json schemaVersion 不支持: ${index.schemaVersion}" }
        val remoteIds = index.packages.map { it.packageId }.toSet()

        db.inTransaction {
            // Local-only drafts (never uploaded) survive as LOCAL_ONLY instead of being dropped.
            packageDao.listByIdentity(identityId).forEach { local ->
                if (local.packageId !in remoteIds && local.status != PackageStatus.LOCAL_ONLY.name) {
                    packageDao.upsert(local.copy(status = PackageStatus.LOCAL_ONLY.name))
                }
            }
            index.packages.forEach { p ->
                packageDao.upsert(
                    LearningPackageEntity(
                        packageId = p.packageId,
                        identityId = identityId,
                        title = p.title,
                        goal = p.goal.name,
                        chapterRange = p.chapterRange,
                        status = (if (recovery) PackageStatus.RECOVERY_REQUIRED else p.status).name,
                        latestTaskId = p.latestTaskId,
                        sourceCount = p.sourceIds.size,
                        createdAt = p.createdAt,
                        updatedAt = p.updatedAt,
                    )
                )
            }
        }

        index.packages.forEach { p ->
            runCatching { restorePackageDetail(identityId, p.packageId) }
        }
    }

    private suspend fun restorePackageDetail(identityId: String, packageId: String) {
        val json = drive.readJsonOrNull(identityId, DrivePath.packageJson(packageId), PackageJson.serializer())
            ?: return
        db.inTransaction {
            sourceDao.upsertAll(
                json.sources.map {
                    SourceAssetEntity(
                        sourceId = it.sourceId,
                        packageId = packageId,
                        drivePath = it.drivePath,
                        displayName = it.displayName,
                        mimeType = it.mimeType,
                        kind = it.kind,
                        sizeBytes = it.sizeBytes,
                        sha256 = it.sha256,
                        uploadState = UploadState.UPLOADED.name,
                        // Reinstall drops every local handle and grant; the content lives on Drive.
                        localUri = null,
                        addedAt = it.addedAt,
                        localAccessMode = LocalAccessMode.NONE.name,
                        canonicalType = canonicalTypeOf(it),
                        updatedAt = it.addedAt,
                    )
                }
            )
        }
        restoreResultPointers(identityId, packageId, json)
        restoreLatestTask(identityId, packageId, json.latestTaskId)
    }

    /**
     * `package.json` carries no canonical type, so derive it from the object's extension and fall
     * back to the recorded MIME. A legacy `.bin` object stays `UNKNOWN`, which keeps it out of
     * generation instead of pretending it is parseable.
     */
    private fun canonicalTypeOf(ref: com.superstudent.core.model.SourceRefJson): String {
        val fromPath = runCatching { SourceFormat.fromExtension(DrivePath.fileName(ref.drivePath)) }
            .getOrNull()
            ?.takeIf { it != CanonicalType.UNKNOWN }
        val fromMime = SourceFormat.fromMime(ref.mimeType)?.takeIf { it != CanonicalType.UNKNOWN }
        return (fromPath ?: fromMime ?: CanonicalType.UNKNOWN).name
    }

    /**
     * §3.5 step 4: task.json is lazy-loaded per package so the task detail keeps its duration,
     * failure reason and credits (FR-13) after a reinstall. A missing or corrupt file is skipped —
     * the run history is a cache, never something to rebuild by guessing.
     */
    private suspend fun restoreLatestTask(identityId: String, packageId: String, taskId: String?) {
        if (taskId.isNullOrBlank()) return
        val json = drive.readJsonOrNull(identityId, DrivePath.taskJson(packageId, taskId), TaskJson.serializer())
            ?: return
        db.inTransaction {
            taskDao.upsert(
                TaskRunEntity(
                    taskId = json.taskId,
                    attempt = json.attempt,
                    packageId = json.packageId,
                    runId = json.runId,
                    sessionId = json.sessionId,
                    state = json.state.name,
                    stage = json.stage?.name,
                    progress = json.progressPercent,
                    resumeFromStage = json.resumeFromStage?.name,
                    lastEventId = json.lastEventId,
                    errorCode = json.error?.code,
                    errorMessage = json.error?.message,
                    credits = json.usage.totalCredits,
                    // Drive is the cross-device authority: both counters are upserted wholesale,
                    // NULLs included. No max() against the local row, no recompute from the current
                    // time, no extra Session query.
                    activeSeconds = restoredSeconds(json.schemaVersion, json.usage.activeSeconds),
                    durationSeconds = restoredSeconds(json.schemaVersion, json.usage.durationSeconds),
                    cleanupPending = false,
                    createdAt = json.createdAt,
                    startedAt = json.startedAt,
                    finishedAt = json.finishedAt,
                    updatedAt = json.updatedAt,
                )
            )
        }
    }

    private suspend fun restoreResultPointers(identityId: String, packageId: String, json: PackageJson) {
        val now = Instant.now().toString()
        // §3.5 step 4: record pointers only. mindmap.html / deck.pptx / card images stay on Drive
        // and are pulled on demand when the student actually opens that tab.
        val kinds = listOf(
            "PLAN" to "plan.json",
            "CARDS" to "cards.json",
            "CITATIONS" to "citations.json",
            "MINDMAP" to "mindmap.json",
            "DECK" to "deck.manifest.json",
            "EXERCISES" to "exercises.json",
        )
        val found = mutableListOf<ArtifactEntity>()
        kinds.forEach { (kind, fileName) ->
            val path = DrivePath.result(packageId, fileName)
            if (drive.exists(identityId, path)) {
                found += ArtifactEntity(
                    artifactId = "$packageId-$kind",
                    packageId = packageId,
                    kind = kind,
                    drivePath = path,
                    sha256 = null,
                    sizeBytes = 0,
                    schemaVersion = 1,
                    localUri = null,
                    cacheState = "NONE",
                    updatedAt = now,
                )
            }
        }
        if (found.isNotEmpty()) {
            db.inTransaction { artifactDao.upsertAll(found) }
        }
    }

    private suspend fun applyProgress(identityId: String, progress: com.superstudent.core.model.ProgressJson) {
        check(progress.schemaVersion == 1) { "progress.json schemaVersion 不支持: ${progress.schemaVersion}" }
        val now = Instant.now().toString()
        db.inTransaction {
            progress.packages.forEach { p ->
                cardDao.upsertAll(
                    p.cards.map {
                        FlashcardProgressEntity(
                            identityId = identityId,
                            packageId = p.packageId,
                            cardId = it.cardId,
                            mastery = it.mastery.name,
                            correctStreak = it.correctStreak,
                            reviewCount = it.reviewCount,
                            lastReviewedAt = it.lastReviewedAt,
                            nextReviewAt = it.nextReviewAt,
                            updatedAt = it.updatedAt.ifBlank { now },
                        )
                    }
                )
                exerciseDao.upsertAll(
                    p.exercises.map {
                        ExerciseProgressEntity(
                            identityId = identityId,
                            packageId = p.packageId,
                            exerciseId = it.exerciseId,
                            attempts = it.attempts,
                            lastCorrect = it.lastCorrect,
                            updatedAt = it.updatedAt.ifBlank { now },
                        )
                    }
                )
            }
        }
    }

    /** index.json missing or corrupt: rebuild by scanning each package directory's package.json. */
    private suspend fun rebuildIndexFromPackages(identityId: String): com.superstudent.core.model.IndexJson? {
        val packagesRoot = DrivePath.under(DrivePath.ROOT, "packages")
        val entries = runCatching { drive.listEntries(identityId, packagesRoot) }.getOrNull() ?: return null
        val now = Instant.now().toString()
        val pkgs = entries
            .filter { (it.type.equals("directory", true) || it.type == "dir") }
            .mapNotNull { dir ->
                val packageId = DrivePath.fileName(dir.path)
                val json = drive.readJsonOrNull(
                    identityId,
                    DrivePath.packageJson(packageId),
                    PackageJson.serializer(),
                ) ?: return@mapNotNull null
                com.superstudent.core.model.IndexPackage(
                    packageId = json.packageId,
                    title = json.title,
                    goal = json.goal,
                    chapterRange = json.chapterRange,
                    status = PackageStatus.RECOVERY_REQUIRED,
                    sourceIds = json.sources.map { it.sourceId },
                    latestTaskId = json.latestTaskId,
                    resultKinds = emptyList(),
                    createdAt = json.createdAt,
                    updatedAt = json.updatedAt,
                    deletedAt = null,
                )
            }
        if (pkgs.isEmpty()) return null
        return com.superstudent.core.model.IndexJson(
            identityId = identityId,
            revision = 1,
            updatedAt = now,
            packages = pkgs,
        )
    }
}
