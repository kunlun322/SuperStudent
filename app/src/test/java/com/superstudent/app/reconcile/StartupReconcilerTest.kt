package com.superstudent.app.reconcile

import com.superstudent.app.auth.AuthSessionState
import com.superstudent.app.features.tasks.CancelCompensationWorker
import com.superstudent.app.features.tasks.RunResumeWorker
import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.TaskState
import com.superstudent.core.repository.CancelCompensationStore
import com.superstudent.core.security.CredentialFailureReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The startup pass driven through its real entry point, [StartupReconciler.runOnce], with the real rule
 * classes registered — not rule by rule.
 *
 * ZLQ-126 §4.1 makes that a gate rather than a preference: the defect being fixed is a *cold start with
 * no usable credential*, and whether such a start still converges its local rows is a property of the
 * coordinator's two-phase pass, not of any single rule. `TaskRunRecoveryRuleTest` and
 * `CanceledRunReconcileRuleTest` already pin the rules in isolation; this file pins what happens when
 * they share one pass and the credential is gone.
 *
 * The pass runs on `Dispatchers.IO`, so every case awaits it with [StartupReconciler.awaitPassForTest]
 * instead of assuming a race went its way. There is no Robolectric or instrumented harness in this repo,
 * hence the in-memory Room stand-in below: it evaluates the same predicates the shipped SQL does, so
 * rule 1's write is visible to rule 2's read. The SQL strings themselves are pinned in
 * `core/database`'s own tests, which run them against the exported v3 schema.
 */
class StartupReconcilerTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    /** Whitespace-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "")

    @Before
    fun resetCoordinator() {
        StartupReconciler.resetForTest()
    }

    // ---- the fixture ---------------------------------------------------------------------------

    /**
     * One `task_run` row joined to one `learning_package` row, with the queries evaluated at call time
     * against the current values. That is the whole point: `CanceledRunReconcileRule` has to see what
     * `TaskRunRecoveryRule` wrote, and a pre-baked list of rows would have hidden the ordering
     * requirement §2.5 exists to state.
     *
     * Starting state is §4.1's case — a cancel recorded while no Session existed, whose compensation
     * marker was set but whose worker never got scheduled because the process died.
     */
    private class MutableRoom(
        val taskId: String = "task_c",
        val attempt: Int = 1,
        val packageId: String = "pkg_1",
        var runState: String = TaskState.CANCEL_REQUESTED.name,
        var cleanupPending: Boolean = true,
        var sessionId: String? = null,
        var packageStatus: String = "GENERATING",
    ) : CancelCompensationStore {

        val resets = mutableListOf<String>()
        var marks = 0

        private fun run() = TaskRunEntity(
            taskId = taskId,
            attempt = attempt,
            packageId = packageId,
            runId = "run_$taskId",
            sessionId = sessionId,
            state = runState,
            stage = null,
            progress = 40,
            resumeFromStage = null,
            lastEventId = null,
            errorCode = null,
            errorMessage = null,
            credits = 0.0,
            activeSeconds = null,
            durationSeconds = null,
            cleanupPending = cleanupPending,
            createdAt = "2026-09-30T09:00:02Z",
            startedAt = "2026-09-30T09:00:03Z",
            finishedAt = null,
            updatedAt = "2026-09-30T09:10:00Z",
        )

        private fun row() = ActiveRunRow(
            run = run(),
            ownerIdentityId = "idt_owner",
            packageStatus = packageStatus,
            packageLatestTaskId = taskId,
        )

        /** `LIST_CANCELED_GENERATING_MISMATCH`: `r.state = 'CANCELED' AND p.status = 'GENERATING'`. */
        override suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow> =
            if (runState == TaskState.CANCELED.name && packageStatus == "GENERATING") listOf(row())
            else emptyList()

        /**
         * `PACKAGE_RESET_AFTER_CANCEL`'s guards, as far as a one-row fixture can evaluate them: still
         * `GENERATING`, still this task, and the attempt really is `CANCELED`. Declining returns 0 and
         * changes nothing — a normal outcome, not an error.
         */
        override suspend fun resetPackageAfterCancel(packageId: String, taskId: String, attempt: Int): Int {
            resets += "$packageId/$taskId/$attempt"
            if (packageStatus != "GENERATING" || this.taskId != taskId || this.attempt != attempt) return 0
            if (runState != TaskState.CANCELED.name) return 0
            packageStatus = "READY"
            return 1
        }

        /** `MARK_CANCEL_CLEANUP_PENDING`: session-bearing rows only, and only while the marker is 0. */
        override suspend fun markCancelCleanupPending(taskId: String, attempt: Int): Int {
            if (sessionId == null || runState != TaskState.CANCELED.name || cleanupPending) return 0
            cleanupPending = true
            marks++
            return 1
        }

        /** `LIST_CANCEL_CLEANUP_PENDING`: `state = 'CANCELED' AND cleanup_pending = 1`. */
        override suspend fun listCancelCleanupPending(): List<TaskRunEntity> =
            if (runState == TaskState.CANCELED.name && cleanupPending) listOf(run()) else emptyList()

        override suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow? =
            if (this.taskId == taskId && this.attempt == attempt) row() else null

        override suspend fun clearCancelCleanupPending(taskId: String, attempt: Int): Int {
            if (!cleanupPending || runState != TaskState.CANCELED.name) return 0
            cleanupPending = false
            return 1
        }
    }

    /**
     * `TaskRunRecoverySql.CONVERGE_CANCELED`, applied to the fixture: `session_id IS NULL AND state =
     * 'CANCEL_REQUESTED'` becomes `CANCELED`, and `cleanup_pending` is not in the `SET` list — which is
     * exactly what leaves the marker there for the next rule to find.
     */
    private fun MutableRoom.convergeCanceled() {
        if (sessionId == null && runState == TaskState.CANCEL_REQUESTED.name) {
            runState = TaskState.CANCELED.name
        }
    }

    private class RecordingRule(
        override val name: String,
        local: Boolean = false,
        private val body: () -> Unit,
    ) : StartupReconcileRule {
        override val requiresCredential: Boolean = !local
        override suspend fun reconcile() = body()
    }

    /** A rule that leaves the gate at its interface default, for asserting that default is `true`. */
    private class DefaultGateRule : StartupReconcileRule {
        override val name = "default-gate"
        override suspend fun reconcile() {}
    }

    private fun deps(
        identity: String? = "idt_owner",
        auth: AuthSessionState = AuthSessionState.Authenticated("idt_owner"),
        authReads: MutableList<String>? = null,
    ) = StartupDeps(
        currentIdentityId = { identity },
        resolveAuth = {
            authReads?.add("resolve")
            auth
        },
    )

    private fun noCredential(reason: CredentialFailureReason = CredentialFailureReason.DECRYPT_FAILED) =
        deps(auth = AuthSessionState.ReauthenticationRequired("idt_owner", reason))

    // ---- §4.1 gate 1: the entry-level case -----------------------------------------------------

    @Test
    fun `one runOnce converges a sessionless cancel and enqueues its compensation once, with no usable credential`() {
        val room = MutableRoom()
        val enqueued = mutableListOf<Pair<String, Int>>()
        StartupReconciler.register(TaskRunRecoveryRule { room.convergeCanceled() })
        StartupReconciler.register(CanceledRunReconcileRule(room) { taskId, attempt ->
            enqueued += taskId to attempt
        })
        var remoteRan = 0
        StartupReconciler.register(RecordingRule("source-failure") { remoteRan++ })

        // The credential is undecryptable, so §2.4's requirement is the interesting part: the local
        // convergence is still owed, and the remote phase is what the gate stops.
        StartupReconciler.runOnce(noCredential())
        StartupReconciler.awaitPassForTest()

        // run → CANCELED
        assertEquals(TaskState.CANCELED.name, room.runState)
        assertTrue("the marker must survive the convergence", room.cleanupPending)
        // package → READY, through job 1's guarded UPDATE and nothing else
        assertEquals("READY", room.packageStatus)
        assertEquals(listOf("pkg_1/task_c/1"), room.resets)
        assertEquals("a sessionless row owes nothing remote, so it is not re-armed", 0, room.marks)
        // the unique work name is enqueued exactly once
        assertEquals(listOf("task_c" to 1), enqueued)
        assertEquals(
            "ss:cancel-task:task_c-1",
            CancelCompensationWorker.workName(room.taskId, room.attempt),
        )
        // and the gated phase really did not run — otherwise this case would not be proving §2.4.
        assertEquals("the remote phase must stop at the pre-flight", 0, remoteRan)
    }

    @Test
    fun `the local phase runs in registration order, and reversing it loses the convergence`() {
        // Registered the wrong way round on purpose: job 1's scan cannot see the row until recovery has
        // flipped it to CANCELED, so this pass converges the run and then stops. It is the control for
        // the case above — the fixture is not accidentally order-insensitive.
        val room = MutableRoom()
        val order = mutableListOf<String>()
        val enqueued = mutableListOf<Pair<String, Int>>()
        StartupReconciler.register(CanceledRunReconcileRule(room) { taskId, attempt ->
            order += "enqueue:$taskId#$attempt"
            enqueued += taskId to attempt
        })
        StartupReconciler.register(TaskRunRecoveryRule { room.convergeCanceled(); order += "recovery" })

        StartupReconciler.runOnce(noCredential())
        StartupReconciler.awaitPassForTest()

        assertEquals(listOf("recovery"), order)
        assertEquals("GENERATING", room.packageStatus)
        assertEquals(emptyList<Pair<String, Int>>(), enqueued)
    }

    // ---- the two phases ------------------------------------------------------------------------

    @Test
    fun `an authenticated pass runs every rule, local phase first, in registration order within it`() {
        val order = mutableListOf<String>()
        StartupReconciler.register(RecordingRule("remote-a") { order += "remote-a" })
        StartupReconciler.register(TaskRunRecoveryRule { order += "task-run-recovery" })
        StartupReconciler.register(RecordingRule("remote-b") { order += "remote-b" })
        StartupReconciler.register(RecordingRule("local-b", local = true) { order += "local-b" })

        StartupReconciler.runOnce(deps())
        StartupReconciler.awaitPassForTest()

        // The partition ignores where a rule sits in the registry across phases, and keeps the
        // registry order inside each one. §2.5's 1-then-2-then-3 is that order, not a re-sort.
        assertEquals(
            listOf("task-run-recovery", "local-b", "remote-a", "remote-b"),
            order,
        )
    }

    @Test
    fun `the gate defaults to closed, and only the two pure-Room rules open it`() {
        assertTrue(
            "forgetting to declare the gate must fail towards gated, not towards running with no PAT",
            DefaultGateRule().requiresCredential,
        )
        assertFalse(TaskRunRecoveryRule {}.requiresCredential)
        assertFalse(CanceledRunReconcileRule(MutableRoom()) { _, _ -> }.requiresCredential)

        // The two rules that reach Drive and QCA are untouched by this change (§2.3 forbids editing
        // them), and stay gated precisely because they never override the default.
        val source = read("app/src/main/java/com/superstudent/app/reconcile/SourceFailureReconcileRule.kt")
        val manifest = read("app/src/main/java/com/superstudent/app/reconcile/ManifestProjectionReconcileRule.kt")
        assertFalse(source.contains("requiresCredential"))
        assertFalse(manifest.contains("requiresCredential"))
        // They keep their own network gate, character for character.
        assertTrue(source.contains("hasValidatedNetwork"))
        assertTrue(manifest.contains("hasValidatedNetwork"))
    }

    @Test
    fun `a failed pre-flight stops the remote phase, keeps the local one, and hands the pass back`() {
        var localRan = 0
        var remoteRan = 0
        StartupReconciler.register(TaskRunRecoveryRule { localRan++ })
        StartupReconciler.register(RecordingRule("source-failure") { remoteRan++ })

        StartupReconciler.runOnce(noCredential(CredentialFailureReason.MISSING_FILE))
        StartupReconciler.awaitPassForTest()
        assertEquals("local convergence is not gated", 1, localRan)
        assertEquals(0, remoteRan)

        // Not rolled back, and not latched shut either: the student logs in, something calls runOnce
        // again, and the deferred half finally runs. The handed-back pass starts over from phase 1
        // rather than resuming mid-pass, which is only safe because every rule is required to be
        // re-runnable — the local rule running twice here is that contract, not a leak.
        StartupReconciler.runOnce(deps())
        StartupReconciler.awaitPassForTest()
        assertEquals("a handed-back pass re-runs the local phase too", 2, localRan)
        assertEquals(1, remoteRan)
    }

    @Test
    fun `with no identity nothing runs and the pre-flight is never even consulted`() {
        var localRan = 0
        var remoteRan = 0
        val authReads = mutableListOf<String>()
        StartupReconciler.register(TaskRunRecoveryRule { localRan++ })
        StartupReconciler.register(RecordingRule("source-failure") { remoteRan++ })

        StartupReconciler.runOnce(deps(identity = null, authReads = authReads))
        StartupReconciler.awaitPassForTest()
        assertEquals(0, localRan)
        assertEquals(0, remoteRan)
        assertEquals(
            "reading the credential store before knowing whose credential it is would publish a " +
                "re-authentication prompt for a signed-out installation",
            emptyList<String>(),
            authReads,
        )

        StartupReconciler.runOnce(deps(identity = "idt_owner", authReads = authReads))
        StartupReconciler.awaitPassForTest()
        assertEquals(1, localRan)
        assertEquals(1, remoteRan)
        assertEquals(listOf("resolve"), authReads)
    }

    @Test
    fun `two runOnce calls in a row produce one pass`() {
        var ran = 0
        StartupReconciler.register(RecordingRule("source-failure") { ran++ })

        // Both calls happen before the dispatcher gets to the first, which is the shape that made the
        // old per-trigger intents race each other.
        StartupReconciler.runOnce(deps())
        StartupReconciler.runOnce(deps())
        StartupReconciler.awaitPassForTest()

        assertEquals(1, ran)
    }

    @Test
    fun `a rule that throws does not stop the ones behind it, in either phase`() {
        val order = mutableListOf<String>()
        StartupReconciler.register(RecordingRule("local-throws", local = true) { throw IllegalStateException("boom") })
        StartupReconciler.register(TaskRunRecoveryRule { order += "task-run-recovery" })
        StartupReconciler.register(RecordingRule("remote-throws") { throw IllegalStateException("boom") })
        StartupReconciler.register(RecordingRule("remote-ok") { order += "remote-ok" })

        StartupReconciler.runOnce(deps())
        StartupReconciler.awaitPassForTest()

        assertEquals(listOf("task-run-recovery", "remote-ok"), order)
    }

    // ---- §5 item 12: the pass is safe to repeat -------------------------------------------------

    @Test
    fun `a second pass over the same row converges nothing further and joins the same work chain`() {
        val room = MutableRoom()
        val enqueued = mutableListOf<Pair<String, Int>>()
        StartupReconciler.register(TaskRunRecoveryRule { room.convergeCanceled() })
        StartupReconciler.register(CanceledRunReconcileRule(room) { taskId, attempt ->
            enqueued += taskId to attempt
        })

        // A credential failure hands the pass back, so the next cold start really does come round
        // again — with the marker still set, because compensation has not finished.
        StartupReconciler.runOnce(noCredential())
        StartupReconciler.awaitPassForTest()
        StartupReconciler.runOnce(noCredential())
        StartupReconciler.awaitPassForTest()

        assertEquals(TaskState.CANCELED.name, room.runState)
        assertEquals("READY", room.packageStatus)
        // Job 1's scan no longer matches once the package is READY, so the guarded UPDATE is not even
        // asked for a second time...
        assertEquals(listOf("pkg_1/task_c/1"), room.resets)
        // ...and when it is asked for again it declines without moving anything backwards.
        assertEquals(0, room.resetPackageAfterCancelBlocking("pkg_1", "task_c", 1))
        assertEquals("READY", room.packageStatus)
        // Job 2 asks twice, for the same attempt.
        assertEquals(listOf("task_c" to 1, "task_c" to 1), enqueued)
        assertEquals(
            "one attempt is one chain: KEEP dedupes on the name both enqueues derive",
            1,
            enqueued.distinct().size,
        )
        assertEquals(
            "ss:cancel-task:task_c-1",
            CancelCompensationWorker.workName(room.taskId, room.attempt),
        )
    }

    /** Same store, called from the test thread rather than from inside the pass. */
    private fun MutableRoom.resetPackageAfterCancelBlocking(packageId: String, taskId: String, attempt: Int): Int =
        runBlocking { resetPackageAfterCancel(packageId, taskId, attempt) }

    @Test
    fun `a stale resume worker finds nothing resumable and exits successfully instead of backing off`() {
        // The run the worker was scheduled for is already terminal, and AUTH_EXPIRED is not resumable:
        // re-attaching observation to it would restart a generation nobody asked for.
        assertFalse(RunResumeWorker.isResumable(TaskState.AUTH_EXPIRED.name))
        assertFalse(RunResumeWorker.isResumable(TaskState.CANCELED.name))
        assertTrue(RunResumeWorker.isResumable(TaskState.RUNNING.name))

        // The credential half of "exits successfully" cannot be driven on the JVM — WorkManager needs
        // an instrumented harness — so it is pinned in the source: the gate is answered with
        // `success()`, never with `retry()`, and the retry branch still exists for a genuinely
        // transient refusal.
        val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
            .flat()
        assertTrue(
            worker.contains("if(outcome.credentialFailure!=null)returnResult.success()"),
        )
        assertTrue(worker.contains("returnif(outcome.serviceStarted)Result.success()elseResult.retry()"))
    }

    // ---- §4.1 gate 2: the registration order in production --------------------------------------

    @Test
    fun `the application registers the two local rules in order and keeps exactly one startup entry`() {
        val application = read("app/src/main/java/com/superstudent/app/SsApplication.kt")
        val recovery = application.indexOf("StartupReconciler.register(\n            TaskRunRecoveryRule")
        val canceled = application.indexOf("StartupReconciler.register(\n            CanceledRunReconcileRule")
        val entry = application.indexOf("StartupReconciler.runOnce(")
        assertTrue("TaskRunRecoveryRule must be registered", recovery > 0)
        assertTrue("CanceledRunReconcileRule must be registered", canceled > 0)
        assertTrue(
            "§2.5: task-run recovery first, then the canceled-run compensation that reads its result",
            recovery < canceled,
        )
        assertTrue("both must be registered before the single pass runs", canceled < entry)
        assertEquals(
            "a second process-level startup entry is what §2.3 forbids",
            1,
            application.split("StartupReconciler.runOnce(").size - 1,
        )
    }
}
