package com.superstudent.core.repository

import com.superstudent.core.database.ArtifactDao
import com.superstudent.core.database.ArtifactEntity
import com.superstudent.core.database.SsDatabase
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.ArtifactValidationException
import com.superstudent.core.model.CardsData
import com.superstudent.core.model.CitationJson
import com.superstudent.core.model.CitationsData
import com.superstudent.core.model.DeckManifestData
import com.superstudent.core.model.ExercisesData
import com.superstudent.core.model.MindmapData
import com.superstudent.core.model.PlanData
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.ResultValidator
import com.superstudent.core.security.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/** A deck.pptx whose OOXML structure, slide count and hash were verified against its sidecar. */
class DeckFile(val bytes: ByteArray, val sha256: String, val slideCount: Int)

class DeckCorruptException(message: String) : Exception(message)

/**
 * The five result entries AC-01 requires, all strictly validated. Nothing reaches the UI unless
 * every one of them passed, so a malformed mindmap or deck can never be shown as a success.
 *
 * [mindmapHtml] is the HTML that cleared the visual contract v2 cross-check, and only that HTML:
 * a null here means there is no accepted map to render, so the UI falls back to the PNG or the
 * native tree built from `mindmap.json` (ZLQ-140 D3).
 */
data class PackageResults(
    val plan: PlanData,
    val cards: CardsData,
    val mindmap: MindmapData,
    val deck: DeckManifestData,
    val exercises: ExercisesData,
    val citations: CitationsData,
    val mindmapHtml: String? = null,
) {
    val citationById: Map<String, CitationJson> = citations.citations.associateBy { it.citationId }
}

/**
 * Downloads and STRICTLY validates result artifacts (design §5).
 * A task never reaches SUCCEEDED unless all five artifacts pass validation.
 */
class ResultsRepository(
    private val drive: DriveRepository,
    private val artifactDao: ArtifactDao,
    private val db: SsDatabase,
) {

    companion object {
        private val SLIDE_PART = Regex("^ppt/slides/slide\\d+\\.xml$")
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }

    /**
     * @param strictV2 `true` for the run that decides whether a task SUCCEEDED — a document-style
     *   `mindmap.html` then fails the whole fetch, the package goes back to READY and the student
     *   can regenerate it. `false` only when re-reading a package that may predate visual contract
     *   v2: such a package is never retroactively judged bad, it just yields no accepted HTML.
     */
    suspend fun fetch(
        identityId: String,
        packageId: String,
        allowedSourceIds: Set<String>? = null,
        strictV2: Boolean = true,
    ): PackageResults =
        withContext(Dispatchers.IO) {
            val citationsRaw = text(identityId, packageId, "citations.json")
            val planRaw = text(identityId, packageId, "plan.json")
            val cardsRaw = text(identityId, packageId, "cards.json")
            val mindmapRaw = text(identityId, packageId, "mindmap.json")
            // Same batch as mindmap.json: the map and its visual manifest are validated together,
            // so neither can be recorded without the other having been read.
            val mindmapHtmlRaw = if (strictV2) {
                text(identityId, packageId, "mindmap.html")
            } else {
                runCatching { text(identityId, packageId, "mindmap.html") }.getOrNull()
            }
            val deckManifestRaw = text(identityId, packageId, "deck.manifest.json")
            val exercisesRaw = text(identityId, packageId, "exercises.json")
            val deckBytes = drive.downloadBytes(identityId, DrivePath.deckPptx(packageId))

            val citations = ResultValidator.validateCitations(citationsRaw, packageId, allowedSourceIds)
            val known = citations.citations.map { it.citationId }.toSet()
            val plan = ResultValidator.validatePlan(planRaw, packageId, known)
            val cards = ResultValidator.validateCards(cardsRaw, packageId, known)
            val mindmap = ResultValidator.validateMindmap(mindmapRaw, packageId, known)
            val deck = ResultValidator.validateDeckManifest(deckManifestRaw, packageId, known)
            val topicIds = plan.topics.map { it.topicId }.toSet()
            val exercises = ResultValidator.validateExercises(exercisesRaw, packageId, known, topicIds)

            // AC-08 gate: the binary must really be the deck the sidecar describes.
            verifyDeck(deckBytes, deck)

            // Strict here means this fetch decides whether the task SUCCEEDED: a rejected map
            // throws, so the run fails and the package returns to READY instead of shipping an
            // outline as a mindmap. A lenient re-read only ever yields no HTML.
            val mindmapHtml = ResultValidator.acceptedMindmapHtml(mindmapHtmlRaw, mindmap, strictV2)

            val recorded = mutableListOf(
                Triple("PLAN", "plan.json", planRaw.encodeToByteArray()),
                Triple("CARDS", "cards.json", cardsRaw.encodeToByteArray()),
                Triple("CITATIONS", "citations.json", citationsRaw.encodeToByteArray()),
                Triple("MINDMAP", "mindmap.json", mindmapRaw.encodeToByteArray()),
                Triple("DECK", "deck.manifest.json", deckManifestRaw.encodeToByteArray()),
                Triple("EXERCISES", "exercises.json", exercisesRaw.encodeToByteArray()),
            )
            // Only HTML that passed the cross-check becomes a recorded artifact.
            if (mindmapHtml != null) {
                recorded += Triple("MINDMAP_HTML", "mindmap.html", mindmapHtml.encodeToByteArray())
            }
            recordArtifacts(packageId, recorded)
            PackageResults(plan, cards, mindmap, deck, exercises, citations, mindmapHtml)
        }

    /** Downloads and re-verifies deck.pptx right before an export hands it to office software. */
    suspend fun fetchDeck(
        identityId: String,
        packageId: String,
        allowedSourceIds: Set<String>? = null,
    ): DeckFile =
        withContext(Dispatchers.IO) {
            val manifestRaw = text(identityId, packageId, "deck.manifest.json")
            val citationsRaw = text(identityId, packageId, "citations.json")
            val known = ResultValidator.validateCitations(citationsRaw, packageId, allowedSourceIds)
                .citations.map { it.citationId }.toSet()
            val manifest = ResultValidator.validateDeckManifest(manifestRaw, packageId, known)
            val bytes = drive.downloadBytes(identityId, DrivePath.deckPptx(packageId))
            verifyDeck(bytes, manifest)
        }

    /**
     * OOXML opens, slide count matches the sidecar, and the hash is the published one. Any mismatch
     * means the file the student would open is not the file that was validated.
     */
    private fun verifyDeck(bytes: ByteArray, manifest: DeckManifestData): DeckFile {
        if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)) {
            throw DeckCorruptException("deck.pptx 不是有效的 OOXML 压缩包")
        }
        val slides = countSlideParts(bytes)
        if (slides != manifest.slideCount) {
            throw DeckCorruptException("deck.pptx 实际 $slides 页，与 deck.manifest.json 声明的 ${manifest.slideCount} 页不一致")
        }
        val sha = Hashing.sha256Hex(bytes)
        val expected = manifest.sha256?.lowercase()
        if (expected != null && expected != sha) {
            throw DeckCorruptException("deck.pptx 校验和不匹配，文件可能已损坏")
        }
        if (manifest.sizeBytes != null && manifest.sizeBytes != bytes.size.toLong()) {
            throw DeckCorruptException("deck.pptx 大小 ${bytes.size} 与声明的 ${manifest.sizeBytes} 不一致")
        }
        return DeckFile(bytes, sha, slides)
    }

    private fun countSlideParts(bytes: ByteArray): Int {
        var count = 0
        var sawPresentation = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (SLIDE_PART.matches(name)) count++
                if (name == "ppt/presentation.xml") sawPresentation = true
                zip.closeEntry()
            }
        }
        if (!sawPresentation) throw DeckCorruptException("deck.pptx 缺少 ppt/presentation.xml，不是有效的演示文稿")
        return count
    }

    /** True when all five result files exist and pass strict validation. */
    suspend fun validatePublished(identityId: String, packageId: String): Boolean = try {
        fetch(identityId, packageId)
        true
    } catch (_: ArtifactValidationException) {
        false
    } catch (_: DeckCorruptException) {
        false
    } catch (_: com.superstudent.core.drive.DriveNotFoundException) {
        false
    }

    private suspend fun text(identityId: String, packageId: String, fileName: String): String =
        drive.downloadBytes(identityId, DrivePath.result(packageId, fileName)).decodeToString()

    private suspend fun recordArtifacts(packageId: String, files: List<Triple<String, String, ByteArray>>) {
        val now = Instant.now().toString()
        val rows = files.map { (kind, fileName, bytes) ->
            ArtifactEntity(
                artifactId = "$packageId-$kind",
                packageId = packageId,
                kind = kind,
                drivePath = DrivePath.result(packageId, fileName),
                sha256 = Hashing.sha256Hex(bytes),
                sizeBytes = bytes.size.toLong(),
                schemaVersion = 1,
                localUri = null,
                cacheState = "NONE",
                updatedAt = now,
            )
        }
        db.inTransaction { artifactDao.upsertAll(rows) }
    }

    suspend fun latestArtifact(packageId: String, kind: String): ArtifactEntity? =
        withContext(Dispatchers.IO) { artifactDao.findLatest(packageId, kind) }

    /**
     * Result kinds already published for this package. `artifact` also holds CITATIONS, which is not
     * a student-visible result and is absent from [ResultKind], so it is filtered out here — otherwise
     * a package whose only artifact is citations would advertise a results entry that opens blank.
     */
    fun observePublishedKinds(packageId: String): Flow<Set<ResultKind>> =
        artifactDao.observeByPackage(packageId).map { it.toPublishedKinds() }
}

/** The student-visible subset of recorded artifact kinds; CITATIONS is stored but never surfaced. */
internal fun List<ArtifactEntity>.toPublishedKinds(): Set<ResultKind> =
    mapNotNull { row -> ResultKind.entries.firstOrNull { it.name == row.kind } }.toSet()
