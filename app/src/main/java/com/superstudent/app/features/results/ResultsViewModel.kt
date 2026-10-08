package com.superstudent.app.features.results

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.superstudent.app.AppContainer
import com.superstudent.core.database.ExerciseProgressEntity
import com.superstudent.core.database.FlashcardProgressEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.model.CardImageStatus
import com.superstudent.core.model.CardMastery
import com.superstudent.core.model.MINDMAP_VISUAL_CONTRACT_VERSION
import com.superstudent.core.repository.DeckFile
import com.superstudent.core.repository.PackageNotFoundException
import com.superstudent.core.repository.PackageResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ResultsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val results: PackageResults? = null,
    val progress: Map<String, FlashcardProgressEntity> = emptyMap(),
    val exerciseProgress: Map<String, ExerciseProgressEntity> = emptyMap(),
    val completedTopicIds: Set<String> = emptySet(),
    // FR-07: the HTML is the primary renderer; the PNG only exists as the Android 9 fallback.
    val mindmapHtml: String? = null,
    val mindmapHtmlError: String? = null,
    val mindmapPng: File? = null,
    val mindmapLoading: Boolean = false,
    // FR-08
    val deckBusy: Boolean = false,
    val deckError: String? = null,
    val deckSavedUri: Uri? = null,
    // design §6: a picture that never arrives must not stop the card from being usable.
    val cardImages: Map<String, File> = emptyMap(),
    val imagesEnabled: Boolean = true,
    val imagesBusy: Boolean = false,
    val imagesSkippedOffline: Boolean = false,
    // FR-10: unconfirmed deletion hides the affected content instead of showing stale chunks.
    val qmindHidden: Boolean = false,
)

/**
 * Renders the five result entries AC-01 requires (design §5). Every one of them arrives strictly
 * validated — [com.superstudent.core.repository.ResultsRepository.fetch] refuses to return a
 * package whose mindmap, deck or exercises failed validation, so this screen never has to guess.
 *
 * Binaries are lazy: the mindmap HTML/PNG and card pictures are pulled only when the student opens
 * the tab that needs them (§3.5 step 4). Mastery, topic completion and exercise answers are written
 * to Room + progress.json only, never back into cards.json or exercises.json.
 */
class ResultsViewModel(private val container: AppContainer) : ViewModel() {

    private val packageId = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    private var mindmapLoadedFor: String? = null
    private var imagesLoadedFor: String? = null

    fun bind(id: String) {
        if (packageId.value == id) return
        packageId.value = id
        mindmapLoadedFor = null
        imagesLoadedFor = null
        _state.value = ResultsUiState()
        load()
    }

    fun load() {
        val pkg = packageId.value ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull()
            if (identity == null) {
                _state.update { ResultsUiState(loading = false, error = "尚未登录") }
                return@launch
            }
            runCatching {
                val ownedSourceIds = container.packageRepository.listSources(pkg).map { it.sourceId }.toSet()
                // Reading a package is not the success gate: TaskRunner owns that and is strict. A
                // package published before visual contract v2 must still open, so its unaccepted
                // HTML is dropped here instead of failing the whole screen (ZLQ-140 D3.5).
                val results = container.resultsRepository.fetch(identity, pkg, ownedSourceIds, strictV2 = false)
                val cards = container.progressRepository.cardProgress(identity, pkg)
                val exercises = container.progressRepository.exerciseProgress(identity, pkg)
                val completed = container.progressRepository.completedTopics(identity, pkg)
                val profile = container.profileRepository.read(identity)
                val imagesEnabled = container.prefs.cardImagesEnabled.first()
                Loaded(results, cards, exercises, completed, profile?.deletePending == true, imagesEnabled)
            }.onSuccess { loaded ->
                _state.update {
                    it.copy(
                        loading = false,
                        results = loaded.results,
                        progress = loaded.cards.associateBy { row -> row.cardId },
                        exerciseProgress = loaded.exercises.associateBy { row -> row.exerciseId },
                        completedTopicIds = loaded.completed,
                        qmindHidden = loaded.qmindHidden,
                        imagesEnabled = loaded.imagesEnabled,
                        // FR-07: only HTML that cleared visual contract v2 is ever handed to the UI.
                        mindmapHtml = loaded.results.mindmapHtml,
                        mindmapHtmlError = mindmapFallbackHint(loaded.results),
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = when (t) {
                            is PackageNotFoundException -> t.message
                            else -> t.message ?: "读取学习结果失败"
                        },
                    )
                }
            }
        }
    }

    // ---------- FR-07 mindmap ----------

    /**
     * Pulls the PNG fallback on first open of the tab. The HTML is NOT downloaded here any more: it
     * arrives with [load] already cross-checked against `mindmap.json`, so this screen can never
     * render a map that was not accepted. A missing PNG is not fatal either — the native tree built
     * from mindmap.json already satisfies AC-02.
     */
    fun loadMindmapArtifacts() {
        val pkg = packageId.value ?: return
        if (mindmapLoadedFor == pkg) return
        mindmapLoadedFor = pkg
        _state.update { it.copy(mindmapLoading = true) }
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull()
            if (identity == null) {
                _state.update { it.copy(mindmapLoading = false) }
                return@launch
            }
            val png = container.binaryCache.load(identity, DrivePath.mindmapPng(pkg))
            _state.update { it.copy(mindmapLoading = false, mindmapPng = png) }
        }
    }

    /**
     * Why there is no map to show. A package that never declared visual contract v2 is not broken —
     * its HTML was simply never accepted, and the only way to get a graph is to generate again.
     */
    private fun mindmapFallbackHint(results: PackageResults): String? {
        if (results.mindmapHtml != null) return null
        return if (results.mindmap.visualContractVersion != MINDMAP_VISUAL_CONTRACT_VERSION) {
            "旧版导图需重新生成：该学习包生成于图状导图契约之前，已隐藏其未验收的 HTML，可继续使用下方结构树"
        } else {
            "mindmap.html 未通过图状导图验收，已改用 PNG 与下方结构树"
        }
    }

    // ---------- design §6 card images ----------

    /**
     * Sequential prefetch of the cards that actually published an image. Wi-Fi-only is honoured by
     * the caller gate in the screen; the download itself is one file at a time so a slow link is
     * never saturated. An image that fails simply stays absent from the map.
     */
    fun loadCardImages() {
        val pkg = packageId.value ?: return
        if (imagesLoadedFor == pkg) return
        val results = _state.value.results ?: return
        if (!_state.value.imagesEnabled) {
            imagesLoadedFor = pkg
            return
        }
        val paths = results.cards.cards
            .filter { it.image?.status == CardImageStatus.READY }
            .mapNotNull { it.image?.path?.takeIf { path -> path.isNotBlank() } }
        if (paths.isEmpty()) {
            imagesLoadedFor = pkg
            return
        }
        imagesLoadedFor = pkg
        _state.update { it.copy(imagesBusy = true, imagesSkippedOffline = false) }
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull()
            if (identity == null) {
                _state.update { it.copy(imagesBusy = false) }
                return@launch
            }
            // design §6: batch prefetch is Wi-Fi-only by default. Skipping it is not an error —
            // every card stays fully usable without its picture.
            if (!prefetchAllowed()) {
                _state.update { it.copy(imagesBusy = false, imagesSkippedOffline = true) }
                return@launch
            }
            paths.forEach { path ->
                val file = container.binaryCache.load(identity, path) ?: return@forEach
                val cardId = cardIdForImagePath(results, path) ?: return@forEach
                _state.update { it.copy(cardImages = it.cardImages + (cardId to file)) }
            }
            _state.update { it.copy(imagesBusy = false) }
        }
    }

    private val imageAttempts = mutableSetOf<String>()

    /**
     * User-initiated fetch of one card picture. The Wi-Fi gate in [prefetchAllowed] applies to the
     * batch prefetch only; a student who opens a card on mobile data still gets that one image.
     */
    fun loadCardImage(cardId: String) {
        if (!_state.value.imagesEnabled) return
        if (!imageAttempts.add(cardId)) return
        if (_state.value.cardImages.containsKey(cardId)) return
        val path = _state.value.results?.cards?.cards
            ?.firstOrNull { it.cardId == cardId }
            ?.image?.takeIf { it.status == CardImageStatus.READY }
            ?.path?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull()
            if (identity == null) return@launch
            val file = container.binaryCache.load(identity, path) ?: return@launch
            _state.update { it.copy(cardImages = it.cardImages + (cardId to file)) }
        }
    }

    private suspend fun prefetchAllowed(): Boolean {
        if (!container.prefs.prefetchWifiOnly.first()) return true
        val cm = container.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** The Drive path is authoritative; the cardId in the filename is only a hint. */
    private fun cardIdForImagePath(results: PackageResults, path: String): String? =
        results.cards.cards.firstOrNull { it.image?.path == path }?.cardId

    fun setImagesEnabled(enabled: Boolean) {
        _state.update { it.copy(imagesEnabled = enabled) }
        viewModelScope.launch {
            container.prefs.setCardImagesEnabled(enabled)
            if (enabled) {
                imagesLoadedFor = null
                loadCardImages()
            }
        }
    }

    // ---------- FR-08 deck ----------

    /**
     * Re-downloads and re-verifies deck.pptx immediately before an export, so what reaches the office
     * app is the same file that passed validation (AC-08).
     */
    suspend fun fetchDeck(): DeckFile? {
        val pkg = packageId.value ?: return null
        val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull() ?: return null
        _state.update { it.copy(deckBusy = true, deckError = null) }
        val ownedSourceIds = container.packageRepository.listSources(pkg).map { it.sourceId }.toSet()
        val deck = runCatching { container.resultsRepository.fetchDeck(identity, pkg, ownedSourceIds) }
            .onFailure { t -> _state.update { it.copy(deckError = t.message ?: "deck.pptx 校验失败") } }
            .getOrNull()
        _state.update { it.copy(deckBusy = false) }
        return deck
    }

    fun onDeckSaved(uri: Uri) {
        _state.update { it.copy(deckSavedUri = uri, deckError = null) }
    }

    fun clearDeckSaved() {
        _state.update { it.copy(deckSavedUri = null) }
    }

    fun setDeckError(message: String?) {
        _state.update { it.copy(deckError = message) }
    }

    // ---------- FR-09 exercises ----------

    /** Records one graded answer to Room + progress.json. exercises.json is never written back. */
    fun answerExercise(exerciseId: String, correct: Boolean) {
        val pkg = packageId.value ?: return
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull() ?: return@launch
            runCatching { container.progressRepository.recordAnswer(identity, pkg, exerciseId, correct) }
            val rows = runCatching { container.progressRepository.exerciseProgress(identity, pkg) }
                .getOrDefault(emptyList())
            _state.update { it.copy(exerciseProgress = rows.associateBy { row -> row.exerciseId }) }
        }
    }

    /** Wrong answers first — the re-practice queue FR-09 asks for. */
    fun rePracticeOrderFor(exerciseIds: List<String>): List<String> {
        val progress = _state.value.exerciseProgress
        val rows = exerciseIds.mapNotNull { progress[it] }
        return container.progressRepository.orderForRePractice(rows).map { it.exerciseId }
    }

    // ---------- cards + plan ----------

    fun answer(cardId: String, correct: Boolean) {
        val pkg = packageId.value ?: return
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull() ?: return@launch
            runCatching { container.progressRepository.recordReview(identity, pkg, cardId, correct) }
            val progress = runCatching { container.progressRepository.cardProgress(identity, pkg) }
                .getOrDefault(emptyList())
            _state.update { it.copy(progress = progress.associateBy { row -> row.cardId }) }
        }
    }

    fun completeTopic(topicId: String) {
        val pkg = packageId.value ?: return
        viewModelScope.launch {
            val identity = runCatching { container.accountRepository.requireIdentityId() }.getOrNull() ?: return@launch
            runCatching { container.progressRepository.markTopicCompleted(identity, pkg, topicId) }
            _state.update { it.copy(completedTopicIds = it.completedTopicIds + topicId) }
        }
    }

    /** Review queue over ALL cards: never-reviewed and unmastered first, mastered last. */
    fun reviewOrderFor(cardIds: List<String>): List<String> {
        val progress = _state.value.progress
        val rows = cardIds.map { id ->
            progress[id] ?: FlashcardProgressEntity(
                identityId = "",
                packageId = "",
                cardId = id,
                mastery = CardMastery.NEW.name,
                correctStreak = 0,
                reviewCount = 0,
                lastReviewedAt = null,
                nextReviewAt = null,
                updatedAt = "",
            )
        }
        return container.progressRepository.orderForReview(rows).map { it.cardId }
    }

    private class Loaded(
        val results: PackageResults,
        val cards: List<FlashcardProgressEntity>,
        val exercises: List<ExerciseProgressEntity>,
        val completed: Set<String>,
        val qmindHidden: Boolean,
        val imagesEnabled: Boolean,
    )
}
