package com.superstudent.app.reconcile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three hard constraints of the ZLQ-113 × ZLQ-106 merge, pinned as far as the JVM allows.
 *
 * There is no Robolectric or instrumented harness in this repo, so the runtime half of constraint 3 —
 * that the task-run rule recovers with no connectivity probe in the way — is driven directly, and the
 * wiring halves of constraints 1 and 2 are read off the production sources the same way
 * [com.superstudent.app.features.tasks.TaskStartOwnershipTest] does. The network-free character of the
 * convergence itself, and that `reconcileAndResume` runs it before it ever reaches for the network, is
 * already pinned there; this file pins the startup wiring around it.
 */
class TaskRunRecoveryRuleTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    /** Whitespace-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "")

    // ---- constraint 3: an offline cold start still converges ----------------------------------

    @Test
    fun `reconcile recovers unconditionally, with no network in sight`() = runTest {
        // The rule takes its recovery as a plain suspending function and calls it as-is: there is no
        // connectivity probe for an offline cold start to be turned away by. This is the local
        // convergence half of reconcileAndResume (reconcileSessionlessActiveRuns) still running when
        // the device is offline — the step that releases the D-2 first-build guard, so a sessionless
        // orphan left by a killed process cannot block this identity's next generation forever.
        var recovered = 0
        val rule = TaskRunRecoveryRule { recovered++ }

        rule.reconcile()

        assertEquals("recovery must run exactly once, ungated by network", 1, recovered)
        assertEquals("task-run-recovery", rule.name)
    }

    @Test
    fun `the rule does not consult the network gate the two source rules use`() {
        val rule = read("app/src/main/java/com/superstudent/app/reconcile/TaskRunRecoveryRule.kt")
        // The gate is real and both source rules use it, so the negative below is a deliberate choice
        // about this rule, not the gate having quietly gone missing from the codebase.
        assertTrue(
            read("app/src/main/java/com/superstudent/app/reconcile/SourceFailureReconcileRule.kt")
                .contains("hasValidatedNetwork"),
        )
        assertTrue(
            read("app/src/main/java/com/superstudent/app/reconcile/ManifestProjectionReconcileRule.kt")
                .contains("hasValidatedNetwork"),
        )
        assertFalse(
            "task-run recovery must not call the gate, or offline convergence is skipped — a KDoc " +
                "mention is fine, only a call would gate it",
            rule.contains("hasValidatedNetwork("),
        )
    }

    // ---- constraint 1: one process-level startup reconciliation entry -------------------------

    @Test
    fun `the task-run rule registers on the coordinator and the cold-start call left MainActivity`() {
        val application = read("app/src/main/java/com/superstudent/app/SsApplication.kt").flat()
        // All three rules share the one entry point, and it is run once per process.
        assertTrue(application.contains("StartupReconciler.register(SourceFailureReconcileRule"))
        assertTrue(application.contains("StartupReconciler.register(ManifestProjectionReconcileRule"))
        assertTrue(application.contains("StartupReconciler.register(TaskRunRecoveryRule"))
        assertTrue(application.contains("StartupReconciler.runOnce(this)"))
        // The rule recovers through the same handoff the worker and the login path use — it did not
        // grow a parallel recovery of its own. ZLQ-120 named the trigger, so the handoff now carries a
        // reason; it is still the one function, reached from the one registration.
        assertTrue(
            application.contains(
                "TaskRunRecoveryRule{TaskForegroundService.reconcileAndResume(this,ResumeReason.PROCESS_START)}",
            ),
        )

        val main = read("app/src/main/java/com/superstudent/app/MainActivity.kt")
        // The identity-ready cold-start branch no longer reconciles directly: exactly one direct call
        // is left in this file, and constraint 2 pins it as the post-login one.
        assertEquals(
            "cold-start reconcile moved into the coordinator; only the post-login call remains",
            1,
            main.split("reconcileAndResume").size - 1,
        )
        assertFalse(
            "MainActivity must not grow a second startup entry alongside SsApplication's — a prose " +
                "mention in a comment is fine, only a StartupReconciler. call would be one",
            main.contains("StartupReconciler."),
        )
    }

    // ---- constraint 2: the three non-startup triggers stay direct -----------------------------

    @Test
    fun `login, service-stop and network-back recovery stay direct, not folded into the coordinator`() {
        val main = read("app/src/main/java/com/superstudent/app/MainActivity.kt")
        val service = read("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt")
        val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")

        // (a) post-login recovery is an event, not a startup timing: it must fire the moment a login
        // lands, which folding it into the once-per-process coordinator would lose.
        assertTrue(
            main.substringAfter("LoginScreen(onLoggedIn")
                .contains("TaskForegroundService.reconcileAndResume("),
        )
        // (b) the last job out of the service hands a stalled run to the worker.
        assertTrue(service.contains("RunResumeWorker.schedule(this)"))
        // (c) network-back recovery awaits the same handoff directly, so it can report only work it owned.
        assertTrue(worker.contains("TaskForegroundService.reconcileAndResume(applicationContext)"))
        // None of the three is routed through the startup coordinator: a `StartupReconciler.` call is
        // the entry point, and a prose mention (MainActivity's comment) is not one.
        assertFalse(main.contains("StartupReconciler."))
        assertFalse(service.contains("StartupReconciler."))
        assertFalse(worker.contains("StartupReconciler."))
    }
}
