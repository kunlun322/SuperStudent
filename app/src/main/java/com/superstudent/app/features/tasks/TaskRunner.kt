package com.superstudent.app.features.tasks

import android.util.Log
import com.superstudent.app.auth.CredentialGate
import com.superstudent.app.auth.REAUTHENTICATION_MESSAGE
import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.LearningPackageEntity
import com.superstudent.core.database.Prefs
import com.superstudent.core.database.SourceAssetEntity
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.drive.DrivePath
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.model.CanonicalType
import com.superstudent.core.model.ContentBlock
import com.superstudent.core.model.CreateSessionRequest
import com.superstudent.core.model.Credits
import com.superstudent.core.model.FailureClassifier
import com.superstudent.core.model.FailureReport
import com.superstudent.core.model.LearningGoal
import com.superstudent.core.model.PackageStatus
import com.superstudent.core.model.ProfileJson
import com.superstudent.core.model.QmindSourceRefJson
import com.superstudent.core.model.ResultKind
import com.superstudent.core.model.SendEventsRequest
import com.superstudent.core.model.SessionDto
import com.superstudent.core.model.SessionEventDto
import com.superstudent.core.model.SessionUsageSnapshot
import com.superstudent.core.model.TaskStage
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.UploadState
import com.superstudent.core.model.UserMessageEvent
import com.superstudent.core.network.NetworkFactory
import com.superstudent.core.network.PatProvider
import com.superstudent.core.network.QcaApi
import com.superstudent.core.network.QcaErrorKind
import com.superstudent.core.network.QcaException
import com.superstudent.core.network.RemoteCancelConverger
import com.superstudent.core.network.RemoteCancelOutcome
import com.superstudent.core.network.SseSignal
import com.superstudent.core.network.SseWatcher
import com.superstudent.core.network.qcaCall
import com.superstudent.core.repository.CancelCompensator
import com.superstudent.core.repository.CompensationOutcome
import com.superstudent.core.repository.ManifestWriter
import com.superstudent.core.repository.PackageRepository
import com.superstudent.core.repository.ProfileRepository
import com.superstudent.core.repository.ResultsRepository
import com.superstudent.core.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Collections
import kotlin.random.Random

/**
 * What one START_NEW request produced. Only [Rejected.message] / [Failed.message] may reach the UI;
 * both are fixed templates, never raw exception or API text.
 */
sealed interface StartOutcome {
    /** The attempt exists, carries a sessionId, and the task message was delivered. */
    data class Started(val run: TaskRunEntity) : StartOutcome

    /** A start that produced no run to observe. [message] is the only part that may reach the UI. */
    sealed interface NotStarted : StartOutcome {
        val message: String
    }

    /** Rejected before any attempt row or cloud call: a precondition, or the D-2 admission guard. */
    data class Rejected(override val message: String) : NotStarted

    /** The chain ran and failed; the attempt row is already terminal, so recovery won't pick it up. */
    data class Failed(override val message: String) : NotStarted
}

/**
 * Drives the task state machine of design §4: submit → observe (SSE first, polling fallback)
 * → strict artifact validation → publish → tmp cleanup.
 *
 * Every state change is written to Room first, so the UI observes Room and a restarted
 * process can resume observing an in-flight run without resubmitting it.
 */
class TaskRunner(
    private val api: QcaApi,
    private val drive: DriveRepository,
    private val sseWatcher: SseWatcher,
    private val patProvider: PatProvider,
    /**
     * Read before any `getSession`/`createSession`/`sendEvents`/SSE call (§4.3). Injected rather
     * than derived from [patProvider] because the answer has to be published as an
     * `AuthSessionState` transition, not just returned.
     */
    private val credentialGate: CredentialGate,
    /**
     * Called when the server rejects the stored credential itself (HTTP 401), so the session state can
     * be published as `ReauthenticationRequired` and the invalid material dropped (§4.1). Injected
     * because this class holds no `AuthSession`: it converges the run, the session state is the
     * caller's.
     */
    private val onCredentialRejected: suspend () -> Unit,
    private val taskRepository: TaskRepository,
    private val packageRepository: PackageRepository,
    /** Generation start/finish must publish under the same lock as uploads (ZLQ-110 §2.3). */
    private val manifestWriter: ManifestWriter,
    private val resultsRepository: ResultsRepository,
    private val profileRepository: ProfileRepository,
    private val prefs: Prefs,
    private val templateId: String,
    private val baseUrl: String = "https://api.qoder.com/",
    /** GET-before-POST + POST-after-confirm convergence for one Session (ZLQ-114 §5.3). */
    private val remoteCancel: RemoteCancelConverger = RemoteCancelConverger(api),
    /** The same compensation the Worker runs, so a foreground cancel stops the billing at once. */
    private val compensator: CancelCompensator,
    /**
     * Hands an unfinished compensation to WorkManager. Injected because this class holds no Context,
     * and because a cancel that could not confirm the remote must never be reported as finished.
     */
    private val enqueueCancelCompensation: (taskId: String, attempt: Int) -> Unit,
) {

    companion object {
        private const val TAG = "SsTaskRunner"
        private val POLL_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        private const val BACKGROUND_POLL_MS = 30_000L
        private const val SSE_WINDOW_MS = 60_000L
        private const val NO_EVENT_CHECK_MS = 45_000L
        private const val UNKNOWN_AFTER_MS = 30L * 60 * 1000
        private const val BACKGROUND_AFTER_MS = 2L * 60 * 1000

        /** Bound on the terminal failure-report scan: 5 pages of 100 events. */
        private const val FAILURE_SCAN_PAGES = 5

        /**
         * The projection a cancel leaves behind. These are display fields only — `error_code` must
         * never be read as the compensation marker, which is `cleanup_pending` (ZLQ-114 §5.1).
         */
        private const val CANCEL_ERROR_CODE = "canceled"
        private const val CANCEL_ERROR_MESSAGE = "用户取消"

        /** The five entries AC-01 requires. A run that does not produce all of them is not a success. */
        val ALL_RESULT_KINDS = listOf(
            ResultKind.PLAN,
            ResultKind.CARDS,
            ResultKind.MINDMAP,
            ResultKind.DECK,
            ResultKind.EXERCISES,
        )

        fun stageProgress(stage: TaskStage?): Int = when (stage) {
            null -> 0
            TaskStage.UPLOAD -> 5
            TaskStage.PARSE -> 20
            TaskStage.QMIND_INDEX -> 30
            TaskStage.GENERATE_PLAN -> 45
            TaskStage.GENERATE_CARDS -> 70
            TaskStage.GENERATE_MINDMAP -> 75
            TaskStage.GENERATE_DECK -> 78
            TaskStage.GENERATE_EXERCISES -> 80
            TaskStage.VALIDATE -> 88
            TaskStage.PUBLISH -> 95
        }

        val TERMINAL_STATES = setOf(
            TaskState.SUCCEEDED,
            TaskState.FAILED_RETRYABLE,
            TaskState.FAILED_PERMANENT,
            TaskState.CANCELED,
            TaskState.AUTH_EXPIRED,
            TaskState.ACCESS_DENIED,
            TaskState.IDENTITY_INVALID,
        )

        /**
         * Whether the 45-second no-event probe should conclude on this session status. `idle` counts
         * as over, not just `terminated`: a session can sit `idle` long after its turn ended — one was
         * still `idle` 96 minutes past completion — so waiting only for `terminated` never concluded
         * such a run and its usage was never written (ZLQ-84).
         *
         * Delegates to the network-layer predicate so cancel confirmation cannot disagree with this
         * probe about what "over" means (ZLQ-114 §5.3).
         */
        internal fun sessionConcluded(status: String?): Boolean =
            com.superstudent.core.network.sessionConcluded(status)

        /**
         * Whether reaching [state] freezes the two usage seconds counters (ZLQ-89 §4). Only a terminal
         * state does. `UNKNOWN` is still being observed by the recovery worker, and `RUNNING` /
         * `RETRY_WAIT` / `CANCEL_REQUESTED` are mid-run — in all of them the cloud's `duration_seconds`
         * is still growing, so recording it would store a measurement that is not final.
         */
        internal fun isUsageSnapshotPoint(state: TaskState): Boolean = state in TERMINAL_STATES

        /**
         * Hands the Agent this identity's qmind binding (design §7.2). Only the Notebook ID is
         * authoritative; the Agent derives the Notebook name from profile.json itself, so no username
         * ever travels in the prompt. A null ID means "bind on first ingest".
         *
         * `ingestedSha256` carries the sha256 of the LAST SUCCESSFUL ingest from profile.json so the
         * Agent can apply the "skip only when qmindSourceId != null && sha256 == ingestedSha256"
         * rule; without it a changed source could be wrongly skipped (ZLQ-90 scope B).
         *
         * `drivePath` travels as an opaque locator and `displayName` as the only human-readable name
         * (ZLQ-91 P0-4): without the pair, a hash-derived ASCII path would end up in citations and the
         * exported deck notes, which is exactly the regression AC-02 guards against.
         */
        internal fun qmindBinding(profile: ProfileJson?, sources: List<SourceAssetEntity>): QmindTaskBinding {
            val ingested = profile?.qmindSources?.associateBy { it.sourceId } ?: emptyMap()
            return QmindTaskBinding(
                profilePath = DrivePath.profile(),
                notebookId = profile?.qmindNotebookId,
                sources = sources.map {
                    val prev = ingested[it.sourceId]
                    QmindSourceTaskRef(
                        sourceId = it.sourceId,
                        drivePath = it.drivePath.orEmpty(),
                        sha256 = it.sha256.orEmpty(),
                        displayName = it.displayName,
                        mimeType = it.mimeType ?: CanonicalType.of(it.canonicalType).mimeType,
                        canonicalType = CanonicalType.of(it.canonicalType).name,
                        ingestedSha256 = prev?.sourceSha256,
                        qmindSourceId = prev?.qmindSourceId,
                    )
                },
            )
        }

        /**
         * ZLQ-90 scope D-2 admission guard: `notebook create` is not idempotent, so while the
         * identity has no bound Notebook at most ONE generation task may enter the first-build
         * chain; any other concurrent task must be rejected (or stay QUEUED), never sent to the
         * cloud in parallel. Once a Notebook is bound, no admission limit applies.
         */
        internal fun admitFirstBuild(notebookBound: Boolean, sameIdentityActiveRuns: Int): Boolean =
            notebookBound || sameIdentityActiveRuns == 0

        /**
         * What the second concurrent first build is told. A fixed template: it names no task, no
         * package and no Drive path, because it goes straight to the UI.
         */
        internal const val ADMISSION_BUSY_MESSAGE = "另一个生成任务正在首次创建知识库，请等它完成后再开始"
    }

    private val startMutex = Mutex()

    /**
     * Runs a live submit critical section in THIS process owns. Recovery consults it to tell "no
     * owner" from "owner is mid-submit"; it is in-memory only, never business state, and its writes
     * outside the lock are why the set is synchronized.
     */
    private val liveSubmitKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private fun runKey(taskId: String, attempt: Int) = "$taskId#$attempt"

    /**
     * Active (non-terminal) runs belonging to this identity; runs of other identities don't count.
     * Resolved in Room rather than through the package repository so the guard stays decidable
     * offline — a Drive read here would let a network blip admit a second concurrent first build.
     */
    private suspend fun countSameIdentityActiveRuns(identityId: String): Int =
        taskRepository.countActiveByIdentity(identityId)

    /**
     * Owns the whole submit chain of one START_NEW request (ZLQ-103 §2).
     *
     * The caller is the foreground service, whose lifetime is not tied to a screen: navigating away
     * no longer cancels the chain between `createAttempt` and the sessionId write and leaves a
     * `QUEUED` + `session_id IS NULL` orphan behind. Everything up to and including that write runs
     * in one critical section shared with recovery; the long network stages (send, observe) run
     * outside it.
     *
     * [requestId] is the request's idempotency token and becomes the attempt's `run_id`, so a
     * redelivered intent resolves to the attempt it already created instead of making another one.
     */
    suspend fun submitNew(
        identityId: String,
        packageId: String,
        resumeFromStage: TaskStage?,
        requestId: String,
    ): StartOutcome {
        // §4.3/§4.4: the credential is read before the first Drive or QCA call. Without one there is
        // no Session to create, and starting a foreground service to fail inside OkHttp is what put
        // the process into its restart backoff.
        if (credentialGate.requireCredential() != null) {
            return StartOutcome.Rejected(REAUTHENTICATION_MESSAGE)
        }
        val pkg = runCatching { packageRepository.getPackage(identityId, packageId) }.getOrNull()
            ?: return StartOutcome.Failed("学习包不可用，请返回后重试")
        val uploaded = packageRepository.listSources(packageId)
            .filter { it.uploadState == UploadState.UPLOADED.name && !it.drivePath.isNullOrBlank() }
        // A source the Agent cannot parse (a legacy `.bin` restored from Drive, canonical type
        // UNKNOWN) is left out of the task instead of being handed over: including it would only
        // fail the run at PARSE, after credits were already spent. It stays in package.json.
        val sources = uploaded.filter { CanonicalType.of(it.canonicalType).generatable }
        if (sources.isEmpty()) return StartOutcome.Rejected("该学习包还没有可用于生成的资料，请检查资料格式")
        val profile = runCatching { profileRepository.read(identityId) }.getOrNull()

        val submitted = try {
            startMutex.withLock {
                // Recovery first: an orphan left behind by an earlier process is still counted by
                // the admission guard below, and would block this legitimate start forever.
                reconcileLocked()
                // ZLQ-90 scope D-2: check-and-create must be atomic, otherwise two concurrent
                // first-build tasks can both pass the check and create duplicate Notebooks
                // (`notebook create` is not idempotent). The loser is rejected before any attempt
                // row or cloud session exists.
                val notebookBound = !profile?.qmindNotebookId.isNullOrBlank()
                if (!admitFirstBuild(notebookBound, countSameIdentityActiveRuns(identityId))) {
                    return StartOutcome.Rejected(ADMISSION_BUSY_MESSAGE)
                }
                val created = taskRepository.createAttempt(packageId, resumeFromStage, requestId)
                val key = runKey(created.taskId, created.attempt)
                liveSubmitKeys += key
                try {
                    // ZLQ-113 / ZLQ-110 §2.3: the GENERATING publish goes through the one manifest
                    // writer, which re-reads the package and its sources inside the per-package lock,
                    // so a source committing while this task starts is no longer dropped by the
                    // snapshot taken above and there is no second SourceRefJson projection to drift.
                    manifestWriter.publishState(
                        identityId = identityId,
                        packageId = packageId,
                        status = PackageStatus.GENERATING,
                        latestTaskId = created.taskId,
                    )
                    val sessionId = qcaCall {
                        api.createSession(
                            "ss:create-session:${created.taskId}-${created.attempt}",
                            CreateSessionRequest(
                                identityId = identityId,
                                templateId = templateId,
                                title = "SS package $packageId",
                                metadata = mapOf(
                                    "app" to "superstudent-android",
                                    "schema_version" to "1",
                                    "package_id" to packageId,
                                    "task_id" to created.taskId,
                                    "attempt" to created.attempt.toString(),
                                ),
                            ),
                        )
                    }.id
                    // The point of no return: once this row carries a sessionId, recovery resumes it
                    // instead of converging it, and can never create a second Session for it.
                    val withSession = created.copy(sessionId = sessionId, startedAt = nowIso())
                    taskRepository.save(withSession)
                    withSession
                } catch (t: Throwable) {
                    // Direction 2: an attempt row exists but no sessionId does, on EVERY failure
                    // path — cancellation, a rejected createSession, a Drive publish failure, an
                    // unexpected exception. Without this write the row stays QUEUED forever and the
                    // admission guard above counts it on every later start.
                    liveSubmitKeys -= key
                    withContext(NonCancellable) { compensateStart(identityId, created, t) }
                    throw t
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return StartOutcome.Failed(startFailureMessage(t))
        }

        liveSubmitKeys -= runKey(submitted.taskId, submitted.attempt)
        runCatching { taskRepository.publishTaskJson(identityId, submitted) }
        return if (sendTaskMessage(identityId, submitted, pkg, sources, profile, resumeFromStage)) {
            StartOutcome.Started(submitted)
        } else {
            StartOutcome.Failed(startFailureMessage(null))
        }
    }

    /**
     * Hands the prompt to the Session under the attempt's idempotency key. True when the cloud has
     * the task (accepted now, or already running it); false when the run was just failed.
     */
    private suspend fun sendTaskMessage(
        identityId: String,
        run: TaskRunEntity,
        pkg: LearningPackageEntity,
        sources: List<SourceAssetEntity>,
        profile: ProfileJson?,
        resumeFromStage: TaskStage?,
    ): Boolean {
        val sessionId = run.sessionId ?: return false
        val prompt = PromptTemplate.build(
            taskPayload(pkg, sources, profile, run.taskId, run.attempt, resumeFromStage),
        )
        return try {
            qcaCall {
                api.sendEvents(
                    sessionId,
                    "ss:send-task:${run.taskId}-${run.attempt}",
                    SendEventsRequest(
                        events = listOf(UserMessageEvent(content = listOf(ContentBlock(type = "text", text = prompt))))
                    ),
                )
            }
            true
        } catch (e: QcaException) {
            // A turn already running means an earlier delivery of this same key got through, which
            // is the replay case, not a failure.
            if (e.kind == QcaErrorKind.TURN_ALREADY_RUNNING) return true
            failRun(identityId, run, e)
            false
        }
    }

    private suspend fun taskPayload(
        pkg: LearningPackageEntity,
        sources: List<SourceAssetEntity>,
        profile: ProfileJson?,
        taskId: String,
        attempt: Int,
        resumeFromStage: TaskStage?,
    ) = TaskPayload(
        packageId = pkg.packageId,
        taskId = taskId,
        attempt = attempt,
        goal = LearningGoal.valueOf(pkg.goal),
        chapterRange = pkg.chapterRange,
        sourcePaths = sources.map { it.toTaskRef() },
        outputPrefix = DrivePath.packageDir(pkg.packageId),
        resumeFromStage = resumeFromStage,
        qmind = qmindBinding(profile, sources),
        cardImages = CardImagePolicy(enabled = prefs.cardImagesEnabledNow()),
    )

    /**
     * Replays the submit of a run whose Session already exists (ZLQ-103 §3, `QUEUED` + sessionId:
     * the process died after the sessionId write but before sendEvents). The key is the one the
     * interrupted attempt used, so the cloud either accepts the replay or reports the turn already
     * running — it cannot start a second Session or charge for one.
     */
    suspend fun replaySubmit(identityId: String, taskId: String, attempt: Int): Boolean {
        // §4.3: a replay is a cloud `sendEvents`, so it is gated too. Returning false leaves the row
        // where it is for the observer's own pre-flight to converge, rather than half-replaying it.
        if (credentialGate.requireCredential() != null) return false
        val row = taskRepository.get(taskId, attempt) ?: return false
        val sessionId = row.sessionId ?: return false
        val pkg = runCatching { packageRepository.getPackage(identityId, row.packageId) }.getOrNull()
            ?: return false
        val sources = packageRepository.listSources(row.packageId)
            .filter { it.uploadState == UploadState.UPLOADED.name && !it.drivePath.isNullOrBlank() }
            .filter { CanonicalType.of(it.canonicalType).generatable }
        if (sources.isEmpty()) return false
        val profile = runCatching { profileRepository.read(identityId) }.getOrNull()
        val resumeFromStage = row.resumeFromStage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() }
        val prompt = PromptTemplate.build(
            taskPayload(pkg, sources, profile, taskId, attempt, resumeFromStage),
        )
        return try {
            qcaCall {
                api.sendEvents(
                    sessionId,
                    "ss:send-task:$taskId-$attempt",
                    SendEventsRequest(
                        events = listOf(UserMessageEvent(content = listOf(ContentBlock(type = "text", text = prompt))))
                    ),
                )
            }
            true
        } catch (e: QcaException) {
            if (e.kind == QcaErrorKind.TURN_ALREADY_RUNNING) return true
            failRun(identityId, row, e)
            false
        }
    }

    /**
     * Converges every sessionless active row that has no live owner, and returns the rows that still
     * need the network — those have a Session, so recovery resumes them and never rebuilds one.
     *
     * Local only (no Drive write, no QCA call), which is what lets it run at cold start and inside
     * every admission check without depending on connectivity.
     */
    suspend fun reconcileSessionlessActiveRuns(): List<ActiveRunRow> =
        startMutex.withLock { reconcileLocked() }

    /** Caller must hold [startMutex]. */
    private suspend fun reconcileLocked(): List<ActiveRunRow> {
        val rows = runCatching { taskRepository.listActiveWithOwner() }.getOrDefault(emptyList())
        val resumable = mutableListOf<ActiveRunRow>()
        for (row in rows) {
            val run = row.run
            val held = liveSubmitKeys.contains(runKey(run.taskId, run.attempt))
            when (val action = TaskRecovery.decide(run.state, run.sessionId != null, held)) {
                RecoveryAction.CONVERGE_CANCELED, RecoveryAction.CONVERGE_START_INTERRUPTED -> {
                    val moved = if (action == RecoveryAction.CONVERGE_CANCELED) {
                        taskRepository.convergeCanceled(run.taskId, run.attempt)
                    } else {
                        taskRepository.convergeInterrupted(
                            run.taskId,
                            run.attempt,
                            TaskRecovery.ERROR_CODE_START_INTERRUPTED,
                            TaskRecovery.ERROR_MESSAGE_START_INTERRUPTED,
                        )
                    }
                    if (moved > 0) {
                        Log.w(
                            TAG,
                            "reconcile: ${run.taskId}#${run.attempt} state=${run.state} " +
                                "session=${run.sessionId ?: "-"} -> ${if (action == RecoveryAction.CONVERGE_CANCELED) "CANCELED" else "FAILED_RETRYABLE"}",
                        )
                        resetPackageAfterConverge(row)
                    }
                }

                RecoveryAction.REPLAY_AND_OBSERVE, RecoveryAction.OBSERVE -> resumable += row
                RecoveryAction.WAIT_FOR_SUBMIT, RecoveryAction.NONE -> Unit
            }
        }
        return resumable
    }

    /**
     * Takes the package out of `GENERATING` only when the converged attempt is still the one the
     * package points at and nothing newer is running; otherwise the student would be offered
     * "generate" on a package that already has a later attempt in flight.
     */
    private suspend fun resetPackageAfterConverge(row: ActiveRunRow) {
        val run = row.run
        val latest = runCatching { taskRepository.latestAttempt(run.taskId) }.getOrNull()
        val others = runCatching {
            taskRepository.countOtherActiveForPackage(run.packageId, run.taskId, run.attempt)
        }.getOrDefault(0)
        val reset = TaskRecovery.resetsPackage(
            packageStatus = row.packageStatus,
            packageLatestTaskId = row.packageLatestTaskId,
            taskId = run.taskId,
            isLatestAttempt = latest?.attempt == run.attempt,
            otherActiveRuns = others,
        )
        if (!reset) return
        runCatching { taskRepository.packageBackToReady(run.packageId, run.taskId) }
    }

    /**
     * Terminal write for a submit chain that died with an attempt row but no sessionId. Runs under
     * `NonCancellable` so the very cancellation that triggered it cannot also cancel the repair.
     * A QCA rejection keeps its richer classification; anything else is a start interruption.
     */
    private suspend fun compensateStart(identityId: String, run: TaskRunEntity, cause: Throwable) {
        if (cause is QcaException) {
            runCatching { failRun(identityId, run, cause) }
            return
        }
        val moved = runCatching {
            taskRepository.convergeInterrupted(
                run.taskId,
                run.attempt,
                TaskRecovery.ERROR_CODE_START_INTERRUPTED,
                TaskRecovery.ERROR_MESSAGE_START_INTERRUPTED,
            )
        }.getOrDefault(0)
        if (moved == 0) return
        Log.w(TAG, "compensate: ${run.taskId}#${run.attempt} start interrupted (${cause.javaClass.simpleName})")
        runCatching {
            val latest = taskRepository.latestAttempt(run.taskId)
            val others = taskRepository.countOtherActiveForPackage(run.packageId, run.taskId, run.attempt)
            if (TaskRecovery.resetsPackage(PackageStatus.GENERATING.name, run.taskId, run.taskId, latest?.attempt == run.attempt, others)) {
                taskRepository.packageBackToReady(run.packageId, run.taskId)
            }
        }
    }

    /** User-facing text for a start that never produced a run; raw failure text stays in logcat. */
    private fun startFailureMessage(t: Throwable?): String =
        if (t is QcaException) {
            FailureClassifier.requestFailed(t.code ?: t.kind.name, stateFor(t), t.message, nowIso()).message
        } else {
            "云端生成失败，可重试"
        }

    private fun SourceAssetEntity.toTaskRef() = SourceTaskRef(
        sourceId = sourceId,
        drivePath = drivePath.orEmpty(),
        displayName = displayName,
        mimeType = mimeType ?: CanonicalType.of(canonicalType).mimeType,
        canonicalType = CanonicalType.of(canonicalType).name,
        sha256 = sha256.orEmpty(),
    )

    /** Records the binding the Agent reported back, so the next run ingests incrementally. */
    private suspend fun persistQmindReport(identityId: String, manifest: FinalManifest?) {
        val report = manifest?.qmind ?: return
        runCatching {
            profileRepository.applyIngestReport(
                identityId = identityId,
                notebookId = report.notebookId,
                ownerUserHash = report.ownerUserHash,
                ingested = report.sources.map {
                    QmindSourceRefJson(
                        sourceId = it.sourceId,
                        sourceSha256 = it.sha256.orEmpty(),
                        qmindSourceId = it.qmindSourceId,
                    )
                },
            )
        }
    }

    /** Observes an already-submitted run until it reaches a terminal state or UNKNOWN. */
    suspend fun observe(identityId: String, taskId: String, attempt: Int) {
        val initial = taskRepository.get(taskId, attempt) ?: return
        val sessionId = initial.sessionId
        if (sessionId == null) {
            // No Session was ever recorded, so there is nothing to observe and no observer that
            // could conclude this row. Recovery owns it: converge it (or leave it to the submit
            // critical section that is still holding it) instead of returning silently and letting
            // it stay active forever.
            reconcileSessionlessActiveRuns()
            return
        }
        if (runCatching { TaskState.valueOf(initial.state) }.getOrNull() in TERMINAL_STATES) return

        var row = initial
        var lastEventId = row.lastEventId
        var stage = row.stage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() }
        var lastEventAt = System.currentTimeMillis()
        val observeStartedAt = System.currentTimeMillis()
        var pollIndex = 0
        var sseBroken = false
        var cancelSeen = false
        var lastManifest: FinalManifest? = null

        /** The cancel tap writes CANCEL_REQUESTED to Room from the UI; latch it so we never lose it. */
        suspend fun refreshCancelFlag() {
            if (cancelSeen) return
            if (taskRepository.get(taskId, attempt)?.state == TaskState.CANCEL_REQUESTED.name) cancelSeen = true
        }

        /**
         * Computes the next value of [row] without writing it, so the terminal path can hand the
         * result to the repository's merge instead of saving a pre-merge row.
         */
        suspend fun stageRow(
            state: TaskState? = null,
            newStage: TaskStage? = null,
            eventId: String? = null,
            progress: Int? = null,
            errorCode: String? = null,
            errorMessage: String? = null,
            clearError: Boolean = false,
            creditsDelta: Double? = null,
            creditsTotal: Double? = null,
            finished: Boolean = false,
        ): TaskRunEntity {
            val now = Instant.now().toString()
            if (!finished) refreshCancelFlag()
            row = row.copy(
                state = when {
                    cancelSeen && !finished -> TaskState.CANCEL_REQUESTED.name
                    state != null -> state.name
                    else -> row.state
                },
                stage = (newStage?.name) ?: row.stage,
                progress = progress ?: row.progress,
                lastEventId = eventId ?: row.lastEventId,
                errorCode = if (clearError) null else (errorCode ?: row.errorCode),
                errorMessage = if (clearError) null else (errorMessage ?: row.errorMessage),
                // FR-13: SSE carries per-event model usage, getSession carries the authoritative
                // task total. Overwriting with a delta made the figure fall mid-run and left the
                // persisted task.json under-reporting whenever the final total was unavailable.
                credits = when {
                    creditsTotal != null -> Credits.round(creditsTotal)
                    creditsDelta != null -> Credits.round(row.credits + creditsDelta)
                    else -> row.credits
                },
                finishedAt = if (finished) now else row.finishedAt,
                startedAt = row.startedAt ?: now,
            )
            if (newStage != null) stage = newStage
            return row
        }

        suspend fun persist(
            state: TaskState? = null,
            newStage: TaskStage? = null,
            eventId: String? = null,
            progress: Int? = null,
            errorCode: String? = null,
            errorMessage: String? = null,
            clearError: Boolean = false,
            creditsDelta: Double? = null,
            creditsTotal: Double? = null,
            finished: Boolean = false,
        ) {
            stageRow(
                state = state,
                newStage = newStage,
                eventId = eventId,
                progress = progress,
                errorCode = errorCode,
                errorMessage = errorMessage,
                clearError = clearError,
                creditsDelta = creditsDelta,
                creditsTotal = creditsTotal,
                finished = finished,
            )
            taskRepository.save(row)
        }

        /**
         * The single terminal funnel for this observer (ZLQ-89 §4). [sessionSnapshot] is the usage
         * read belonging to this same conclusion — never re-queried here, so credits and both seconds
         * counters always describe one moment of one session.
         *
         * Everything after the merge uses the canonical row the repository read back: `row` predates
         * the merge, and publishing or finalizing from it would write task.json without the snapshot
         * just frozen and then let `finalize` overwrite the merged row in Room.
         */
        suspend fun finishTerminal(
            state: TaskState,
            failure: FailureReport?,
            sessionSnapshot: SessionUsageSnapshot = SessionUsageSnapshot.EMPTY,
        ) {
            stageRow(
                state = state,
                errorCode = failure?.code,
                errorMessage = failure?.message,
                // A RETRY_WAIT backoff writes its own message; leaving it behind made a later
                // success still render that text under `run_error`.
                clearError = failure == null,
                progress = if (state == TaskState.SUCCEEDED) 100 else row.progress,
                finished = true,
            )
            row = taskRepository.concludeTerminal(row, sessionSnapshot)
                // A vanished attempt still has to reach its terminal state, as it did before the
                // merge existed; the snapshot simply has nothing to merge into.
                ?: row.also { taskRepository.save(it) }
            if (failure != null) logFailure(failure)
            taskRepository.publishTaskJson(identityId, row, failure?.toTaskError(row.attempt))
            finalize(identityId, row, state)
        }

        suspend fun cancelRequested(): Boolean {
            refreshCancelFlag()
            return cancelSeen
        }

        suspend fun applyCancel() {
            val outcome = ensureRemoteCanceled(sessionId)
            val confirmed = outcome == RemoteCancelOutcome.CONVERGED
            // One best-effort read, taken only once the remote is *known* to be over. Reading before
            // the cancel settled would snapshot a session still winding down; reading after an
            // unconfirmed cancel would freeze `duration_seconds` on a turn that is still running and
            // still billing (ZLQ-114 §5.3), so that case keeps both counters NULL and leaves the
            // measurement to the compensation pass. A failed read must not hold up CANCELED.
            val snapshot = if (confirmed) {
                SessionUsageSnapshot.from(
                    runCatching { qcaCall { api.getSession(sessionId) } }.getOrNull()
                )
            } else {
                SessionUsageSnapshot.EMPTY
            }
            concludeCanceled(
                identityId = identityId,
                intent = stageRow(
                    state = TaskState.CANCELED,
                    errorCode = CANCEL_ERROR_CODE,
                    errorMessage = CANCEL_ERROR_MESSAGE,
                    finished = true,
                ),
                snapshot = snapshot,
                remoteConfirmed = confirmed,
            )
        }

        /**
         * Pages forward from [from] for a failure report. A null cursor re-reads the session from its
         * first event, which covers a stream that had already moved past the report. Each attempt owns
         * its own session, so this can never pick up an earlier attempt's failure.
         */
        suspend fun scanForFailure(from: String?): FailureReport? {
            var cursor = from
            var pages = 0
            while (pages < FAILURE_SCAN_PAGES) {
                pages++
                val page = try {
                    qcaCall { api.listEvents(sessionId, cursor, 100) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "observe: failure-report scan aborted", e)
                    return null
                }
                FailureClassifier.fromEvents(page.data, nowIso())?.let { return it }
                if (!page.hasMore || page.data.isEmpty()) return null
                cursor = page.data.last().id.takeIf { it.isNotBlank() } ?: return null
            }
            return null
        }

        /**
         * Re-reads the session's events for the cloud's own failure report. The live stream can miss
         * the Agent's final message — an SSE drop, or the observer converging on `terminated` via
         * getSession — and without this the only evidence left is the Drive 404 downstream of the
         * real failure (ZLQ-79). An unreadable page degrades to artifact validation, never to a guess.
         */
        suspend fun cloudFailureReport(): FailureReport? =
            lastEventId?.let { scanForFailure(it) } ?: scanForFailure(null)

        /**
         * Single gate for every "the turn is over" conclusion. A pending cancel wins over both the
         * error and the success path: canceling the remote session makes the in-flight model request
         * end with an error, which must not be reported to the user as a failure.
         *
         * The cloud's failure report is authoritative: it is consulted before any artifact is read, so
         * a run that failed in QMIND_INDEX is reported as that failure and not as a missing result
         * object it never had the chance to publish. A model-request error is weaker — a run can
         * survive one — so it only becomes the reported reason once validation has also failed.
         *
         * [prefetched] is the Session the caller already read, and is passed straight through instead
         * of being queried again: a second read would land on a later, larger `duration_seconds` and
         * the same conclusion would disagree with itself. One read feeds credits, both seconds
         * counters and every terminal branch below, success and failure alike.
         */
        suspend fun conclude(error: FailureReport?, prefetched: SessionDto? = null) {
            if (cancelRequested()) {
                applyCancel()
                return
            }
            persistQmindReport(identityId, lastManifest)
            val snapshot = SessionUsageSnapshot.from(
                prefetched ?: runCatching { qcaCall { api.getSession(sessionId) } }.getOrNull()
            )
            val report = error
                ?: if (lastManifest?.succeeded == true) null else cloudFailureReport()
            if (report != null && report.authoritative) {
                finishTerminal(report.state, report, snapshot)
                return
            }
            snapshot.totalCredits?.takeIf { it > 0 }?.let { persist(creditsTotal = it) }
            // FR-10: reject the whole result set if any citation came from a source this package
            // does not own — logical isolation has no platform-side backstop.
            val ownedSourceIds = packageRepository.listSources(row.packageId).map { it.sourceId }.toSet()
            val validated = runCatching {
                resultsRepository.fetch(identityId, row.packageId, ownedSourceIds)
            }
            val cause = validated.exceptionOrNull()
            if (cause is CancellationException) throw cause
            if (validated.isSuccess) {
                finishTerminal(TaskState.SUCCEEDED, null, snapshot)
            } else {
                // The cause is typically a Drive path; it belongs in the log and task.json, never in
                // the message the student reads.
                finishTerminal(
                    TaskState.FAILED_RETRYABLE,
                    report ?: FailureClassifier.artifactValidation(cause?.message, nowIso(), lastEventId),
                    snapshot,
                )
            }
        }

        // §4.3: read before the loop's first `getSession`, not after it. The baseline asked the
        // cloud whether the Session still existed and only then noticed there was no credential to
        // ask with, so the failure surfaced as an uncaught exception from inside OkHttp instead of
        // as this terminal write. Local sessionless recovery above stays ungated on purpose (§2.4).
        credentialGate.requireCredential()?.let { reason ->
            finishTerminal(
                TaskState.AUTH_EXPIRED,
                FailureClassifier.requestFailed(
                    reason.name, TaskState.AUTH_EXPIRED, "credential=${reason.name}", nowIso(),
                ),
            )
            return
        }

        while (true) {
            val fresh = taskRepository.get(taskId, attempt)
            if (fresh != null) {
                val freshState = runCatching { TaskState.valueOf(fresh.state) }.getOrNull()
                if (freshState == TaskState.CANCEL_REQUESTED) cancelSeen = true
                if (cancelSeen) {
                    applyCancel()
                    return
                }
                if (freshState in TERMINAL_STATES) return
                if (freshState == TaskState.RUNNING && row.state != TaskState.RUNNING.name) {
                    row = fresh
                }
            }

            if (System.currentTimeMillis() - observeStartedAt > UNKNOWN_AFTER_MS) {
                // Design §1.4: never cancel the remote task; surface it for the user instead.
                persist(state = TaskState.UNKNOWN, errorMessage = "超过 30 分钟仍未结束，请关注云端任务")
                taskRepository.publishTaskJson(identityId, row)
                return
            }

            if (System.currentTimeMillis() - lastEventAt > NO_EVENT_CHECK_MS) {
                val session = runCatching { qcaCall { api.getSession(sessionId) } }.getOrNull()
                if (session != null) {
                    val snapshot = SessionUsageSnapshot.from(session)
                    // Credits only. Nothing is frozen here: the run is not known to be over, and
                    // `duration_seconds` is still growing, so recording it now would store a
                    // mid-run value as if it were the terminal measurement.
                    snapshot.totalCredits?.takeIf { it > 0 }?.let { persist(creditsTotal = it) }
                    // `idle` counts as over, not just `terminated`; see [sessionConcluded]. The
                    // session already in hand is passed on — conclude() must not query it again,
                    // or one conclusion would be built on two different readings.
                    if (sessionConcluded(session.status)) {
                        conclude(null, session)
                        return
                    }
                }
                lastEventAt = System.currentTimeMillis()
            }

            val pat = patProvider.currentPat()
            if (pat == null) {
                finishTerminal(
                    TaskState.AUTH_EXPIRED,
                    FailureClassifier.requestFailed("no_pat", TaskState.AUTH_EXPIRED, "本地无可用 PAT", nowIso()),
                )
                return
            }

            if (!sseBroken) {
                val request = NetworkFactory.sseRequest(baseUrl, sessionId, pat, lastEventId)
                var sawTurnEnd = false
                var sawError: FailureReport? = null
                var streamBroken = false

                val timedOut = runCatching {
                    withTimeoutOrNull(SSE_WINDOW_MS) {
                        sseWatcher.watch(request).first { signal ->
                            when (signal) {
                                is SseSignal.Event -> {
                                    val effect = handleEvent(signal.dto)
                                    lastEventAt = System.currentTimeMillis()
                                    pollIndex = 0
                                    if (effect.manifest != null) lastManifest = effect.manifest
                                    if (effect.eventId != null) lastEventId = effect.eventId
                                    if (effect.stage != null) {
                                        persist(
                                            state = TaskState.RUNNING,
                                            newStage = effect.stage,
                                            eventId = effect.eventId,
                                            progress = stageProgress(effect.stage),
                                            creditsDelta = effect.credits,
                                        )
                                    } else if (effect.eventId != null || effect.credits != null) {
                                        persist(
                                            state = if (row.state == TaskState.QUEUED.name) TaskState.RUNNING else null,
                                            eventId = effect.eventId,
                                            creditsDelta = effect.credits,
                                        )
                                    }
                                    if (effect.error != null) sawError = effect.error
                                    if (effect.turnEnded) sawTurnEnd = true
                                    sawTurnEnd || sawError != null || cancelSeen
                                }

                                is SseSignal.Failed -> {
                                    streamBroken = true
                                    true
                                }

                                SseSignal.Closed -> {
                                    streamBroken = true
                                    true
                                }
                            }
                        }
                    } == null
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    streamBroken = true
                    false
                }

                if (cancelRequested()) {
                    applyCancel()
                    return
                }
                val concludedError = sawError
                if (sawTurnEnd || concludedError != null) {
                    conclude(concludedError)
                    return
                }
                if (streamBroken) {
                    sseBroken = true
                } else if (timedOut) {
                    // Stream still healthy after the window: reconnect with Last-Event-ID.
                    continue
                }
            }

            if (sseBroken) {
                val background = System.currentTimeMillis() - observeStartedAt > BACKGROUND_AFTER_MS
                val base = if (background) {
                    BACKGROUND_POLL_MS
                } else {
                    POLL_DELAYS_MS[pollIndex.coerceAtMost(POLL_DELAYS_MS.size - 1)]
                }
                delay(jitter(base))
                pollIndex++

                val page = runCatching { qcaCall { api.listEvents(sessionId, lastEventId, 100) } }
                val events = page.getOrElse { e ->
                    if (e is CancellationException) throw e
                    if (e is QcaException && e.kind == QcaErrorKind.AUTH_EXPIRED) {
                        reportCredentialRejected()
                        finishTerminal(
                            TaskState.AUTH_EXPIRED,
                            FailureClassifier.requestFailed(
                                e.code ?: e.kind.name,
                                TaskState.AUTH_EXPIRED,
                                e.message,
                                nowIso(),
                            ),
                        )
                        return
                    }
                    Log.w(TAG, "observe: listEvents failed, backing off", e)
                    persist(
                        state = TaskState.RETRY_WAIT,
                        errorMessage = FailureClassifier
                            .requestFailed(null, TaskState.RETRY_WAIT, e.message, nowIso())
                            .message,
                    )
                    null
                } ?: continue

                if (events.data.isEmpty()) {
                    if (pollIndex > 8) {
                        sseBroken = false
                        pollIndex = 0
                    }
                    continue
                }
                lastEventAt = System.currentTimeMillis()
                for (event in events.data) {
                    val effect = handleEvent(event)
                    if (effect.manifest != null) lastManifest = effect.manifest
                    if (effect.eventId != null) lastEventId = effect.eventId
                    if (effect.stage != null) {
                        persist(
                            state = TaskState.RUNNING,
                            newStage = effect.stage,
                            eventId = effect.eventId,
                            progress = stageProgress(effect.stage),
                            creditsDelta = effect.credits,
                        )
                    } else if (effect.eventId != null || effect.credits != null) {
                        persist(
                            state = if (row.state == TaskState.QUEUED.name) TaskState.RUNNING else null,
                            eventId = effect.eventId,
                            creditsDelta = effect.credits,
                        )
                    }
                    if (effect.error != null || effect.turnEnded) {
                        conclude(effect.error)
                        return
                    }
                }
                pollIndex = 0
                sseBroken = false
            }
        }
    }

    private data class EventEffect(
        val eventId: String?,
        val stage: TaskStage?,
        val turnEnded: Boolean,
        val error: FailureReport?,
        val credits: Double?,
        val manifest: FinalManifest? = null,
    )

    private fun handleEvent(event: SessionEventDto): EventEffect {
        val id = event.id.takeIf { it.isNotBlank() }
        var stage: TaskStage? = null
        var turnEnded = false
        var manifest: FinalManifest? = null
        val credits = event.modelUsage?.credits?.takeIf { it > 0 }

        when (event.type) {
            "session.status_idle", "session.thread_status_idle", "session.terminated" -> {
                turnEnded = true
            }

            "agent.message" -> {
                val text = event.textContent()
                PromptTemplate.parseStageMarker(text)?.let { stage = it }
                PromptTemplate.parseFinalManifest(text)?.let { manifest = it }
            }
        }
        // One classifier owns every cloud failure signal, so the live stream and the terminal
        // re-query in conclude() cannot disagree about what the run's reason is.
        val error = FailureClassifier.fromEvent(event, nowIso())
        return EventEffect(id, stage, turnEnded, error, credits, manifest)
    }

    /** tmp cleanup + package/index publish. Never touches anything outside this runId. */
    private suspend fun finalize(identityId: String, row: TaskRunEntity, state: TaskState) {
        val tmp = DrivePath.tmpDir(row.packageId, "${row.taskId}-${row.attempt}")
        val cleaned = runCatching { drive.deleteSubtree(identityId, tmp) }.isSuccess
        taskRepository.save(row.copy(cleanupPending = !cleaned))

        // The package may have been deleted while the run was in flight; there is then nothing to
        // publish and the writer would only raise PackageNotFoundException.
        if (runCatching { packageRepository.getPackage(identityId, row.packageId) }.isFailure) return
        val newStatus = if (state == TaskState.SUCCEEDED) PackageStatus.DONE else PackageStatus.READY
        val kinds = if (state == TaskState.SUCCEEDED) ALL_RESULT_KINDS else null
        runCatching {
            // Sources are recomputed from Room inside the manifest lock, not carried in from here.
            manifestWriter.publishState(
                identityId = identityId,
                packageId = row.packageId,
                status = newStatus,
                latestTaskId = row.taskId,
                resultKinds = kinds,
            )
        }
    }

    /**
     * The cancel terminal and its compensation (ZLQ-114 §5.2 / §5.4) — the cancel sibling of the
     * observer's `finishTerminal`, and the only path allowed to write `CANCELED`.
     *
     * Room goes first and carries the package reset inside the same transaction, so a cancel that never
     * reached the cloud still leaves the student with a `READY` package and a working 重新生成 button
     * instead of one stuck at `GENERATING` forever. Everything after the commit is best effort:
     * `task.json`, the tmp delete and the manifest publish must never roll back Room.
     *
     * [remoteConfirmed] false means the Session may still be running. Nothing is deleted in that case —
     * a live Session would recreate the objects just removed — so the `cleanup_pending` marker stays set
     * and the attempt is handed to [CancelCompensationWorker], which retries until the cloud confirms.
     */
    private suspend fun concludeCanceled(
        identityId: String,
        intent: TaskRunEntity,
        snapshot: SessionUsageSnapshot,
        remoteConfirmed: Boolean,
    ): TaskRunEntity {
        val failure = FailureReport(
            code = CANCEL_ERROR_CODE,
            message = CANCEL_ERROR_MESSAGE,
            stage = null,
            detail = null,
            state = TaskState.CANCELED,
            occurredAt = nowIso(),
        )
        val conclusion = taskRepository.concludeCanceledAndRestorePackage(intent, snapshot)
        if (conclusion == null) {
            // The attempt row is gone. There is then no marker to keep and nothing left to compensate,
            // but the terminal still has to be written and published, as it was before the transaction
            // existed. `cleanup_pending` stays clear so no rule keeps re-enqueueing a row that has no
            // package to publish and no tmp subtree anybody can name.
            val orphan = intent.copy(state = TaskState.CANCELED.name, cleanupPending = false)
            taskRepository.save(orphan)
            logFailure(failure)
            taskRepository.publishTaskJson(identityId, orphan, failure.toTaskError(orphan.attempt))
            return orphan
        }
        val canonical = conclusion.row
        logFailure(failure)
        // Published from the canonical row, never from [intent]: the intent predates the usage merge.
        taskRepository.publishTaskJson(identityId, canonical, failure.toTaskError(canonical.attempt))
        if (!remoteConfirmed) {
            enqueueCancelCompensation(canonical.taskId, canonical.attempt)
            return canonical
        }
        // Foreground compensation shortens the window in which the cloud keeps billing; the Worker is
        // only asked for what this pass could not finish.
        if (compensator.compensate(canonical.taskId, canonical.attempt) == CompensationOutcome.RETRY) {
            enqueueCancelCompensation(canonical.taskId, canonical.attempt)
        }
        return canonical
    }

    suspend fun requestCancel(identityId: String, taskId: String, attempt: Int) {
        val row = taskRepository.get(taskId, attempt) ?: return
        val state = runCatching { TaskState.valueOf(row.state) }.getOrNull() ?: return
        if (state in TERMINAL_STATES || state == TaskState.CANCEL_REQUESTED) return
        val sessionId = row.sessionId
        if (sessionId == null) {
            val canceled = row.copy(
                state = TaskState.CANCELED.name,
                finishedAt = Instant.now().toString(),
            )
            // No Session ever existed, so there is no usage to snapshot and nothing remote to confirm;
            // the terminal write still goes through the merge so no CANCELED path bypasses
            // first-write-wins, and the package reset rides along in the same transaction.
            concludeCanceled(
                identityId = identityId,
                intent = canceled,
                snapshot = SessionUsageSnapshot.EMPTY,
                remoteConfirmed = true,
            )
            return
        }
        val canceling = row.copy(state = TaskState.CANCEL_REQUESTED.name)
        taskRepository.save(canceling)
        taskRepository.publishTaskJson(identityId, canceling)
        // Stop the remote turn right away: the observer only wakes on events, and a canceled
        // session going idle is what lets it converge to CANCELED instead of SUCCEEDED. A lost nudge
        // is not a lost cancel — the observer's own convergence pass re-asks and confirms.
        if (!cancelRemote(sessionId)) {
            Log.w(TAG, "cancel: nudge not accepted for $sessionId; observer will converge it")
        }
    }

    /**
     * Asks the cloud to stop the Session now, without waiting for it (ZLQ-114 §5.3).
     *
     * This is the nudge that lets the observer converge: the observer only wakes on events, and a
     * canceled session going idle is what turns its next wake into `CANCELED` instead of `SUCCEEDED`.
     * It returns whether the request was accepted rather than `Unit`, because a `Unit` return is what
     * let an offline cancel look successful while the turn kept running. Accepted includes a rejected
     * cancel — "already canceled / canceling / terminal" is the state being asked for.
     *
     * Acceptance is *not* confirmation. Only [ensureRemoteCanceled] establishes that.
     */
    private suspend fun cancelRemote(sessionId: String): Boolean = try {
        qcaCall { api.cancelSession(sessionId) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: QcaException) {
        e.kind == QcaErrorKind.CONFLICT ||
            e.kind == QcaErrorKind.TURN_ALREADY_RUNNING ||
            e.kind == QcaErrorKind.SESSION_NOT_FOUND ||
            e.kind == QcaErrorKind.NOT_FOUND
    }

    /**
     * Drives the Session to a *confirmed* cancel: GET before POST, POST only while it is still active,
     * confirm after the POST (ZLQ-114 §5.3). Replaces the old `cancelRemote()` + `waitForTerminated()`
     * pair, whose `Unit` returns hid every failure and made an offline cancel indistinguishable from a
     * completed one.
     */
    private suspend fun ensureRemoteCanceled(sessionId: String): RemoteCancelOutcome =
        remoteCancel.ensureCanceled(sessionId)

    /** A run that failed before it could be observed: the QCA call itself was rejected (§1.5). */
    private suspend fun failRun(identityId: String, row: TaskRunEntity, e: QcaException) {
        val state = stateFor(e)
        // §4.1: a server 401 means the stored credential is the thing that is broken, so it is
        // dropped and the session state published — the run's terminal alone would leave the rest of
        // the app still believing it is signed in.
        if (e.kind == QcaErrorKind.AUTH_EXPIRED) reportCredentialRejected()
        val failure = FailureClassifier.requestFailed(e.code ?: e.kind.name, state, e.message, nowIso())
        logFailure(failure)
        // A failure before the Session existed has nothing to read. Afterwards the counters are
        // best-effort: a failed read leaves both NULL and must not stop the terminal write.
        val snapshot = row.sessionId?.let { id ->
            SessionUsageSnapshot.from(runCatching { qcaCall { api.getSession(id) } }.getOrNull())
        } ?: SessionUsageSnapshot.EMPTY
        val failed = row.copy(
            state = state.name,
            errorCode = failure.code,
            errorMessage = failure.message,
            finishedAt = failure.occurredAt,
        )
        val canonical = taskRepository.concludeTerminal(failed, snapshot)
            ?: failed.also { taskRepository.save(it) }
        taskRepository.publishTaskJson(identityId, canonical, failure.toTaskError(canonical.attempt))
        finalize(identityId, canonical, state)
    }

    /**
     * Publishing the session state is best effort: the run's own terminal write must not be lost
     * because the state publisher threw, and a failure here is a redacted log line, never a retry.
     */
    private suspend fun reportCredentialRejected() {
        runCatching { onCredentialRejected() }
            .onFailure { Log.w(TAG, "credential rejection could not be published", it) }
    }

    private fun stateFor(e: QcaException): TaskState = when (e.kind) {
        QcaErrorKind.AUTH_EXPIRED -> TaskState.AUTH_EXPIRED
        // No credential means the run cannot proceed and no retry can help until the student logs in
        // again; AUTH_EXPIRED is the terminal state the UI already reads as "re-authenticate".
        QcaErrorKind.AUTH_REQUIRED -> TaskState.AUTH_EXPIRED
        QcaErrorKind.ACCESS_DENIED, QcaErrorKind.IDENTITY_DISABLED -> TaskState.ACCESS_DENIED
        QcaErrorKind.IDENTITY_INVALID -> TaskState.IDENTITY_INVALID
        QcaErrorKind.BAD_REQUEST_PERMANENT, QcaErrorKind.NOT_FOUND, QcaErrorKind.SESSION_NOT_FOUND ->
            TaskState.FAILED_PERMANENT
        QcaErrorKind.TURN_ALREADY_RUNNING -> TaskState.RUNNING
        QcaErrorKind.RATE_LIMITED, QcaErrorKind.RETRYABLE, QcaErrorKind.NETWORK -> TaskState.RETRY_WAIT
        QcaErrorKind.CONFLICT, QcaErrorKind.UNKNOWN -> TaskState.FAILED_RETRYABLE
    }

    private fun jitter(baseMs: Long): Long {
        val delta = (baseMs * 0.2).toLong().coerceAtLeast(1)
        return baseMs + Random.nextLong(-delta, delta + 1)
    }

    private fun nowIso(): String = Instant.now().toString()

    /** Raw failure text (Drive paths, HTTP messages, cloud reasons) is for logcat only, never the UI. */
    private fun logFailure(failure: FailureReport) {
        Log.w(
            TAG,
            "run failed code=${failure.code} state=${failure.state} stage=${failure.stage} " +
                "event=${failure.lastEventId} detail=${failure.detail}",
        )
    }
}
