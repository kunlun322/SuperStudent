package com.superstudent.core.repository

import com.superstudent.core.database.ExerciseProgressDao
import com.superstudent.core.database.ExerciseProgressEntity
import com.superstudent.core.database.FlashcardProgressDao
import com.superstudent.core.database.FlashcardProgressEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CardMastery
import com.superstudent.core.model.CardProgressJson
import com.superstudent.core.model.ExerciseProgressJson
import com.superstudent.core.model.PlanProgress
import com.superstudent.core.model.ProgressJson
import com.superstudent.core.model.ProgressPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Learning progress. Mastery is written ONLY to Room + progress.json — never back into cards.json,
 * so regenerating cards cannot erase review history (design §5.3).
 */
class ProgressRepository(
    private val drive: DriveRepository,
    private val cardDao: FlashcardProgressDao,
    private val exerciseDao: ExerciseProgressDao,
    private val progressStore: ProgressStore,
) {

    fun observeCards(identityId: String, packageId: String): Flow<List<FlashcardProgressEntity>> =
        cardDao.observeByIdentityPackage(identityId, packageId)

    suspend fun cardProgress(identityId: String, packageId: String): List<FlashcardProgressEntity> =
        withContext(Dispatchers.IO) { cardDao.list(identityId, packageId) }

    fun observeExercises(identityId: String, packageId: String): Flow<List<ExerciseProgressEntity>> =
        exerciseDao.observeByIdentityPackage(identityId, packageId)

    suspend fun exerciseProgress(identityId: String, packageId: String): List<ExerciseProgressEntity> =
        withContext(Dispatchers.IO) { exerciseDao.list(identityId, packageId) }

    /**
     * Records one graded answer (FR-09). Written to Room + progress.json only — exercises.json stays
     * exactly as the Agent published it, so a re-practice session always sees the original stem.
     */
    suspend fun recordAnswer(
        identityId: String,
        packageId: String,
        exerciseId: String,
        correct: Boolean,
    ): ExerciseProgressEntity = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        val existing = exerciseDao.find(identityId, packageId, exerciseId)
        val entity = ExerciseProgressEntity(
            identityId = identityId,
            packageId = packageId,
            exerciseId = exerciseId,
            attempts = (existing?.attempts ?: 0) + 1,
            lastCorrect = correct,
            updatedAt = now,
        )
        exerciseDao.upsert(entity)
        publish(identityId, packageId)
        entity
    }

    /** Wrong answers first, most recently attempted last — the re-practice queue FR-09 asks for. */
    fun orderForRePractice(progress: List<ExerciseProgressEntity>): List<ExerciseProgressEntity> =
        progress.sortedWith(
            compareBy<ExerciseProgressEntity> { if (it.lastCorrect) 1 else 0 }
                .thenBy { it.attempts }
                .thenBy { it.updatedAt }
                .thenBy { it.exerciseId }
        )

    /** Review queue: unmastered first (NEW before LEARNING), then by due time. */
    fun orderForReview(progress: List<FlashcardProgressEntity>): List<FlashcardProgressEntity> =
        progress.sortedWith(
            compareBy<FlashcardProgressEntity> { masteryRank(it.mastery) }
                .thenBy { it.nextReviewAt ?: "" }
                .thenBy { it.cardId }
        )

    private fun masteryRank(mastery: String): Int = when (runCatching { CardMastery.valueOf(mastery) }.getOrNull()) {
        CardMastery.NEW -> 0
        CardMastery.LEARNING -> 1
        CardMastery.MASTERED -> 2
        null -> 0
    }

    suspend fun recordReview(
        identityId: String,
        packageId: String,
        cardId: String,
        correct: Boolean,
    ): FlashcardProgressEntity = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val existing = cardDao.find(identityId, packageId, cardId)
        val streak = if (correct) (existing?.correctStreak ?: 0) + 1 else 0
        val mastery = when {
            !correct -> CardMastery.LEARNING
            streak >= MASTER_STREAK -> CardMastery.MASTERED
            else -> CardMastery.LEARNING
        }
        val entity = FlashcardProgressEntity(
            identityId = identityId,
            packageId = packageId,
            cardId = cardId,
            mastery = mastery.name,
            correctStreak = streak,
            reviewCount = (existing?.reviewCount ?: 0) + 1,
            lastReviewedAt = now.toString(),
            nextReviewAt = now.plus(reviewInterval(mastery, streak), ChronoUnit.MINUTES).toString(),
            updatedAt = now.toString(),
        )
        cardDao.upsert(entity)
        publish(identityId, packageId)
        entity
    }

    private fun reviewInterval(mastery: CardMastery, streak: Int): Long = when (mastery) {
        CardMastery.NEW -> 10
        CardMastery.LEARNING -> if (streak <= 0) 10 else 60L * 24 * streak.coerceAtMost(3)
        CardMastery.MASTERED -> 60L * 24 * 7
    }

    suspend fun completedTopics(identityId: String, packageId: String): Set<String> =
        withContext(Dispatchers.IO) {
            progressStore.read(identityId)
                ?.packages
                ?.firstOrNull { it.packageId == packageId }
                ?.plan
                ?.completedTopicIds
                ?.toSet()
                ?: emptySet()
        }

    suspend fun markTopicCompleted(identityId: String, packageId: String, topicId: String) =
        withContext(Dispatchers.IO) {
            progressStore.update(identityId) { json ->
                val pkg = json.packages.firstOrNull { it.packageId == packageId } ?: ProgressPackage(packageId)
                val completed = (pkg.plan?.completedTopicIds ?: emptyList()).toMutableList()
                if (topicId !in completed) completed += topicId
                replacePackage(
                    json,
                    pkg.copy(plan = PlanProgress(completedTopicIds = completed, lastTopicId = topicId)),
                )
            }
        }

    /** Rebuilds the Drive progress.json package entry from Room (Room is authoritative locally). */
    suspend fun publish(identityId: String, packageId: String) {
        val cards = withContext(Dispatchers.IO) { cardDao.list(identityId, packageId) }
        val exercises = withContext(Dispatchers.IO) { exerciseDao.list(identityId, packageId) }
        progressStore.update(identityId) { json ->
            val pkg = json.packages.firstOrNull { it.packageId == packageId } ?: ProgressPackage(packageId)
            replacePackage(
                json,
                pkg.copy(
                    cards = cards.map {
                        CardProgressJson(
                            cardId = it.cardId,
                            mastery = runCatching { CardMastery.valueOf(it.mastery) }.getOrDefault(CardMastery.NEW),
                            correctStreak = it.correctStreak,
                            reviewCount = it.reviewCount,
                            lastReviewedAt = it.lastReviewedAt,
                            nextReviewAt = it.nextReviewAt,
                            updatedAt = it.updatedAt,
                        )
                    },
                    exercises = exercises.map {
                        ExerciseProgressJson(
                            exerciseId = it.exerciseId,
                            attempts = it.attempts,
                            lastCorrect = it.lastCorrect,
                            updatedAt = it.updatedAt,
                        )
                    },
                ),
            )
        }
    }

    private fun replacePackage(json: ProgressJson, pkg: ProgressPackage): ProgressJson =
        json.copy(packages = json.packages.filterNot { it.packageId == pkg.packageId } + pkg)

    companion object {
        const val MASTER_STREAK = 3
    }
}

/** Serialized read-modify-write of progress.json with a monotonic revision. */
class ProgressStore(private val drive: DriveRepository) {
    private val mutex = Mutex()

    suspend fun read(identityId: String): ProgressJson? = mutex.withLock {
        withContext(Dispatchers.IO) {
            drive.readJsonOrNull(identityId, DrivePath.progress(), ProgressJson.serializer())
        }
    }

    suspend fun update(identityId: String, mutate: (ProgressJson) -> ProgressJson): ProgressJson =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = drive.readJsonOrNull(identityId, DrivePath.progress(), ProgressJson.serializer())
                    ?: ProgressJson(identityId = identityId, revision = 0, updatedAt = Instant.now().toString())
                val next = mutate(current).let {
                    it.copy(
                        identityId = identityId,
                        revision = it.revision + 1,
                        updatedAt = Instant.now().toString(),
                    )
                }
                drive.writeJson(identityId, DrivePath.progress(), next, ProgressJson.serializer())
                next
            }
        }

    suspend fun write(identityId: String, json: ProgressJson) = mutex.withLock {
        withContext(Dispatchers.IO) {
            drive.writeJson(identityId, DrivePath.progress(), json, ProgressJson.serializer())
        }
    }
}
