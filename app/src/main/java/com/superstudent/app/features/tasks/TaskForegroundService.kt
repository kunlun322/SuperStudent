package com.superstudent.app.features.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.superstudent.app.AppContainer
import com.superstudent.app.MainActivity
import com.superstudent.app.appContainer
import com.superstudent.app.auth.REAUTHENTICATION_MESSAGE
import com.superstudent.app.reconcile.hasValidatedNetwork
import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.TaskState
import com.superstudent.core.model.TaskStage
import com.superstudent.core.security.CredentialFailureReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * What one recovery pass handed over: [handedOff] runs to the service, and whether the service could
 * actually be started — a background start can be refused, and the caller must then retry rather
 * than report the runs as resumed.
 */
data class ResumeOutcome(
    val handedOff: Int,
    val serviceStarted: Boolean,
    /**
     * Set when the pass stopped at the credential pre-flight instead of dispatching (ZLQ-119 §4.3).
     * A caller must not read `serviceStarted = false` as "try again later" while this is present:
     * nothing can resume until the student logs in again, so retrying only burns the backoff. Without
     * it, `serviceStarted = false` is transient — no validated network, or a refused background
     * start — and waiting for the next attempt is the right answer.
     */
    val credentialFailure: CredentialFailureReason? = null,
)

/**
 * Owns a generation run for its whole life: the submit chain and the observation that follows it.
 *
 * Ownership lives here rather than in a ViewModel because a ViewModel dies with the backstack entry
 * that hosts it — navigating away mid-start cancelled the submit chain between `createAttempt` and
 * the sessionId write and left a `QUEUED` row no observer could ever conclude (ZLQ-102/ZLQ-103 §2).
 * Room stays the single source of truth, so the UI observes state from the database.
 *
 * Nothing here interprets the notification being swiped away, or the task being removed from
 * recents, as a business cancel: the only cancel paths are the notification action and the page
 * button, both of which write `CANCEL_REQUESTED` through [TaskRunner.requestCancel]. A process death
 * is repaired by reconciliation at the next start, not by `onDestroy`/`onTaskRemoved`, which do not
 * run at all after a kill.
 */
class TaskForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live jobs: `new:{requestId}` for a submit chain, `obs:{taskId}#{attempt}` for an observer. */
    private val jobs = mutableMapOf<String, Job>()

    /** requestIds already dispatched in this process, so a redelivery never starts a second chain. */
    private val handledRequests = LinkedHashSet<String>()

    /** The run the notification's cancel action points at, and the one its progress text describes. */
    private var current: TaskRef? = null

    /**
     * The startId of the last `RESUME_ALL` delivery. Held as a field because the resume pass runs on
     * the service scope and has to stop the service with the startId it was actually started with
     * (§4.4), not with whatever a later `START_NEW` happened to deliver in between.
     */
    private var resumeStartId = 0

    private val container: AppContainer get() = applicationContext.appContainer

    private data class TaskRef(val identityId: String, val taskId: String, val attempt: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() first, before validating anything: the chain this service is about to
        // own must not be killable with the process in the background, and the 5-second contract has
        // to hold even for an intent this service ends up rejecting.
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, notification("正在生成学习内容…"))
        // ZLQ-119 §4.4: RESUME_ALL's task set is recomputable from Room, so a redelivered intent
        // buys nothing — it starts a second pass over rows the first pass already converged, and
        // after a credential FATAL it is what kept rebuilding the process. Everything else keeps
        // redelivery, which is what lets a START_NEW survive a death mid-submit.
        val restartPolicy =
            if (intent?.action == ACTION_RESUME_ALL) START_NOT_STICKY else START_REDELIVER_INTENT
        when (intent?.action) {
            ACTION_START_NEW -> handleStartNew(intent)
            ACTION_RESUME_ALL -> {
                resumeStartId = startId
                handleResumeAll()
            }
            ACTION_CANCEL -> handleCancel(intent)
            else -> stopIfIdle()
        }
        return restartPolicy
    }

    /**
     * One START_NEW request. The requestId is resolved before anything is created, which is what
     * makes `START_REDELIVER_INTENT` safe: a redelivered intent finds the attempt it already made.
     * A redelivered row that never got a sessionId is converged, never given a Session — the process
     * that died may already have created one that was never recorded, and a second one would
     * double-charge the student.
     */
    private fun handleStartNew(intent: Intent) {
        val identityId = intent.getStringExtra(EXTRA_IDENTITY)
        val packageId = intent.getStringExtra(EXTRA_PACKAGE)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (identityId.isNullOrBlank() || packageId.isNullOrBlank() || requestId.isNullOrBlank()) {
            stopIfIdle()
            return
        }
        if (!claimRequest(requestId)) {
            stopIfIdle()
            return
        }
        val resumeFromStage = intent.getStringExtra(EXTRA_RESUME_STAGE)
            ?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() }
        val key = "new:$requestId"
        jobs[key] = scope.launch {
            var ref: TaskRef? = null
            try {
                val existing = runCatching { container.taskRepository.findByRunId(requestId) }.getOrNull()
                when {
                    existing == null -> {
                        when (val outcome = container.taskRunner.submitNew(identityId, packageId, resumeFromStage, requestId)) {
                            is StartOutcome.Started -> {
                                ref = TaskRef(identityId, outcome.run.taskId, outcome.run.attempt)
                                attach(ref)
                                runCatching {
                                    container.taskRunner.observe(identityId, outcome.run.taskId, outcome.run.attempt)
                                }
                            }
                            // The chain never reached the cloud, so the UI has to say why: nothing in
                            // Room will show a rejection that produced no row.
                            is StartOutcome.NotStarted -> TaskStartBus.post(packageId, outcome.message)
                        }
                    }

                    existing.sessionId == null -> {
                        runCatching { container.taskRunner.reconcileSessionlessActiveRuns() }
                    }

                    existing.state == TaskState.QUEUED.name -> {
                        ref = TaskRef(identityId, existing.taskId, existing.attempt)
                        attach(ref)
                        // Session exists but the task message may never have been delivered: replay
                        // it under the same idempotency key, then observe.
                        runCatching { container.taskRunner.replaySubmit(identityId, existing.taskId, existing.attempt) }
                        runCatching { container.taskRunner.observe(identityId, existing.taskId, existing.attempt) }
                    }

                    else -> {
                        ref = TaskRef(identityId, existing.taskId, existing.attempt)
                        attach(ref)
                        runCatching { container.taskRunner.observe(identityId, existing.taskId, existing.attempt) }
                    }
                }
            } finally {
                jobs.remove(key)
                onJobFinished(ref)
            }
        }
    }

    /** Re-attaches observers to whatever is still active after a process death (design §3.5/§4). */
    private fun handleResumeAll() {
        if (jobs.containsKey(KEY_RESUME_ALL)) return
        jobs[KEY_RESUME_ALL] = scope.launch {
            try {
                // Local convergence first and ungated: it is exactly the work an offline,
                // credential-less cold start still owes, and skipping it here would re-create the
                // ZLQ-102 symptom (ZLQ-126 §2.4).
                val rows = runCatching { container.taskRunner.reconcileSessionlessActiveRuns() }
                    .getOrDefault(emptyList())
                val resumable = rows.filter {
                    it.run.state != TaskState.CANCEL_REQUESTED.name || it.run.sessionId != null
                }
                val reason = container.credentialGate.requireCredential()
                if (reason != null) {
                    // Every remaining row carries a Session, so each observer's own pre-flight is
                    // what converges it to AUTH_EXPIRED (§4.3). They are awaited, because stopping
                    // first would leave the terminal write unfinished and the row looking resumable.
                    resumable.mapNotNull { launchResume(it) }.joinAll()
                    notifyReauthenticationRequired()
                    // §4.4: stop with the startId, and schedule nothing — there is no point handing
                    // a credential-less pass to a worker that is forbidden to retry it.
                    stopSelfResult(resumeStartId)
                    return@launch
                }
                resumable.forEach { launchResume(it) }
            } finally {
                jobs.remove(KEY_RESUME_ALL)
                stopIfIdle()
            }
        }
    }

    private fun launchResume(row: ActiveRunRow): Job? {
        val run = row.run
        val key = "obs:${run.taskId}#${run.attempt}"
        if (jobs.containsKey(key)) return jobs[key]
        val ref = TaskRef(row.ownerIdentityId, run.taskId, run.attempt)
        attach(ref)
        jobs[key] = scope.launch {
            try {
                if (run.state == TaskState.QUEUED.name) {
                    runCatching { container.taskRunner.replaySubmit(ref.identityId, ref.taskId, ref.attempt) }
                }
                runCatching { container.taskRunner.observe(ref.identityId, ref.taskId, ref.attempt) }
            } finally {
                jobs.remove(key)
                onJobFinished(ref)
            }
        }
        return jobs[key]
    }

    private fun handleCancel(intent: Intent) {
        val identityId = intent.getStringExtra(EXTRA_IDENTITY)
        val taskId = intent.getStringExtra(EXTRA_TASK)
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        if (identityId.isNullOrBlank() || taskId.isNullOrBlank() || attempt <= 0) {
            stopIfIdle()
            return
        }
        val key = "cancel:$taskId#$attempt"
        if (jobs.containsKey(key)) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification("取消中…", TaskRef(identityId, taskId, attempt)))
        jobs[key] = scope.launch {
            try {
                runCatching { container.taskRunner.requestCancel(identityId, taskId, attempt) }
            } finally {
                jobs.remove(key)
                onJobFinished(null, stalledTaskId = taskId)
            }
        }
    }

    /** In-memory dedup, so one tap (or one redelivery) can never start two chains. */
    private fun claimRequest(requestId: String): Boolean {
        if (jobs.containsKey("new:$requestId")) return false
        if (!handledRequests.add(requestId)) return false
        while (handledRequests.size > MAX_HANDLED_REQUESTS) {
            val oldest = handledRequests.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        return true
    }

    private fun attach(ref: TaskRef) {
        current = ref
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification("正在生成学习内容…", ref))
    }

    /**
     * The last job out turns off the light. [ref] is the run this job owned; a job that owns no run
     * — the cancel tap — passes [stalledTaskId] instead, because the chain it just interrupted may
     * already have parked in `CANCEL_REQUESTED` / `RETRY_WAIT` with nobody left to drive it. Without
     * that handoff the row stays active forever and keeps counting against the first-build admission
     * guard until the student happens to reopen the app.
     */
    private suspend fun onJobFinished(ref: TaskRef?, stalledTaskId: String? = null) {
        if (jobs.isNotEmpty()) return
        when {
            ref != null -> finishRun(container, ref.taskId)
            stalledTaskId != null -> scheduleResumeIfStalled(container, stalledTaskId)
        }
        current = null
        stopSelf()
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) return
        current = null
        stopSelf()
    }

    /** Hands the latest attempt of [taskId] to [RunResumeWorker] when it is still active. */
    private suspend fun scheduleResumeIfStalled(container: AppContainer, taskId: String): TaskRunEntity? {
        val attempts = runCatching { container.taskRepository.listAttempts(taskId) }.getOrDefault(emptyList())
        val latest = attempts.maxByOrNull { it.attempt }
        if (latest != null && RunResumeWorker.isResumable(latest.state)) RunResumeWorker.schedule(this)
        return latest
    }

    private suspend fun finishRun(container: AppContainer, taskId: String) {
        val latest = scheduleResumeIfStalled(container, taskId)
        val label = when (latest?.state) {
            TaskState.SUCCEEDED.name -> "生成完成"
            TaskState.CANCELED.name -> "已取消"
            TaskState.UNKNOWN.name -> "任务状态未知，请关注云端"
            null -> "生成结束"
            else -> "生成失败，可重试"
        }
        notifyDone(label)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The clickable half of §4.3: a missing credential must surface as one notification that opens
     * the login page, not as a crash and not as a silent stop. It replaces the progress notification
     * rather than adding a second one, so the student is not left reading "生成失败，可重试" for a
     * failure that retrying cannot fix.
     */
    private fun notifyReauthenticationRequired() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val openLogin = PendingIntent.getActivity(
            this,
            REQUEST_REAUTH,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("超级学生")
                    .setContentText(REAUTHENTICATION_MESSAGE)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentIntent(openLogin)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build(),
            )
        }
    }

    private fun notifyDone(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("超级学生")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(false)
                .build(),
        )
    }

    private fun notification(text: String, cancelTarget: TaskRef? = current): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("超级学生")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
        // The explicit cancel action is the only notification-driven cancel path. No delete intent is
        // set on purpose: swiping the notification away must not cancel a generation.
        if (cancelTarget != null) {
            builder.addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "取消生成",
                    cancelPendingIntent(cancelTarget),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun cancelPendingIntent(ref: TaskRef): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_CANCEL,
        Intent(this, TaskForegroundService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_IDENTITY, ref.identityId)
            .putExtra(EXTRA_TASK, ref.taskId)
            .putExtra(EXTRA_ATTEMPT, ref.attempt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "SsTaskFgs"
        private const val CHANNEL_ID = "ss_task"
        private const val NOTIFICATION_ID = 1002
        private const val REQUEST_CANCEL = 2001
        private const val REQUEST_REAUTH = 2002
        private const val MAX_HANDLED_REQUESTS = 64
        private const val KEY_RESUME_ALL = "resume-all"

        const val ACTION_START_NEW = "com.superstudent.app.task.START_NEW"
        const val ACTION_RESUME_ALL = "com.superstudent.app.task.RESUME_ALL"
        const val ACTION_CANCEL = "com.superstudent.app.task.CANCEL"
        const val EXTRA_IDENTITY = "identity_id"
        const val EXTRA_TASK = "task_id"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_PACKAGE = "package_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RESUME_STAGE = "resume_from_stage"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "学习任务", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "AI 生成进度"; setShowBadge(false) },
            )
        }

        /**
         * Hands a new generation to the service. This is the ONLY thing the UI does to start one:
         * the attempt row, the Session and the task message all belong to the service's scope, so
         * leaving the screen cannot interrupt them.
         */
        fun startNew(
            context: Context,
            identityId: String,
            packageId: String,
            resumeFromStage: TaskStage?,
            requestId: String,
        ) {
            ensureChannel(context)
            runCatching {
                context.startForegroundService(
                    Intent(context, TaskForegroundService::class.java)
                        .setAction(ACTION_START_NEW)
                        .putExtra(EXTRA_IDENTITY, identityId)
                        .putExtra(EXTRA_PACKAGE, packageId)
                        .putExtra(EXTRA_REQUEST_ID, requestId)
                        .putExtra(EXTRA_RESUME_STAGE, resumeFromStage?.name),
                )
            }.onFailure { Log.w(TAG, "startNew could not reach the service", it) }
        }

        /**
         * The explicit cancel path, shared by the notification action and the page button. It is the
         * only way a generation is cancelled: swiping the notification or the task card away sets no
         * delete intent and therefore cancels nothing.
         */
        fun cancel(context: Context, identityId: String, taskId: String, attempt: Int) {
            ensureChannel(context)
            runCatching {
                context.startForegroundService(
                    Intent(context, TaskForegroundService::class.java)
                        .setAction(ACTION_CANCEL)
                        .putExtra(EXTRA_IDENTITY, identityId)
                        .putExtra(EXTRA_TASK, taskId)
                        .putExtra(EXTRA_ATTEMPT, attempt),
                )
            }.onFailure { Log.w(TAG, "cancel could not reach the service", it) }
        }

        /**
         * Cold-start / network-back recovery (ZLQ-103 §4): converge the sessionless rows FIRST and
         * await it, then hand the rest to the service. Returns how many runs were handed over.
         *
         * The convergence is awaited by the caller rather than detached, so [RunResumeWorker] cannot
         * report success for work it never owned; the observers that follow run in the service, whose
         * lifetime is not the caller's.
         *
         * This is also the one place a `RESUME_ALL` intent is built (ZLQ-126 §4.2 E-1), and every
         * trigger reaches it through [ResumeAllCoordinator] rather than around it: the three callers —
         * the application rule, the post-login callback and the worker — are the three reasons, and
         * none of them dispatches on its own behalf any more.
         *
         * [reason] defaults to [ResumeReason.NETWORK_RECOVERED] because the bare form is the worker's,
         * and WorkManager only runs that worker once connectivity is back.
         */
        suspend fun reconcileAndResume(
            context: Context,
            reason: ResumeReason = ResumeReason.NETWORK_RECOVERED,
        ): ResumeOutcome {
            val container = context.appContainer
            val dispatched = container.resumeAll.request(reason) { operation ->
                // §5.2 step 1, and §2.4: local and ungated. It is exactly the work an offline,
                // credential-less cold start still owes, and both preconditions below come after it.
                val rows = runCatching { container.taskRunner.reconcileSessionlessActiveRuns() }
                    .getOrDefault(emptyList())
                val resumable = rows.filter { it.run.state != TaskState.CANCEL_REQUESTED.name || it.run.sessionId != null }
                operation.rowCount = resumable.size
                if (resumable.isEmpty()) return@request ResumeOutcome(0, true)
                // §5.2 step 2: the credential stops only the remote half. No RESUME_ALL intent, no
                // foreground service, no worker retry — the runs are already converged above.
                val failure = container.credentialGate.requireCredential()
                if (failure != null) return@request ResumeOutcome(resumable.size, false, failure)
                if (!hasValidatedNetwork(context)) {
                    // Same shape as the credential stop, but transient: the rows stay active in Room
                    // and the network-back trigger picks them up. Handing them to that worker here is
                    // what keeps an offline cold start from stranding them — dispatching instead would
                    // only start a foreground service whose observers all fail on the first call.
                    // The worker path is excluded: re-enqueueing its own unique work from inside
                    // `doWork` would REPLACE the run that is in flight, and its `Result.retry()` is
                    // already the wait-for-connectivity mechanism.
                    if (reason != ResumeReason.NETWORK_RECOVERED) RunResumeWorker.schedule(context)
                    return@request ResumeOutcome(resumable.size, false)
                }
                // §5.2 step 4: one intent, whatever the row count is. The service re-reads the rows
                // itself, so the count never becomes a count of intents.
                ensureChannel(context)
                val started = runCatching {
                    context.startForegroundService(
                        Intent(context, TaskForegroundService::class.java).setAction(ACTION_RESUME_ALL),
                    )
                }.isSuccess
                if (!started && reason != ResumeReason.NETWORK_RECOVERED) {
                    // A refused background start is transient, and a caller that merged into this pass
                    // reports success for work this pass owned — so the handoff has to be made here,
                    // where the refusal is seen, rather than left to whichever caller happened to own
                    // the drain. The worker trigger is excluded for the same reason as above.
                    RunResumeWorker.schedule(context)
                }
                ResumeOutcome(resumable.size, started)
            }
            // A merged request is one the pass in flight already covers, so there is nothing this
            // caller owns and nothing for it to retry — the owner above reports the outcome, and a
            // transient failure has already been handed to the worker there.
            return dispatched ?: ResumeOutcome(0, true)
        }

        fun stageLabel(stage: String?): String = when (runCatching { TaskStage.valueOf(stage ?: "") }.getOrNull()) {
            TaskStage.UPLOAD -> "上传资料"
            TaskStage.PARSE -> "解析资料"
            TaskStage.QMIND_INDEX -> "建立索引"
            TaskStage.GENERATE_PLAN -> "生成学习计划"
            TaskStage.GENERATE_CARDS -> "生成记忆卡片"
            TaskStage.GENERATE_MINDMAP -> "生成思维导图"
            TaskStage.GENERATE_DECK -> "生成幻灯片"
            TaskStage.GENERATE_EXERCISES -> "生成习题"
            TaskStage.VALIDATE -> "校验产物"
            TaskStage.PUBLISH -> "发布结果"
            null -> "准备中"
        }
    }
}
