package com.superstudent.app.features.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZLQ-120 §4.2 E-1 and design §8.1 item 10, pinned as source: which function builds the `RESUME_ALL`
 * intent, and which call forms reach it.
 *
 * Neither question is drivable on the JVM — both are about `Context.startForegroundService` and an
 * `Activity` callback — so they are pinned the way `TaskRunnerUsagePathsTest` and
 * `TaskStartOwnershipTest` pin their call sites: whole-tree counts, plus one brace-matched block each,
 * never a whole file. The state machine the intent sits behind is driven for real in
 * [ResumeAllCoordinatorTest]; this file is the wiring around it.
 *
 * The criterion is E-1 as re-scoped by ZLQ-126 §4.2, not the original E: counting `RESUME_ALL`
 * dispatch sites alone was disproved, because the service's own `when (intent?.action)` branch and its
 * restart-policy check also name the constant without dispatching anything. What is counted here is the
 * intent *construction*, and separately the *call forms* that can lead to one.
 */
class ResumeAllWiringTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val main = read("app/src/main/java/com/superstudent/app/MainActivity.kt")
    private val application = read("app/src/main/java/com/superstudent/app/SsApplication.kt")
    private val service = read("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt")
    private val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
    private val container = read("app/src/main/java/com/superstudent/app/AppContainer.kt")

    /** Every production Kotlin file in the app module, as repo-relative path to text. */
    private val production: Map<String, String> = File(repoRoot, "app/src/main/java").walkTopDown()
        .onEnter { it.name != "build" }
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(repoRoot).path to it.readText() }

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

    /** Whitespace- and trailing-comma-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "").replace(",)", ")")

    private fun countIn(needle: String, sources: Map<String, String>): List<String> =
        sources.flatMap { (path, text) -> List(text.split(needle).size - 1) { path } }

    // ---- E-1: one intent constructor, exactly three call forms --------------------------------

    @Test
    fun `one function in the whole app builds a RESUME_ALL intent, and it builds it inside the coordinator`() {
        val constructors = countIn("setAction(ACTION_RESUME_ALL)", production)
        assertEquals(
            "a second constructor is a second dispatch path, which is exactly what §5.2 removes: " +
                constructors,
            1,
            constructors.size,
        )
        assertEquals("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt", constructors.single())

        val handoff = block(service, "suspend fun reconcileAndResume(")
        assertTrue(
            "the intent must be built inside the coordinator's pass, not beside it",
            handoff.contains("setAction(ACTION_RESUME_ALL)"),
        )
        assertTrue(handoff.contains("container.resumeAll.request(reason)"))
        assertTrue(
            "the coordinator call must wrap the construction, not follow it",
            handoff.indexOf("container.resumeAll.request(reason)") < handoff.indexOf("setAction(ACTION_RESUME_ALL)"),
        )
        // §5.2's "never per-task intents": one dispatch covers every resumable row, because the service
        // re-reads the rows itself. An extra on this intent would put the row count back into the
        // dispatch count.
        assertFalse(
            "the RESUME_ALL intent must carry no per-task extra",
            handoff.substring(handoff.indexOf("Intent(context, TaskForegroundService::class.java)"))
                .substringBefore("}.isSuccess")
                .contains("putExtra"),
        )
        // Inside the service, the other three mentions of the constant are the restart-policy check,
        // the `when` branch and the constant's own declaration. Four is what "no new dispatch site"
        // looks like. (The coordinator's KDoc also names it, in prose, which is why this is scoped to
        // the one file rather than counted across the tree.)
        assertEquals(
            4,
            countIn("ACTION_RESUME_ALL", production.filterKeys { it.endsWith("TaskForegroundService.kt") }).size,
        )
    }

    @Test
    fun `exactly three call forms reach it - the application, the activity and the worker`() {
        // Qualified, so the declaration (`suspend fun reconcileAndResume(`) and the KDoc mention in
        // TaskRunRecoveryRule (`.reconcileAndResume` — no receiver, no paren) are both excluded by
        // construction rather than by a comment-stripping pass that could itself be wrong.
        val calls = countIn("TaskForegroundService.reconcileAndResume(", production)
        assertEquals("a fourth form is a fourth trigger: $calls", 3, calls.size)
        assertEquals(
            listOf(
                "app/src/main/java/com/superstudent/app/MainActivity.kt",
                "app/src/main/java/com/superstudent/app/SsApplication.kt",
                "app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt",
            ),
            calls.sorted(),
        )
        // No second branch capturing the activity: the cold-start branch was ZLQ-113's removal, and a
        // re-added one would be a second RESUME_ALL per launch that the coordinator would then have to
        // merge away instead of never happening.
        assertEquals("exactly one MainActivity branch may call it", 1, calls.count { it.endsWith("MainActivity.kt") })
        assertEquals(1, main.split("TaskForegroundService.reconcileAndResume(").size - 1)

        // And the unqualified count agrees, so nothing calls it from inside the service either: three
        // calls plus the one declaration, and the KDoc mention has no paren.
        assertEquals(4, countIn("reconcileAndResume(", production).size)
    }

    @Test
    fun `each of the three forms names its own trigger reason`() {
        assertTrue(
            application.flat().contains(
                "TaskRunRecoveryRule{TaskForegroundService.reconcileAndResume(this,ResumeReason.PROCESS_START)}",
            ),
        )
        assertTrue(
            main.flat().contains(
                "TaskForegroundService.reconcileAndResume(applicationContext,ResumeReason.LOGIN_COMPLETED)",
            ),
        )
        // The worker takes the default, which is NETWORK_RECOVERED: `TaskStartOwnershipTest` pins the
        // bare literal inside `doWork`, and WorkManager only runs that worker once its CONNECTED
        // constraint holds, so the default is this caller and not a guess.
        assertTrue(worker.contains("TaskForegroundService.reconcileAndResume(applicationContext)"))
        assertTrue(
            service.flat().contains("reason:ResumeReason=ResumeReason.NETWORK_RECOVERED)"),
        )
        // §5.2's fourth reason stays reserved: a value nothing sends is the honest way to leave room
        // for an in-service re-issue, and a value something sends silently would be an unaudited path.
        assertEquals(
            listOf("app/src/main/java/com/superstudent/app/features/tasks/ResumeAllCoordinator.kt"),
            countIn("SERVICE_HANDOFF", production),
        )
    }

    // ---- §8.1 item 10: what the three triggers do and do not do -------------------------------

    @Test
    fun `the activity's cold-start branch picks a route and resumes nothing`() {
        // Not `block`: the branch's first brace is its own `if`, so brace matching would stop at the
        // `} else {` and read only the signed-out half. The region up to the next statement is the
        // whole cold-start decision.
        val coldStart = main.substring(
            main.indexOf("val identityId = container.prefs.snapshotAccount().first"),
            main.indexOf("val initial = start"),
        )
        assertFalse(coldStart.contains("reconcileAndResume"))
        assertFalse(coldStart.contains("StartupReconciler."))
        assertTrue(coldStart.contains("start = Routes.LOGIN"))
        assertTrue(coldStart.contains("start = Routes.shell("))
        // The recovery the branch used to own is the coordinator's task-run rule. §2.3's bound is that
        // this round adds no startup entry: `runOnce` has exactly the two callers it already had — the
        // application pass and the identity-ready signal — and the two are one pass, because
        // StartupReconciler latches on the first (`StartupReconcilerTest` drives that).
        assertTrue(application.contains("StartupReconciler.runOnce(this)"))
        assertEquals(
            listOf(
                "app/src/main/java/com/superstudent/app/SsApplication.kt",
                "app/src/main/java/com/superstudent/app/features/packages/PackagesViewModel.kt",
            ),
            countIn("StartupReconciler.runOnce(", production).sorted(),
        )
    }

    @Test
    fun `the first-login callback still fires, ahead of the navigation that follows it`() {
        val callback = main.substring(main.indexOf("LoginScreen(onLoggedIn"))
        assertTrue(callback.contains("TaskForegroundService.reconcileAndResume("))
        assertTrue(
            "the callback's timing is fixed by ZLQ-126 §3.7 / design §5.3: recovery first, then route",
            callback.indexOf("TaskForegroundService.reconcileAndResume(") < callback.indexOf("navController.navigate("),
        )
        // Activity scope, so the recovery outlives the navigation that immediately replaces the screen.
        assertTrue(callback.contains("lifecycleScope.launch {"))
    }

    @Test
    fun `the worker and service handoffs still fire, and the offline branch keeps handing off`() {
        assertTrue(worker.contains("TaskForegroundService.reconcileAndResume(applicationContext)"))
        // The service's last-job-out handoff is the path an offline cold start lands on: without it, a
        // pass that stops at the network precondition would leave resumable rows with nobody to retry.
        val handoff = block(service, "suspend fun reconcileAndResume(")
        assertTrue(handoff.contains("!hasValidatedNetwork(context)"))
        assertTrue(handoff.contains("RunResumeWorker.schedule(context)"))
        assertTrue(
            "re-enqueueing the worker's own unique work from inside its doWork would REPLACE the run in " +
                "flight, so the worker trigger must be excluded from the offline handoff",
            handoff.contains("if (reason != ResumeReason.NETWORK_RECOVERED) RunResumeWorker.schedule(context)"),
        )
        assertTrue(worker.contains("ExistingWorkPolicy.REPLACE"))
        // A refused background start has to hand off in the pass, not at the caller: a caller that
        // merged into the drain reports success for work it did not own, so the owner's failure would
        // otherwise be reported by nobody and retried by nobody.
        assertTrue(
            handoff.contains("if (!started && reason != ResumeReason.NETWORK_RECOVERED)"),
        )
        assertTrue(
            "the handoff must follow the dispatch it is cleaning up after",
            handoff.indexOf("}.isSuccess") < handoff.indexOf("if (!started && reason"),
        )
        // The service still hands a stalled run to that worker when the last job leaves.
        assertEquals(1, service.split("RunResumeWorker.schedule(this)").size - 1)
    }

    // ---- §5.2's step order, and §2.4's local-first rule inside it -----------------------------

    @Test
    fun `the pass converges locally first and gates only the remote half`() {
        val handoff = block(service, "suspend fun reconcileAndResume(")
        val converge = handoff.indexOf("reconcileSessionlessActiveRuns()")
        val empty = handoff.indexOf("if (resumable.isEmpty())")
        val credential = handoff.indexOf("credentialGate.requireCredential()")
        val network = handoff.indexOf("hasValidatedNetwork(context)")
        val dispatch = handoff.indexOf("startForegroundService(")
        listOf(converge, empty, credential, network, dispatch).forEach { assertTrue("a step went missing", it >= 0) }
        assertTrue("local convergence first", converge < credential)
        assertTrue("then the empty-rows stop", empty < credential)
        assertTrue("then the credential precondition", credential < network)
        assertTrue("then the network precondition", network < dispatch)
        // §2.4 verbatim: the preflight may gate remote and sessionful work only. A gate ahead of the
        // convergence is ZLQ-102's original symptom — no credential, so no startup work, so the orphan
        // keeps counting against first-build admission forever.
        assertFalse(
            "nothing may sit between the start of the pass and the local convergence",
            handoff.substring(handoff.indexOf("container.resumeAll.request(reason)"), converge)
                .contains("credentialGate"),
        )
        // §8.1 item 9's N=0: no rows means no intent, and it means so before either precondition is
        // even consulted, so an idle cold start cannot start a foreground service.
        assertTrue(
            handoff.substring(empty, credential).contains("return@request ResumeOutcome(0, true)"),
        )
        // The row count is reported to the coordinator for the log line, which is what makes
        // `rows=0` in §7.3's contract a fact about this pass rather than an inference.
        assertTrue(handoff.contains("operation.rowCount = resumable.size"))
    }

    @Test
    fun `the coordinator is application-scoped, so the three triggers share one guard`() {
        assertTrue(container.contains("val resumeAll = ResumeAllCoordinator()"))
        // One container per process, built in the application's onCreate — which is what makes the
        // coordinator's in-memory single-flight enough, and why §5.4 needs no cross-process mechanism.
        assertTrue(application.contains("container = AppContainer(this)"))
        assertTrue(service.contains("context.appContainer"))
        assertEquals(
            "one instance, constructed once: a second guard would be a second dispatch",
            listOf("app/src/main/java/com/superstudent/app/AppContainer.kt"),
            countIn("ResumeAllCoordinator()", production),
        )
    }
}
