package com.superstudent.app.features.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.superstudent.app.AppContainer
import com.superstudent.app.features.packages.upload.SourceAccess
import com.superstudent.app.features.packages.upload.SourceDeleteWorker
import com.superstudent.app.features.packages.upload.SourceUploadWorker
import com.superstudent.app.features.packages.upload.UploadForegroundService
import com.superstudent.app.features.tasks.QmindDeleter
import com.superstudent.app.reconcile.StartupReconciler
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.Ids
import com.superstudent.core.model.LocalAccessMode
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.QmindDeletionState
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.UploadState
import com.superstudent.core.upload.SourceFailureClassifier
import com.superstudent.core.upload.SourceRecoveryTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
abstract class IdentityScopedViewModel(protected val container: AppContainer) : ViewModel() {
    val identityId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            identityId.value = container.accountRepository.currentIdentityId()
            // Covers a login that happened after this process started: the application's own pass had
            // to hand itself back for lack of an identity, so this is where it gets its one run.
            StartupReconciler.runOnce(container.appContext)
        }
    }

    protected fun requireIdentity(): String =
        identityId.value ?: throw IllegalStateException("尚未登录")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackageListViewModel(container: AppContainer) : IdentityScopedViewModel(container) {

    val packages: StateFlow<List<LearningPackageEntity>> = identityId
        .filterNotNull()
        .flatMapLatest { container.packageRepository.observePackages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun syncFromCloud() {
        viewModelScope.launch {
            val id = identityId.value ?: return@launch
            runCatching { container.restoreRepository.restore(id) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackageDetailViewModel(container: AppContainer) : IdentityScopedViewModel(container) {

    private val packageId = MutableStateFlow<String?>(null)

    private val _deleteState = MutableStateFlow<SourceDeleteUi?>(null)
    val deleteState: StateFlow<SourceDeleteUi?> = _deleteState.asStateFlow()

    private val _pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<String>> = _pendingDeleteIds.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError

    init {
        // DELETE_PENDING outlives the process, so the reminder has to be rebuilt from profile.json
        // on every entry to the screen — otherwise a half-deleted source looks perfectly healthy.
        viewModelScope.launch {
            identityId.filterNotNull().collect { refreshPendingDeletes(it) }
        }
        // A start the service rejected (the D-2 admission guard, an unusable package) never produced
        // a run row, so this bus is the only channel that can tell the student why nothing happened.
        // Paired with packageId rather than collected directly: bind() runs after this init, and the
        // sticky value belongs to the package that was turned away, not to whichever screen is open.
        viewModelScope.launch {
            combine(
                com.superstudent.app.features.tasks.TaskStartBus.message,
                packageId,
            ) { rejection, id -> rejection?.takeIf { it.packageId == id } }
                .collect { rejection ->
                    // The bus is sticky so a rejection survives the gap between the tap and the
                    // service's answer, including a collector that restarts on rotation. Delivering
                    // it therefore has to consume it, or every later visit to this package replays a
                    // guard decision whose condition is long gone — and the message is worded as an
                    // instruction to wait for a run that is no longer there.
                    if (rejection == null) return@collect
                    _startError.value = rejection.message
                    com.superstudent.app.features.tasks.TaskStartBus.clear()
                }
        }
    }

    private suspend fun refreshPendingDeletes(identity: String) {
        val pending = runCatching { container.profileRepository.read(identity) }.getOrNull()
            ?.qmindSources
            ?.filter { it.deletion == QmindDeletionState.DELETE_PENDING }
            ?.map { it.sourceId }
            .orEmpty()
            .toSet()
        _pendingDeleteIds.value = pending
        if (pending.isNotEmpty() && _deleteState.value == null) {
            _deleteState.value = SourceDeleteUi(
                pending.first(),
                outcome = QmindDeleter.Outcome.DELETE_PENDING,
            )
        }
    }

    val ui: StateFlow<PackageDetailUi> = combine(
        identityId.filterNotNull(),
        packageId.filterNotNull(),
    ) { identity, pkg -> identity to pkg }
        .flatMapLatest { (identity, pkg) ->
            combine(
                container.packageRepository.observePackage(identity, pkg),
                container.packageRepository.observeSources(pkg),
                container.taskRepository.observeLatestForPackage(pkg),
                container.resultsRepository.observePublishedKinds(pkg),
            ) { entity, sources, run, kinds -> PackageDetailUi(entity, sources, run, kinds) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageDetailUi())

    /**
     * Which tombstoned rows a delete worker is actually running right now. This is the presenter's
     * `deleting` input, so a Notebook delete session — which polls for up to six minutes — reads as
     * 删除中… instead of sitting at 待清理 looking frozen.
     *
     * Derived from WorkManager rather than a flag this class sets: the worker outlives the screen, so a
     * flag would either be lost on rotation or lie about work a previous visit started.
     */
    val deletingIds: StateFlow<Set<String>> = ui
        .map { state -> state.sources.filter { it.deletePending }.map { it.sourceId } }
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptySet<String>())
            } else {
                combine(
                    ids.map { id ->
                        WorkManager.getInstance(container.appContext)
                            .getWorkInfosForUniqueWorkFlow(SourceDeleteWorker.workName(id))
                            .map { infos -> id to infos.any { it.state == WorkInfo.State.RUNNING } }
                    },
                ) { pairs -> pairs.filter { it.second }.map { it.first }.toSet() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun bind(id: String) {
        if (packageId.value == null) packageId.value = id
        // §3.4 PAGE_VISIBLE, and it is only ever an accelerator: the deadline worker and the startup
        // rule remain the guarantee, so a package nobody opens still has its orphan reclaimed inside
        // the 30 s bound. Asking here just means the student does not watch it happen late.
        viewModelScope.launch { container.sourceRecovery.request(SourceRecoveryTrigger.PAGE) }
    }

    /**
     * Hands the whole start to the foreground service (ZLQ-103 §2) and nothing more.
     *
     * The requestId is minted here so that a redelivered intent resolves to the same attempt; the
     * attempt row, the Session and the task message all belong to the service. Keeping the submit
     * chain in `viewModelScope` was the defect: this scope dies with the screen, so navigating away
     * cancelled it between `createAttempt` and the sessionId write and left a `QUEUED` orphan that
     * the first-build admission guard counted forever.
     */
    fun startGeneration(androidContext: android.content.Context, resumeFromStage: com.superstudent.core.model.TaskStage? = null) {
        val identity = identityId.value
        val pkg = packageId.value
        if (identity == null || pkg == null) {
            _startError.value = "尚未登录，请重新登录后再试"
            return
        }
        _startError.value = null
        com.superstudent.app.features.tasks.TaskStartBus.clear()
        com.superstudent.app.features.tasks.TaskForegroundService.startNew(
            androidContext, identity, pkg, resumeFromStage, Ids.run(),
        )
    }

    fun clearStartError() {
        _startError.value = null
        com.superstudent.app.features.tasks.TaskStartBus.clear()
    }

    /**
     * The page's cancel button. Like the start, the write belongs to the service: cancelling from
     * this scope would lose the intent if the student navigated away while it was in flight, leaving
     * a run the observer keeps watching.
     */
    fun requestCancel(androidContext: android.content.Context) {
        val identity = identityId.value ?: return
        val run = ui.value.run ?: return
        com.superstudent.app.features.tasks.TaskForegroundService.cancel(
            androidContext, identity, run.taskId, run.attempt,
        )
    }

    /**
     * FR-10 / ZLQ-130 §3.9: arms the tombstone first, then hands the work to `source-delete-<sourceId>`.
     *
     * Arming before anything remote is what fences a PUT already in the air — `beginDelete` clears
     * `attempt_token`, so an in-flight attempt's guarded commit matches 0 rows and never publishes the
     * object it just landed. The remote half is deliberately not run in this scope: it belongs to the
     * worker under the source execution lock, so closing the screen mid-delete cannot lose it, and a
     * failure keeps the row at 待清理 with its 重试删除 entry instead of reporting a success.
     */
    fun removeSource(appContext: android.content.Context, sourceId: String) {
        val pkg = packageId.value ?: return
        viewModelScope.launch {
            val armed = runCatching { container.packageRepository.beginDelete(sourceId) }
            if (armed.isFailure) {
                _deleteState.value = SourceDeleteUi(sourceId, message = "删除失败，请重试")
                return@launch
            }
            // §3.9 item 3: the row's own upload work goes with its tombstone — both the unique work and
            // the foreground service's queue item, which the status quo only cancelled per package.
            runCatching {
                WorkManager.getInstance(appContext)
                    .cancelUniqueWork(SourceUploadWorker.workName(sourceId))
            }
            runCatching { UploadForegroundService.cancelSource(appContext, pkg, sourceId) }
            SourceDeleteWorker.enqueue(appContext, sourceId)
        }
    }

    /**
     * FR-10: a delete that stopped halfway keeps its tombstone until a later pass confirms the remote
     * side. Re-arms anything profile.json still marks DELETE_PENDING — a row an older build left behind,
     * whose Room tombstone is gone — and re-enqueues one worker per source. `ExistingWorkPolicy.KEEP`
     * makes a second tap a no-op rather than a second delete transaction (§3.9 item 5).
     */
    fun retryPendingDeletes(appContext: android.content.Context) {
        viewModelScope.launch {
            val identity = identityId.value ?: return@launch
            refreshPendingDeletes(identity)
            val ids = (ui.value.sources.filter { it.deletePending }.map { it.sourceId } +
                _pendingDeleteIds.value).toSet()
            if (ids.isEmpty()) {
                _deleteState.value = null
                return@launch
            }
            ids.forEach { id ->
                runCatching { container.packageRepository.beginDelete(id) }
                SourceDeleteWorker.enqueue(appContext, id)
            }
        }
    }

    fun clearDeleteState() {
        _deleteState.value = null
    }

    /**
     * Picker callback (design increment §1). The Room row is created here, and the persistable URI
     * grant is taken here, because the picker's grant flags are only in force while its result is
     * being handled. Nothing is uploaded on the caller's thread — the foreground service does that.
     */
    fun enqueueUpload(androidContext: android.content.Context, uris: List<android.net.Uri>, text: String?) {
        val identity = identityId.value ?: return
        val pkg = packageId.value ?: return
        if (uris.isEmpty() && text.isNullOrBlank()) return
        _startError.value = null
        _uploadNotice.value = null
        val appContext = androidContext.applicationContext

        val described = uris.map { uri -> uri to SourceAccess.describe(appContext, uri) }
        val grants = uris.associateWith { SourceAccess.takePersistableGrant(appContext, it) }

        viewModelScope.launch(Dispatchers.IO) {
            val notices = mutableListOf<String>()
            val sourceIds = mutableListOf<String>()

            described.forEach { (uri, picked) ->
                val granted = grants[uri] == true
                val row = runCatching {
                    container.packageRepository.createSource(
                        identityId = identity,
                        packageId = pkg,
                        displayName = picked.displayName,
                        mimeType = picked.mimeType,
                        kind = "FILE",
                        sizeBytes = picked.sizeBytes,
                        localUri = uri.toString(),
                        localAccessMode = if (granted) LocalAccessMode.PERSISTED_URI else LocalAccessMode.NONE,
                    )
                }.getOrElse {
                    notices += "「${picked.displayName}」加入失败，请重试"
                    return@forEach
                }
                if (granted) {
                    sourceIds += row.sourceId
                    return@forEach
                }
                // The Photo Picker hands out no persistable grant: keep a private copy instead so a
                // retry hours later still has bytes to read.
                val staged = runCatching { SourceAccess.stageCopy(appContext, uri, row.sourceId) }
                staged.onSuccess { bytes ->
                    container.packageRepository.bindLocalFile(
                        sourceId = row.sourceId,
                        localUri = uri.toString(),
                        localAccessMode = LocalAccessMode.APP_COPY,
                        sizeBytes = bytes,
                    )
                    sourceIds += row.sourceId
                }.onFailure { t ->
                    val failure = SourceFailureClassifier.classify(t)
                    container.packageRepository.markLocalOnly(row.sourceId, failure.code, failure.message)
                    notices += "「${picked.displayName}」${failure.message}"
                }
            }

            if (!text.isNullOrBlank()) {
                val trimmed = text.take(SourceAccess.MAX_TEXT_CHARS)
                val displayName = "pasted-text-${Ids.src().takeLast(6)}.txt"
                val row = runCatching {
                    container.packageRepository.createSource(
                        identityId = identity,
                        packageId = pkg,
                        displayName = displayName,
                        mimeType = "text/plain",
                        kind = "TEXT",
                        sizeBytes = trimmed.toByteArray(Charsets.UTF_8).size.toLong(),
                        localUri = null,
                        localAccessMode = LocalAccessMode.NONE,
                    )
                }.getOrNull()
                if (row == null) {
                    notices += "粘贴文本加入失败，请重试"
                } else {
                    val staged = runCatching { SourceAccess.stageText(appContext, row.sourceId, trimmed) }
                    staged.onSuccess {
                        container.packageRepository.bindLocalFile(
                            sourceId = row.sourceId,
                            localUri = null,
                            localAccessMode = LocalAccessMode.APP_COPY,
                            sizeBytes = null,
                        )
                        sourceIds += row.sourceId
                    }.onFailure { t ->
                        val failure = SourceFailureClassifier.classify(t)
                        container.packageRepository.markLocalOnly(row.sourceId, failure.code, failure.message)
                        notices += "粘贴文本${failure.message}"
                    }
                }
            }

            if (sourceIds.isNotEmpty()) {
                UploadForegroundService.start(appContext, identity, pkg, sourceIds)
            }
            _uploadNotice.value = notices.firstOrNull()
        }
    }

    /** "重试上传": the same row, the same sourceId — never a second source or a second object. */
    fun retryUpload(androidContext: android.content.Context, sourceId: String) {
        val identity = identityId.value ?: return
        val pkg = packageId.value ?: return
        val appContext = androidContext.applicationContext
        _uploadNotice.value = null
        viewModelScope.launch(Dispatchers.IO) {
            // An interrupted UPLOADING row is not requeued by SQL: releasing it needs the source
            // execution lock, and only the recovery coordinator may take one (§3.2 / C6 — the UI must
            // never probe a lock). A FAILED row falls straight through to PENDING below, and the same
            // pass covers whatever else is due. If the lock is unavailable the row keeps
            // 「上传中断，请重试」 rather than starting a second PUT (§5.2).
            container.sourceRecovery.request(SourceRecoveryTrigger.OUTCOME)
            container.packageRepository.requeueForRetry(sourceId)
            UploadForegroundService.start(appContext, identity, pkg, listOf(sourceId))
            SourceUploadWorker.enqueue(appContext, sourceId)
        }
    }

    /** "重新选择文件": rebinds an existing row to a new handle, then uploads it again. */
    fun reselectSource(androidContext: android.content.Context, sourceId: String, uri: android.net.Uri) {
        val identity = identityId.value ?: return
        val pkg = packageId.value ?: return
        val appContext = androidContext.applicationContext
        _uploadNotice.value = null
        val picked = SourceAccess.describe(appContext, uri)
        val granted = SourceAccess.takePersistableGrant(appContext, uri)
        viewModelScope.launch(Dispatchers.IO) {
            val existing = container.packageRepository.findSource(sourceId)
            if (existing == null) {
                _uploadNotice.value = "该资料已不存在"
                return@launch
            }
            if (existing.uploadState == UploadState.UPLOADED.name) {
                _uploadNotice.value = "该资料已上传，无需重新选择"
                return@launch
            }
            var mode = if (granted) LocalAccessMode.PERSISTED_URI else LocalAccessMode.NONE
            var size: Long? = picked.sizeBytes
            if (!granted) {
                val staged = runCatching { SourceAccess.stageCopy(appContext, uri, sourceId) }
                staged.onSuccess { bytes ->
                    mode = LocalAccessMode.APP_COPY
                    size = bytes
                }.onFailure { SourceAccess.deleteStaging(appContext, sourceId) }
            }
            val bound = container.packageRepository.bindLocalFile(sourceId, uri.toString(), mode, size)
            if (!bound) {
                _uploadNotice.value = "重新选择失败，请稍后再试"
                return@launch
            }
            UploadForegroundService.start(appContext, identity, pkg, listOf(sourceId))
        }
    }

    fun cancelUpload(androidContext: android.content.Context) {
        val pkg = packageId.value ?: return
        UploadForegroundService.cancel(androidContext.applicationContext, pkg)
    }

    fun clearUploadNotice() {
        _uploadNotice.value = null
    }

    private val _uploadNotice = MutableStateFlow<String?>(null)
    val uploadNotice: StateFlow<String?> = _uploadNotice.asStateFlow()
}

/** Outcome of one FR-10 source deletion, shown on the package detail screen. */
data class SourceDeleteUi(
    val sourceId: String,
    val busy: Boolean = false,
    val outcome: QmindDeleter.Outcome? = null,
    val message: String? = null,
) {
    val summary: String? get() = when (outcome) {
        QmindDeleter.Outcome.DELETED -> "已从知识库与云端删除"
        QmindDeleter.Outcome.DELETE_PENDING ->
            "知识库删除未确认，内容已隐藏，可稍后重试" + (message?.let { "（$it）" } ?: "")
        QmindDeleter.Outcome.NOT_INGESTED -> "该资料未进入知识库，已删除本地记录"
        null -> message
    }
}

data class PackageDetailUi(
    val pkg: LearningPackageEntity? = null,
    val sources: List<SourceAssetEntity> = emptyList(),
    val run: TaskRunEntity? = null,
    val publishedKinds: Set<ResultKind> = emptySet(),
) {
    val uploadedCount: Int get() = sources.count { it.uploadState == UploadState.UPLOADED.name }

    /**
     * Uploaded sources the Agent can actually parse. A legacy `.bin` restored from Drive counts as
     * uploaded but is `UNKNOWN`, and generating from it alone can only fail at PARSE — so the button
     * stays disabled and says why instead of spending credits on a doomed run (ZLQ-91 P1-4).
     */
    val generatableCount: Int get() = sources.count {
        it.uploadState == UploadState.UPLOADED.name && CanonicalType.of(it.canonicalType).generatable
    }

    val canGenerate: Boolean get() = generatableCount > 0 && run?.let { it.state in RUNNING_STATES } != true

    /** True when something is on Drive but nothing in it is parseable. */
    val blockedByFormat: Boolean get() = uploadedCount > 0 && generatableCount == 0
    val isRunning: Boolean get() = run != null && run.state in RUNNING_STATES

    /**
     * Whether a published result set exists, deliberately independent of the latest run's state:
     * regenerating or cancelling must not make the previous version unreachable (ZLQ-80).
     */
    val hasResults: Boolean get() = publishedKinds.isNotEmpty()

    /**
     * A cancel that Room recorded but whose package row is still `GENERATING` (ZLQ-114 §5.6).
     *
     * With the atomic cancel transaction this is now only reachable for a row written before the fix,
     * or one a cold-start pass has not converged yet — it is a fallback, not a state the app aims for.
     * It changes no button: `CANCELED` is not in [RUNNING_STATES], so [isRunning] is already false and
     * [canGenerate] already offers 重新生成. The hint exists so the student is told the package status
     * they can see is stale instead of being left to guess. The 重新生成 entry it points at stays behind
     * [canGenerate]'s format gate — the fallback explains a stale status, it does not widen what may be
     * generated, so a package with nothing parseable in it is still not invited to burn credits.
     */
    val isCanceledGeneratingMismatch: Boolean
        get() = pkg?.status == PackageStatus.GENERATING.name &&
            run?.state == TaskState.CANCELED.name

    companion object {
        val RUNNING_STATES = setOf(
            TaskState.QUEUED.name,
            TaskState.RUNNING.name,
            TaskState.RETRY_WAIT.name,
            TaskState.CANCEL_REQUESTED.name,
        )
    }
}
