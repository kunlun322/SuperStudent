package com.superstudent.app.features.tasks

import com.superstudent.core.database.TaskRunRecoverySql
import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three directions of ZLQ-103, pinned as far as the JVM allows.
 *
 * None of this can drive a real `Service`: the repo has no Robolectric or instrumented harness, so
 * `onStartCommand`, `startForeground` and intent redelivery are acceptance-tested on a device instead.
 * What is real code here, and is therefore pinned here, is (a) the decision table recovery branches
 * on — see [TaskRecoveryTest] and the SQL it writes in `TaskRunRecoverySqlTest` — and (b) *which call
 * each owner makes, in which order*: the submit chain lives in the service and nowhere else, the
 * sessionless window is one critical section with a compensation write on every exit, and recovery
 * converges before admission counts. Each guard reads one brace-matched block, not a whole file, so
 * an unrelated edit cannot satisfy it by accident.
 */
class TaskStartOwnershipTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val runner = read("app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt")
    private val service = read("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt")
    private val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
    private val viewModel = read("app/src/main/java/com/superstudent/app/features/packages/PackagesViewModel.kt")

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    /** The text from [marker] to the end of the block its first `{` opens. */
    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in source.indexOf('{', start) until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    /**
     * The text from [marker] to the blank line that ends an expression-bodied declaration. `block`
     * cannot be used for those: they open no brace of their own, so brace matching would run on into
     * whatever function follows.
     */
    private fun expr(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        return source.substring(start).substringBefore("\n\n")
    }

    /** Whitespace- and trailing-comma-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "").replace(",)", ")")

    /** Production Kotlin under the given roots, as repo-relative path to text. */
    private fun productionSources(vararg roots: String): Map<String, String> = roots
        .flatMap { File(repoRoot, it).walkTopDown().onEnter { it.name != "build" }.toList() }
        .filter { it.isFile && it.extension == "kt" }
        .filter { "/src/test/" !in it.path && "/src/androidTest/" !in it.path }
        .associate { it.relativeTo(repoRoot).path to it.readText() }

    private fun ordered(vararg positions: Int) {
        positions.forEach { assertTrue("a required call is missing", it >= 0) }
        assertEquals("calls are out of order", positions.toList(), positions.sorted())
    }

    // ---- direction 1: the service owns the submit chain --------------------------------------

    @Test
    fun `the page fires one intent and owns no part of the submit chain`() {
        val start = block(viewModel, "fun startGeneration(")
        assertTrue(start.contains("TaskForegroundService.startNew("))
        // A requestId the service can resolve a redelivery by; it must be minted per tap, not reused.
        assertTrue(start.contains("Ids.run()"))
        assertFalse(
            "the chain must not run in a scope that dies with the backstack entry",
            start.contains("viewModelScope"),
        )
        assertFalse(start.contains("taskRunner"))
        assertFalse(start.contains("createAttempt"))

        val cancel = block(viewModel, "fun requestCancel(")
        assertTrue(cancel.contains("TaskForegroundService.cancel("))
        assertFalse("a cancel must survive navigation too", cancel.contains("viewModelScope"))
    }

    @Test
    fun `nothing but the service can create an attempt or a session`() {
        val sources = productionSources("app/src/main/java")
        val creatingAttempts = sources.filter { it.value.contains("createAttempt(") }.keys
        assertEquals(
            "createAttempt belongs to the submit chain, which belongs to the service",
            setOf("app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt"),
            creatingAttempts,
        )
        val submitting = sources.filter { it.value.contains("submitNew(") }.keys
        assertEquals(
            setOf(
                "app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt",
                "app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt",
            ),
            submitting,
        )
        assertFalse("the service must not talk to the cloud itself", service.contains("api.createSession"))
        assertFalse(service.contains("createAttempt("))
    }

    @Test
    fun `the service goes foreground before it looks at the intent and asks for redelivery`() {
        val start = block(service, "override fun onStartCommand(")
        ordered(
            start.indexOf("startForeground("),
            start.indexOf("when (intent?.action)"),
        )
        assertTrue(
            "a killed process must get the intent back, or the chain it owned is lost silently",
            start.contains("START_REDELIVER_INTENT"),
        )
        assertFalse(
            "onTaskRemoved does not run after a kill, so treating it as a cancel would be a guess",
            service.contains("override fun onTaskRemoved"),
        )
    }

    @Test
    fun `a redelivered START_NEW resolves its own attempt before creating anything`() {
        val handle = block(service, "private fun handleStartNew(")
        ordered(
            handle.indexOf("claimRequest(requestId)"),
            handle.indexOf("findByRunId(requestId)"),
            handle.indexOf("submitNew("),
        )
        assertEquals("exactly one submit per request", 1, handle.split("submitNew(").size - 1)
        // The requestId that never reached createAttempt is the ONLY branch that may submit; a row
        // that exists but has no sessionId is converged, never given a Session it may already have.
        assertTrue(
            handle.indexOf("existing == null ->") < handle.indexOf("submitNew(") &&
                handle.indexOf("submitNew(") < handle.indexOf("existing.sessionId == null ->"),
        )
        val sessionless = handle.substring(
            handle.indexOf("existing.sessionId == null ->"),
            handle.indexOf("existing.state =="),
        )
        assertTrue(sessionless.contains("reconcileSessionlessActiveRuns()"))
        assertFalse(
            "recovery must never create a second Session for one attempt",
            sessionless.contains("submitNew(") || sessionless.contains("createSession"),
        )
    }

    @Test
    fun `one tap cannot start two chains`() {
        val claim = block(service, "private fun claimRequest(")
        assertTrue(claim.contains("jobs.containsKey(\"new:\$requestId\")"))
        assertTrue("a requestId handled earlier in this process is not handled again", claim.contains("handledRequests.add(requestId)"))
        assertTrue(claim.contains("MAX_HANDLED_REQUESTS"))
    }

    @Test
    fun `swiping the notification or the task card away cancels nothing`() {
        val notification = block(service, "private fun notification(")
        assertTrue(notification.contains("addAction("))
        assertTrue(notification.contains("取消生成"))
        assertTrue(notification.contains("cancelPendingIntent(cancelTarget)"))
        assertFalse(
            "a delete intent would make an accidental swipe an irreversible business cancel",
            service.contains("setDeleteIntent"),
        )
        val pending = expr(service, "private fun cancelPendingIntent(")
        assertTrue(pending.contains("setAction(ACTION_CANCEL)"))
        assertTrue(pending.contains("FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
    }

    @Test
    fun `a rejection the service produced still reaches the page`() {
        val handle = block(service, "private fun handleStartNew(")
        // Nothing in Room records a rejection that created no row, so the bus is the only channel.
        assertTrue(handle.contains("is StartOutcome.NotStarted -> TaskStartBus.post(packageId, outcome.message)"))
        assertTrue(
            "the page must collect it into the same error the start used to produce",
            block(viewModel, "class PackageDetailViewModel(").contains("_startError.value = rejection.message"),
        )
        assertTrue(block(viewModel, "fun clearStartError()").contains("TaskStartBus.clear()"))
        read("app/src/main/java/com/superstudent/app/features/tasks/TaskStartBus.kt").let { bus ->
            assertTrue(bus.contains("object TaskStartBus"))
            assertTrue(bus.contains("MutableStateFlow<Rejection?>(null)"))
        }
    }

    @Test
    fun `a rejection is shown only on the package that was turned away`() {
        // The bus is sticky so a rejection survives navigating back to the screen. Sticky and
        // unscoped together blamed every package opened afterwards for one guard decision.
        val detail = block(viewModel, "class PackageDetailViewModel(")
        assertTrue(
            "the sticky value must be paired with the bound packageId, which bind() sets after init",
            detail.contains("TaskStartBus.message,") && detail.contains("packageId,"),
        )
        assertTrue(detail.contains("rejection?.takeIf { it.packageId == id }"))
        assertTrue(
            "the service must say which package it turned away",
            block(service, "private fun handleStartNew(").contains("TaskStartBus.post(packageId,"),
        )
    }

    @Test
    fun `a rejection is delivered once rather than replayed into every later visit`() {
        // Sticky and never consumed meant reopening the package re-showed a guard decision whose
        // condition was long gone — worded as an instruction to wait for a run that no longer existed.
        val detail = block(viewModel, "class PackageDetailViewModel(")
        val collector = detail.substringAfter(".collect { rejection ->").substringBefore("fun bind(")
        assertTrue("delivering a rejection must consume it", collector.contains("TaskStartBus.clear()"))
        assertTrue(
            "that clear() re-emits null into the same collector; ignoring nulls is what keeps the " +
                "message on screen instead of erasing it a moment later",
            collector.contains("if (rejection == null) return@collect"),
        )
        assertTrue(collector.contains("_startError.value = rejection.message"))
    }

    // ---- direction 2: compensation on every path out of the sessionless window ----------------

    @Test
    fun `the whole sessionless window is one critical section shared with recovery`() {
        val submit = block(runner, "suspend fun submitNew(")
        assertEquals(
            "the window must be a single lock, not several",
            1,
            submit.split("startMutex.withLock").size - 1,
        )
        val lock = submit.substring(submit.indexOf("startMutex.withLock"))
        ordered(
            lock.indexOf("reconcileLocked()"),
            lock.indexOf("admitFirstBuild("),
            lock.indexOf("createAttempt("),
            lock.indexOf("liveSubmitKeys += key"),
            lock.indexOf("api.createSession("),
            lock.indexOf("taskRepository.save(withSession)"),
        )
        // The lock covers the sessionless window and nothing longer: holding it across the send and
        // the observation would serialize every generation in the app behind one student's run.
        val lockBlock = block(submit, "startMutex.withLock")
        assertFalse(lockBlock.contains("sendTaskMessage("))
        assertFalse(lockBlock.contains("publishTaskJson"))
        assertFalse(lockBlock.contains("observe("))
        assertTrue(
            "the send happens after the window closed",
            submit.indexOf("sendTaskMessage(") > submit.indexOf(lockBlock) + lockBlock.length,
        )
        assertTrue(
            "recovery takes the same lock, so it cannot race a live submit",
            block(runner, "suspend fun reconcileSessionlessActiveRuns(")
                .contains("startMutex.withLock { reconcileLocked() }"),
        )
    }

    @Test
    fun `every exit between createAttempt and the sessionId write compensates`() {
        val submit = block(runner, "suspend fun submitNew(")
        // `Throwable`, not `CancellationException`: a rejected createSession, a Drive publish failure
        // and an unexpected exception all leave the same orphan behind.
        assertTrue(submit.contains("} catch (t: Throwable) {"))
        val compensate = submit.substring(submit.indexOf("liveSubmitKeys -= key"))
        ordered(
            compensate.indexOf("liveSubmitKeys -= key"),
            compensate.indexOf("withContext(NonCancellable) { compensateStart(identityId, created, t) }"),
            compensate.indexOf("throw t"),
        )
        assertTrue(
            "NonCancellable is what lets the repair outlive the cancellation that triggered it",
            runner.contains("import kotlinx.coroutines.NonCancellable"),
        )
        // The outer handler rethrows cancellation rather than turning it into a Failed outcome, so a
        // cancelled scope still cancels — after the row has been repaired.
        assertTrue(submit.contains("} catch (e: CancellationException) {"))
    }

    @Test
    fun `compensation writes through the guarded converge, never a whole-row save`() {
        val compensate = block(runner, "private suspend fun compensateStart(")
        assertTrue(compensate.contains("taskRepository.convergeInterrupted("))
        assertTrue(compensate.contains("TaskRecovery.ERROR_CODE_START_INTERRUPTED"))
        assertFalse(
            "a whole-row save would overwrite a state a concurrent observer just concluded",
            compensate.contains("taskRepository.save("),
        )
        // A cloud rejection keeps its richer classification instead of being flattened to
        // START_INTERRUPTED, and still ends in a terminal write.
        assertTrue(compensate.contains("if (cause is QcaException)"))
        assertTrue(compensate.contains("failRun(identityId, run, cause)"))
        // Only an actual move may reset the package, or a stale pass would flip a live one to READY.
        assertTrue(compensate.contains("if (moved == 0) return"))
    }

    @Test
    fun `a live submit is distinguishable from an orphan, and stops being so on both exits`() {
        assertTrue(
            "the set is read outside the lock, so it has to be synchronized",
            runner.contains("Collections.synchronizedSet(mutableSetOf<String>())") ||
                runner.contains("Collections.synchronizedSet(mutableSetOf())"),
        )
        val submit = block(runner, "suspend fun submitNew(")
        assertEquals("claimed once", 1, submit.split("liveSubmitKeys += key").size - 1)
        assertEquals(
            "released on the failure exit and on the success exit",
            2,
            submit.split("liveSubmitKeys -=").size - 1,
        )
        val reconcile = block(runner, "private suspend fun reconcileLocked(")
        assertTrue(
            "recovery consults it before deciding a sessionless row is an orphan",
            reconcile.contains("liveSubmitKeys.contains(runKey(run.taskId, run.attempt))"),
        )
        assertTrue(reconcile.contains("TaskRecovery.decide(run.state, run.sessionId != null, held)"))
    }

    @Test
    fun `both cloud calls of a start are idempotent per attempt`() {
        val submit = block(runner, "suspend fun submitNew(")
        assertTrue(submit.contains("\"ss:create-session:\${created.taskId}-\${created.attempt}\""))
        assertTrue(block(runner, "private suspend fun sendTaskMessage(").contains("\"ss:send-task:\${run.taskId}-\${run.attempt}\""))
        // The replay uses the SAME key: that is what makes "process died before sendEvents" a replay
        // rather than a second turn, and what makes TURN_ALREADY_RUNNING a success.
        assertTrue(block(runner, "suspend fun replaySubmit(").contains("\"ss:send-task:\$taskId-\$attempt\""))
        listOf(block(runner, "private suspend fun sendTaskMessage("), block(runner, "suspend fun replaySubmit(")).forEach {
            assertTrue("an already-running turn means the key got through earlier", it.contains("QcaErrorKind.TURN_ALREADY_RUNNING) return true"))
        }
    }

    // ---- direction 3: recovery converges, and converges first ---------------------------------

    @Test
    fun `recovery runs before admission, so a legacy orphan cannot block a legitimate start`() {
        val submit = block(runner, "suspend fun submitNew(")
        val lock = submit.substring(submit.indexOf("startMutex.withLock"))
        assertTrue(lock.indexOf("reconcileLocked()") < lock.indexOf("admitFirstBuild("))
        assertTrue(
            "recovery is local only, so a cold start cannot depend on connectivity",
            block(runner, "private suspend fun reconcileLocked(").let {
                !it.contains("api.") && !it.contains("publishPackage") && !it.contains("publishTaskJson")
            },
        )
    }

    @Test
    fun `the D-2 guard itself is unchanged and is counted locally`() {
        val admit = expr(runner, "internal fun admitFirstBuild(")
        assertEquals(
            "notebookBound||sameIdentityActiveRuns==0",
            admit.substringAfter("=").flat(),
        )
        val count = expr(runner, "private suspend fun countSameIdentityActiveRuns(")
        assertTrue(count.contains("taskRepository.countActiveByIdentity(identityId)"))
        assertFalse(
            "a Drive read here would let a network blip admit a second concurrent first build",
            count.contains("packageRepository") || count.contains("getPackage"),
        )
        // QUEUED still counts: the guard is not what got relaxed, the orphan is what got repaired.
        assertTrue(TaskRunRecoverySql.COUNT_ACTIVE_BY_IDENTITY.contains("'QUEUED'"))
        assertTrue(TaskRunRecoverySql.LIST_ACTIVE_WITH_OWNER.contains("'QUEUED'"))
    }

    @Test
    fun `observe hands a sessionless run to recovery instead of returning silently`() {
        val observe = block(runner, "suspend fun observe(")
        val head = observe.substringBefore("var row = initial")
        assertFalse(
            "the old silent skip is what left the orphan active forever",
            head.contains("sessionId ?: return"),
        )
        ordered(head.indexOf("val sessionId = initial.sessionId"), head.indexOf("if (sessionId == null)"))
        assertTrue(head.contains("reconcileSessionlessActiveRuns()"))
        assertTrue(
            "a run that is already concluded must not be re-observed",
            head.contains("in TERMINAL_STATES) return"),
        )
    }

    @Test
    fun `resume converges first and then only re-attaches observers`() {
        val resumeAll = block(service, "private fun handleResumeAll()")
        ordered(
            resumeAll.indexOf("reconcileSessionlessActiveRuns()"),
            resumeAll.indexOf("launchResume("),
        )
        val handoff = block(service, "suspend fun reconcileAndResume(")
        ordered(
            handoff.indexOf("reconcileSessionlessActiveRuns()"),
            handoff.indexOf("startForegroundService("),
        )
        assertTrue(
            "a refused background start must be reported, not swallowed",
            handoff.contains("ResumeOutcome(resumable.size, started)"),
        )
        // The service resumes the run under the identity that owns it, not the one that is logged in.
        val launch = block(service, "private fun launchResume(")
        assertTrue(launch.contains("TaskRef(row.ownerIdentityId, run.taskId, run.attempt)"))
        assertTrue(
            "a QUEUED row with a Session replays the same idempotent send before observing",
            launch.contains("replaySubmit(ref.identityId, ref.taskId, ref.attempt)"),
        )
        assertFalse(launch.contains("submitNew("))
    }

    @Test
    fun `recovery never restarts a failure the student has to retry by hand`() {
        assertFalse(RunResumeWorker.isResumable(TaskState.FAILED_RETRYABLE.name))
        assertFalse(RunResumeWorker.isResumable(TaskState.FAILED_PERMANENT.name))
        assertFalse(RunResumeWorker.isResumable(TaskState.SUCCEEDED.name))
        assertFalse(RunResumeWorker.isResumable(TaskState.CANCELED.name))
        assertFalse(RunResumeWorker.isResumable(null))
        assertFalse(RunResumeWorker.isResumable("NOT_A_STATE"))
        listOf(
            TaskState.QUEUED,
            TaskState.RUNNING,
            TaskState.RETRY_WAIT,
            TaskState.UNKNOWN,
            TaskState.CANCEL_REQUESTED,
        ).forEach { assertTrue("$it is still owned by the cloud", RunResumeWorker.isResumable(it.name)) }
        assertTrue(
            "every terminal, including the START_INTERRUPTED rows recovery produces, is excluded",
            TaskRunner.TERMINAL_STATES.none { RunResumeWorker.isResumable(it.name) },
        )

        val doWork = block(worker, "override suspend fun doWork()")
        assertTrue(
            "awaited, not detached: success may only be reported for work this worker owned",
            doWork.contains("TaskForegroundService.reconcileAndResume(applicationContext)"),
        )
        assertFalse(doWork.contains("launch {"))
        assertEquals(2, doWork.split("Result.retry()").size - 1)
        assertTrue(doWork.contains("outcome.serviceStarted"))
    }

    /**
     * Found on a device: canceling while the network is down parks the chain in `RETRY_WAIT` with the
     * cancel latched, the cancel job is then the last one standing, and the service stopped without
     * handing the run to the worker. Nothing drove it again, so the row stayed active and kept
     * counting against the first-build guard until the app was reopened by hand.
     */
    @Test
    fun `the last job out hands a stalled run to the resume worker`() {
        val cancel = block(service, "private fun handleCancel(")
        assertTrue(
            "a cancel that outlives the chain it interrupted must still schedule its recovery",
            cancel.contains("onJobFinished(null, stalledTaskId = taskId)"),
        )
        assertFalse(
            "the cancel job's exit is the one that can strand the run; the bad-intent exit owns nothing",
            cancel.substringAfter("} finally {").contains("stopIfIdle()"),
        )

        val finished = block(service, "private suspend fun onJobFinished(")
        ordered(
            finished.indexOf("jobs.isNotEmpty()"),
            finished.indexOf("finishRun(container, ref.taskId)"),
            finished.indexOf("scheduleResumeIfStalled(container, stalledTaskId)"),
            finished.indexOf("stopSelf()"),
        )
        assertTrue(finished.contains("ref != null -> finishRun(container, ref.taskId)"))
        assertTrue(finished.contains("stalledTaskId != null -> scheduleResumeIfStalled(container, stalledTaskId)"))

        val schedule = block(service, "private suspend fun scheduleResumeIfStalled(")
        assertTrue(schedule.contains("RunResumeWorker.isResumable(latest.state)"))
        assertTrue(schedule.contains("RunResumeWorker.schedule(this)"))
        // One rule, one copy: the terminal-notification path must not grow its own.
        assertEquals(1, service.split("RunResumeWorker.schedule(this)").size - 1)
        assertTrue(block(service, "private suspend fun finishRun(").contains("scheduleResumeIfStalled(container, taskId)"))

        // The resume pass itself must not re-schedule: it is what the worker starts, so a second
        // handoff there would be a worker -> service -> worker loop.
        assertFalse(block(service, "private fun handleResumeAll(").contains("RunResumeWorker.schedule("))
    }

    @Test
    fun `a converged run releases its package only under every guard`() {
        val reset = block(runner, "private suspend fun resetPackageAfterConverge(")
        ordered(
            reset.indexOf("latestAttempt("),
            reset.indexOf("countOtherActiveForPackage("),
            reset.indexOf("TaskRecovery.resetsPackage("),
            reset.indexOf("packageBackToReady("),
        )
        val reconcile = block(runner, "private suspend fun reconcileLocked(")
        assertTrue(
            "the reset only follows a write that actually moved the row",
            reconcile.contains("if (moved > 0)"),
        )
        assertTrue(block(runner, "private suspend fun compensateStart(").contains("TaskRecovery.resetsPackage("))
    }

    // ---- the boundaries this fix must not cross ----------------------------------------------

    @Test
    fun `the schema is v4, every migration is registered, and no destructive fallback exists anywhere`() {
        read("core/database/src/main/java/com/superstudent/core/database/SsDatabase.kt").let { db ->
            assertTrue(db.contains("version = 4"))
            assertTrue(db.contains("addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"))
        }
        val schemas = File(repoRoot, "core/database/schemas/com.superstudent.core.database.SsDatabase").listFiles()
            ?.map { it.name }?.sorted()
        assertEquals(listOf("1.json", "2.json", "3.json", "4.json"), schemas)
        // ZLQ-130 takes the schema to v4 by *adding* source_asset lease/recovery columns, so the
        // earlier paths an installed user has already run must still be there untouched.
        read("core/database/src/main/java/com/superstudent/core/database/Migrations.kt").let { m ->
            assertTrue(m.contains("Migration(1, 2)"))
            assertTrue(m.contains("Migration(2, 3)"))
            assertTrue(m.contains("Migration(3, 4)"))
        }
        productionSources("app/src", "core").forEach { (path, text) ->
            assertFalse(
                "$path would wipe every installed user's local copy of their finished runs",
                text.contains("fallbackTo" + "DestructiveMigration"),
            )
        }
    }

    @Test
    fun `the shipped artifact identity is the v7 release ops set`() {
        read("app/build.gradle.kts").let { gradle ->
            assertTrue(gradle.contains("versionCode = 10"))
            assertTrue(gradle.contains("versionName = \"1.0.0-rc10\""))
        }
    }
}
