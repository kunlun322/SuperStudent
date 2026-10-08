package com.superstudent.app.reconcile

import com.superstudent.core.database.ActiveRunRow
import com.superstudent.core.database.TaskRunEntity
import com.superstudent.core.model.TaskState
import com.superstudent.core.repository.CancelCompensationStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cold-start half of ZLQ-114 §5.5, and with it §8.1 item 2: the process dies between the atomic
 * cancel transaction and the enqueue, so nothing in the app is watching the row — the local tree
 * already looks consistent — and the next startup is the only thing that can find it.
 *
 * The database half of that item is pinned in
 * `core/database/src/test/java/com/superstudent/core/database/CancelCompensationSqlTest.kt`, which
 * executes the shipped SQL against a real v3 schema and asserts the package row actually becomes
 * `READY`. This file pins the rule that calls that SQL: what it scans, in what order, what it resets,
 * which rows it marks, and how many times it enqueues — driven directly, because this repo has no
 * Robolectric or instrumented harness.
 *
 * The wiring halves (registration on the shared coordinator, the Worker it enqueues) are read off the
 * production sources the same way [TaskRunRecoveryRuleTest] does.
 */
class CanceledRunReconcileRuleTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    /** Whitespace-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "")

    private fun run(
        taskId: String = "task_c",
        attempt: Int = 1,
        packageId: String = "pkg_1",
        sessionId: String? = "sess_c",
        state: String = TaskState.CANCELED.name,
        cleanupPending: Boolean = false,
    ) = TaskRunEntity(
        taskId = taskId,
        attempt = attempt,
        packageId = packageId,
        runId = "run_$taskId",
        sessionId = sessionId,
        state = state,
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
        finishedAt = "2026-09-30T09:10:00Z",
        updatedAt = "2026-09-30T09:10:00Z",
    )

    private fun row(
        run: TaskRunEntity,
        packageStatus: String = "GENERATING",
        ownerIdentityId: String = "idt_owner",
    ) = ActiveRunRow(
        run = run,
        ownerIdentityId = ownerIdentityId,
        packageStatus = packageStatus,
        packageLatestTaskId = run.taskId,
    )

    // ---- job 1 + job 2 together: the crash between the transaction and the enqueue ------------

    @Test
    fun `a cancel that died before its enqueue is converged and re-enqueued by the next cold start`() =
        runTest {
            // The §5.2 transaction committed (`CANCELED`, package still `GENERATING` in this fixture
            // because it is a pre-fix row) and the process died before the enqueue. Nothing else looks
            // at this row again, so the scan is the only route back to it.
            val store = FakeStore(
                mismatch = listOf(row(run(cleanupPending = false))),
                pending = listOf(run(cleanupPending = true)),
            )
            val enqueued = mutableListOf<Pair<String, Int>>()
            val rule = CanceledRunReconcileRule(store) { taskId, attempt -> enqueued += taskId to attempt }

            rule.reconcile()

            // Job 1 ran first: the package is put back to `READY` through §5.2's guarded UPDATE, and
            // the reset is asked for with the row's own identity triple, not with anything derived
            // from the signed-in account.
            assertEquals(
                listOf("resetPackageAfterCancel(pkg_1, task_c, 1)"),
                store.resets.map { (pkg, task, attempt) -> "resetPackageAfterCancel($pkg, $task, $attempt)" },
            )
            // The session-bearing mismatch row is re-armed, because a row this old cannot be
            // distinguished from one whose remote cancel was never confirmed.
            assertEquals(listOf("task_c" to 1), store.marked)
            // Job 2 then enqueues exactly the pending rows, once each.
            assertEquals(listOf("task_c" to 1), enqueued)
            assertEquals("canceled-run-reconcile", rule.name)
        }

    @Test
    fun `a mismatch row with no session is converged but not re-armed, because nothing remote is owed`() =
        runTest {
            val store = FakeStore(
                mismatch = listOf(row(run(sessionId = null, cleanupPending = false))),
                pending = emptyList(),
            )
            val enqueued = mutableListOf<Pair<String, Int>>()

            CanceledRunReconcileRule(store) { taskId, attempt -> enqueued += taskId to attempt }.reconcile()

            assertEquals(1, store.resets.size)
            assertTrue(
                "a sessionless cancel had no cloud turn to stop, so marking it would enqueue a " +
                    "pass with nothing to prove",
                store.marked.isEmpty(),
            )
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun `every pending row is enqueued once, and a row that is not a pending cancel is not`() = runTest {
        val store = FakeStore(
            mismatch = emptyList(),
            pending = listOf(
                run(taskId = "task_a", attempt = 1, cleanupPending = true),
                // The scan's own SQL already filters on both columns; the rule repeats the check so a
                // widened query cannot silently start enqueuing work for a live or finished run.
                run(taskId = "task_b", attempt = 2, cleanupPending = true, state = TaskState.RUNNING.name),
                run(taskId = "task_c", attempt = 3, cleanupPending = false),
                run(taskId = "task_d", attempt = 4, cleanupPending = true),
            ),
        )
        val enqueued = mutableListOf<Pair<String, Int>>()

        CanceledRunReconcileRule(store) { taskId, attempt -> enqueued += taskId to attempt }.reconcile()

        assertEquals(listOf("task_a" to 1, "task_d" to 4), enqueued)
        assertTrue("no mismatch rows means nothing to reset", store.resets.isEmpty())
    }

    @Test
    fun `a guard that declines is not an error, and the pass still enqueues what is owed`() = runTest {
        // §5.2's UPDATE returns 0 when the package moved on to a newer attempt or has another run in
        // flight. That is the correct outcome, so the rule must neither throw nor skip the enqueue.
        val store = FakeStore(
            mismatch = listOf(row(run(cleanupPending = false))),
            pending = listOf(run(cleanupPending = true)),
            resetResult = 0,
        )
        val enqueued = mutableListOf<Pair<String, Int>>()

        CanceledRunReconcileRule(store) { taskId, attempt -> enqueued += taskId to attempt }.reconcile()

        assertEquals(1, store.resets.size)
        assertEquals(listOf("task_c" to 1), enqueued)
    }

    @Test
    fun `a clean database leaves the rule with nothing to do`() = runTest {
        val store = FakeStore(mismatch = emptyList(), pending = emptyList())
        val enqueued = mutableListOf<Pair<String, Int>>()

        CanceledRunReconcileRule(store) { taskId, attempt -> enqueued += taskId to attempt }.reconcile()

        assertTrue(store.resets.isEmpty())
        assertTrue(store.marked.isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    // ---- the offline guarantee --------------------------------------------------------------

    @Test
    fun `the rule does not consult the network gate the two source rules use`() {
        val rule = read("app/src/main/java/com/superstudent/app/reconcile/CanceledRunReconcileRule.kt")
        // Positive control: the gate is real and both rules this one sits beside use it, so the
        // negative below is a deliberate choice about this rule, not the gate having gone missing.
        assertTrue(
            read("app/src/main/java/com/superstudent/app/reconcile/SourceFailureReconcileRule.kt")
                .contains("hasValidatedNetwork"),
        )
        assertTrue(
            read("app/src/main/java/com/superstudent/app/reconcile/ManifestProjectionReconcileRule.kt")
                .contains("hasValidatedNetwork"),
        )
        assertFalse(
            "the local convergence is exactly what an offline cold start still has to do; a KDoc " +
                "mention is fine, only a call would gate it",
            rule.contains("hasValidatedNetwork("),
        )
    }

    @Test
    fun `the rule makes no cloud call of its own`() {
        val rule = read("app/src/main/java/com/superstudent/app/reconcile/CanceledRunReconcileRule.kt")
        // Both jobs are pure Room. Anything that has to reach the cloud belongs to the Worker, which
        // is why the rule takes an enqueue lambda instead of a compensator.
        assertFalse(rule.contains("QcaApi"))
        assertFalse(rule.contains("DriveRepository"))
        assertFalse(rule.contains("RemoteCancelConverger"))
        assertFalse(rule.contains("ManifestWriter"))
        assertFalse(
            "compensation is the Worker's job; doing it here would make an offline pass fail",
            rule.contains("compensate("),
        )
    }

    // ---- the wiring: one coordinator, one work name ----------------------------------------

    @Test
    fun `the rule registers on the existing coordinator and does not add a startup entry`() {
        val application = read("app/src/main/java/com/superstudent/app/SsApplication.kt").flat()
        assertTrue(
            "§5.5 must reuse the one startup pass, not schedule its own",
            application.contains("StartupReconciler.register(CanceledRunReconcileRule("),
        )
        // It reads through the same repository slice the Worker does, and hands WorkManager the
        // attempt — not the package, not the account.
        assertTrue(application.contains("store=container.taskRepository"))
        assertTrue(application.contains("CancelCompensationWorker.enqueue(this,taskId,attempt)"))
        assertTrue(application.contains("StartupReconciler.register(TaskRunRecoveryRule"))
        assertEquals("still exactly one runOnce", 1, application.split("StartupReconciler.runOnce(").size - 1)

        // No second process-level entry grew anywhere else: MainActivity keeps only its post-login
        // direct call, and the service/worker pair is untouched.
        val main = read("app/src/main/java/com/superstudent/app/MainActivity.kt")
        val service = read("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt")
        val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
        assertFalse(main.contains("CanceledRunReconcileRule"))
        assertFalse(main.contains("StartupReconciler."))
        assertFalse(service.contains("StartupReconciler."))
        assertFalse(worker.contains("StartupReconciler."))
    }

    @Test
    fun `the worker it enqueues is the unique, network-constrained one the design specifies`() {
        // ZLQ-114 §5.4. The name cannot carry the section number: a JVM method name may not contain a dot.
        val worker = read("app/src/main/java/com/superstudent/app/features/tasks/CancelCompensationWorker.kt")
        assertTrue(worker.contains("\"ss:cancel-task:\""))
        assertTrue(worker.flat().contains("enqueueUniqueWork(workName(taskId,attempt),ExistingWorkPolicy.KEEP,request)"))
        assertTrue(worker.contains("NetworkType.CONNECTED"))
        assertTrue(worker.contains("BackoffPolicy.EXPONENTIAL"))
        // Awaited in full: returning before the pass finished would report success for a Session that
        // is still billing, and a process death right after would lose the only scheduled retry.
        assertTrue(worker.contains("compensate(taskId, attempt)"))
        assertFalse(
            "the compensation must not be detached into another coroutine the Worker does not wait for",
            worker.contains("GlobalScope") || worker.contains("launch {"),
        )
    }
}

/**
 * In-memory [CancelCompensationStore]. Records the calls rather than modelling the database: the SQL
 * behind each one is pinned against a real v3 schema by `CancelCompensationSqlTest`.
 */
private class FakeStore(
    private val mismatch: List<ActiveRunRow>,
    private val pending: List<TaskRunEntity>,
    private val resetResult: Int = 1,
) : CancelCompensationStore {
    val resets = mutableListOf<Triple<String, String, Int>>()
    val marked = mutableListOf<Pair<String, Int>>()

    override suspend fun listCanceledGeneratingMismatch(): List<ActiveRunRow> = mismatch

    override suspend fun resetPackageAfterCancel(packageId: String, taskId: String, attempt: Int): Int {
        resets += Triple(packageId, taskId, attempt)
        return resetResult
    }

    override suspend fun markCancelCleanupPending(taskId: String, attempt: Int): Int {
        marked += taskId to attempt
        return 1
    }

    override suspend fun listCancelCleanupPending(): List<TaskRunEntity> = pending

    override suspend fun findAttemptWithOwner(taskId: String, attempt: Int): ActiveRunRow? =
        throw UnsupportedOperationException("not used by CanceledRunReconcileRule")

    override suspend fun clearCancelCleanupPending(taskId: String, attempt: Int): Int =
        throw UnsupportedOperationException("not used by CanceledRunReconcileRule")
}
