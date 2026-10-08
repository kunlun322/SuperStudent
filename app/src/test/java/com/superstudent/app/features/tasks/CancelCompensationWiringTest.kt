package com.superstudent.app.features.tasks

import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring ZLQ-114 §5.2–§5.4 depends on, pinned as far as the JVM allows — §8.1 items 4 and 9, plus
 * the call-order requirements whose breach would silently reintroduce the original defect.
 *
 * Two kinds of assertion live here. The ones that are real code call the production function
 * (`workName`, `isResumable`), so they fail on a semantic change rather than on a reformat. The rest
 * are about *which call a path makes and in what order*, which no JVM harness in this repo can
 * observe — `concludeCanceledAndRestorePackage` needs a Room database and `applyCancel` needs an SSE
 * transport — so each is pinned by reading one brace-matched block of the production source, the same
 * way [TaskRunnerUsagePathsTest] and `TaskStartOwnershipTest` do it. The behaviour those calls have is
 * proven elsewhere: the transaction and its guards by
 * `core/database/src/test/java/com/superstudent/core/database/CancelCompensationSqlTest.kt` against a
 * real v3 schema, and the compensation pass itself by `CancelCompensatorTest` against the production
 * converger, `DriveRepository` and `ManifestWriter`.
 */
class CancelCompensationWiringTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val runner = read("app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt")
    private val repository =
        read("core/database/src/main/java/com/superstudent/core/repository/TaskRepository.kt")
    private val worker =
        read("app/src/main/java/com/superstudent/app/features/tasks/CancelCompensationWorker.kt")
    private val container = read("app/src/main/java/com/superstudent/app/AppContainer.kt")

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

    /** The text from [marker] to the `)` closing the argument list its trailing `(` opens. */
    private fun call(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced parentheses after `$marker`")
    }

    /** Whitespace- and trailing-comma-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "").replace(",)", ")")

    /** One `const val NAME = """…"""` SQL string. `block` cannot read these: SQL has no braces. */
    private fun sqlConstant(name: String): String {
        val sql = read("core/database/src/main/java/com/superstudent/core/database/TaskRunRecoverySql.kt")
        val marker = "const val $name = \"\"\""
        val start = sql.indexOf(marker)
        assertTrue("`$name` disappeared from TaskRunRecoverySql.kt", start >= 0)
        return sql.substring(start + marker.length, sql.indexOf("\"\"\"", start + marker.length))
    }

    // ---- §8.1 item 4: one work chain per (taskId, attempt) ------------------------------------

    @Test
    fun `the work name is exactly the attempt, so a second enqueue joins the chain instead of racing it`() {
        assertEquals("ss:cancel-task:task_c1-2", CancelCompensationWorker.workName("task_c1", 2))
        assertEquals("ss:cancel-task:task_c1-1", CancelCompensationWorker.workName("task_c1", 1))
        // The attempt is part of the key, not decoration: two attempts of one task compensate two
        // different tmp subtrees and two different Sessions, so they must be two chains.
        assertFalse(
            CancelCompensationWorker.workName("task_c1", 1) == CancelCompensationWorker.workName("task_c1", 2)
        )
        // And the task is part of it too, so one attempt number cannot collide across tasks.
        assertFalse(
            CancelCompensationWorker.workName("task_a", 1) == CancelCompensationWorker.workName("task_b", 1)
        )
    }

    @Test
    fun `both enqueuers derive the name from that one function and keep the existing chain`() {
        val enqueue = block(worker, "fun enqueue(context: Context")
        assertTrue(
            "a second name format would silently produce a second chain for the same attempt, which " +
                "KEEP could not dedupe",
            enqueue.flat().contains("enqueueUniqueWork(workName(taskId,attempt),ExistingWorkPolicy.KEEP,request)"),
        )
        assertTrue(enqueue.contains("NetworkType.CONNECTED"))
        assertTrue(enqueue.contains("BackoffPolicy.EXPONENTIAL"))

        // The two callers both go through it: the cold-start rule and the foreground cancel path.
        val application = read("app/src/main/java/com/superstudent/app/SsApplication.kt").flat()
        assertTrue(application.contains("CancelCompensationWorker.enqueue(this,taskId,attempt)"))
        assertTrue(container.flat().contains("CancelCompensationWorker.enqueue(appContext,taskId,attempt)"))
        assertFalse(
            "no caller may build the unique name itself",
            application.contains("\"ss:cancel-task:") || container.contains("\"ss:cancel-task:"),
        )
    }

    // ---- §8.1 item 9: CANCELED stays out of the resume set ------------------------------------

    @Test
    fun `a canceled run is still not resumable, so recovery cannot restart a generation nobody asked for`() {
        assertFalse(RunResumeWorker.isResumable(TaskState.CANCELED.name))
        // The rest of the set is unchanged: this is an anti-regression assertion on accepted ZLQ-106
        // behaviour, not a redefinition of it.
        assertTrue(RunResumeWorker.isResumable(TaskState.QUEUED.name))
        assertTrue(RunResumeWorker.isResumable(TaskState.RUNNING.name))
        assertTrue(RunResumeWorker.isResumable(TaskState.RETRY_WAIT.name))
        assertTrue(RunResumeWorker.isResumable(TaskState.UNKNOWN.name))
        assertTrue(RunResumeWorker.isResumable(TaskState.CANCEL_REQUESTED.name))
        // Terminal states, and the ones the cancel fix must not have dragged in.
        assertFalse(RunResumeWorker.isResumable(TaskState.SUCCEEDED.name))
        assertFalse(RunResumeWorker.isResumable(TaskState.FAILED_RETRYABLE.name))
        assertFalse(RunResumeWorker.isResumable(null))
    }

    @Test
    fun `the resume worker did not grow a cancel branch of its own`() {
        val resume = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
        // ZLQ-114's compensation is a separate unique work keyed on the attempt; folding it into the
        // REPLACE-policy resume chain would let a later resume evict a pending compensation.
        assertFalse(resume.contains("CancelCompensation"))
        assertFalse(resume.contains("cleanup_pending") || resume.contains("cleanupPending"))
        assertTrue(
            "the resume chain keeps its own name and policy",
            resume.contains("enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)"),
        )
    }

    // ---- §5.2: one transaction, marker set, canonical row returned ----------------------------

    @Test
    fun `the cancel transaction merges usage, sets the marker, resets the package and re-reads the row`() {
        val tx = block(repository, "suspend fun concludeCanceledAndRestorePackage(")
        // One transaction around all of it — the atomicity is the fix.
        assertTrue(tx.contains("db.inTransaction"))
        assertEquals(1, tx.split("db.inTransaction").size - 1)
        // First-write-wins merge reused, not reimplemented: a cancel must not overwrite usage the row
        // already froze, and must not lose the snapshot it was handed.
        assertTrue(tx.contains("mergeTerminalUsage(current, intent, snapshot)"))
        assertTrue(tx.contains("state = TaskState.CANCELED.name"))
        assertTrue(
            "the marker is written set by the transaction; clearing is the compensator's CAS",
            tx.contains("cleanupPending = true"),
        )
        assertFalse(tx.contains("cleanupPending = false"))
        // The package reset rides inside the same transaction, guarded, and its row count is surfaced.
        assertTrue(tx.contains("packageDao.resetToReadyAfterCancel("))
        assertTrue(tx.contains("packageRestored = restored"))
        // And the caller gets the canonical row read back inside the transaction, never its own intent.
        assertTrue(tx.contains("CancelConclusion(row = canonical ?: merged, packageRestored = restored)"))
        assertTrue(tx.contains("?: return@inTransaction null"))
        // §5.2 forbids the tolerant read in the new cancel path: a missing usage value must not become
        // a silent 0 that then wins the first-write-wins merge.
        assertFalse(tx.contains("getOrDefault(0)"))
        assertFalse(tx.contains("getOrDefault("))
    }

    @Test
    fun `the guarded reset SQL is the shipped constant, tested against the exported v3 schema`() {
        val dao = read("core/database/src/main/java/com/superstudent/core/database/Daos.kt")
        val query = dao.indexOf("@Query(TaskRunRecoverySql.PACKAGE_RESET_AFTER_CANCEL)")
        val decl = dao.indexOf("suspend fun resetToReadyAfterCancel(")
        assertTrue("the DAO lost the cancel reset", query >= 0 && decl >= 0)
        assertTrue(
            "the reset must be that guarded statement — a `block` cannot read an interface method, so " +
                "the annotation is matched to the declaration directly below it",
            decl - query in 1..80,
        )
        assertTrue(
            "the attempt is a parameter, because two of the guards scope to this exact attempt",
            dao.substring(decl).lineSequence().first()
                .contains("(packageId: String, taskId: String, attempt: Int, now: String): Int"),
        )
        val guard = sqlConstant("PACKAGE_RESET_AFTER_CANCEL")
        // The four guards of §5.2, so a package that moved on keeps its GENERATING.
        assertTrue(guard.contains("status = 'GENERATING'"))
        assertTrue(guard.contains("latest_task_id = :taskId"))
        assertTrue("a newer attempt owns the package", guard.contains("SELECT MAX(attempt) FROM task_run"))
        assertTrue("another active run of the same package blocks the reset", guard.contains("NOT EXISTS"))
        assertTrue(guard.contains("SET status = 'READY'"))

        // No schema change for *this* fix: it reuses a v3 column. ZLQ-130 has since taken the
        // database to v4, so the assertion this block actually owes is narrower and still checkable —
        // the 3→4 migration adds source_asset lease columns and leaves `task_run` and
        // `cleanup_pending` alone, which is what keeps the guarded reset above valid on v4.
        listOf("3.json", "4.json").forEach { name ->
            assertTrue(
                "$name lost cleanup_pending",
                read("core/database/schemas/com.superstudent.core.database.SsDatabase/$name")
                    .contains("cleanup_pending"),
            )
        }
        assertTrue(
            "the database must still be version 4",
            read("core/database/src/main/java/com/superstudent/core/database/SsDatabase.kt")
                .contains("version = 4,"),
        )
        assertFalse(
            "the 3→4 migration must not touch task_run",
            read("core/database/src/main/java/com/superstudent/core/database/Migrations.kt")
                .substringAfter("val MIGRATION_3_4_SQL")
                .contains("task_run"),
        )
    }

    // ---- §5.3: the remote cancel returns a result, and ordering is load-bearing ---------------

    @Test
    fun `applyCancel confirms the remote before it measures, and measures before it concludes`() {
        val apply = block(runner, "suspend fun applyCancel()")
        val confirmAt = apply.indexOf("ensureRemoteCanceled(sessionId)")
        val readAt = apply.indexOf("api.getSession(sessionId)")
        val concludeAt = apply.indexOf("concludeCanceled(")
        assertTrue("the confirm call disappeared", confirmAt >= 0)
        assertTrue("the usage read disappeared", readAt >= 0)
        assertTrue("the conclude call disappeared", concludeAt >= 0)
        assertTrue(
            "reading before the cancel settled would snapshot a session still winding down, and " +
                "reading after an unconfirmed cancel would freeze duration_seconds on a turn still billing",
            confirmAt < readAt,
        )
        assertTrue("the terminal must be written from the confirmed outcome", readAt < concludeAt)
        // An unconfirmed cancel keeps both counters NULL rather than inventing a measurement.
        assertTrue(apply.contains("SessionUsageSnapshot.EMPTY"))
        assertTrue(apply.contains("remoteConfirmed = confirmed"))
        // A failed usage read must not hold up CANCELED.
        assertTrue(apply.flat().contains("runCatching{qcaCall{api.getSession(sessionId)}}"))
    }

    @Test
    fun `the converger returns an explicit outcome, so an offline cancel cannot look successful`() {
        val remote = read("core/network/src/main/java/com/superstudent/core/network/RemoteCancel.kt")
        assertTrue(remote.contains("enum class RemoteCancelOutcome"))
        assertTrue(remote.contains("CONVERGED"))
        assertTrue(remote.contains("UNCONFIRMED"))
        assertTrue(
            "§5.3: GET before POST, POST only while still active, confirm after",
            remote.contains("suspend fun ensureCanceled("),
        )
        // The old Unit-returning pair is gone; a Unit return is exactly what hid every failure.
        assertFalse(runner.contains("private suspend fun waitForTerminated("))
        // Both are expression-bodied, so `block` would stop at the try's own brace: the declarations
        // are read whole, from one to the next.
        val nudgeStart = runner.indexOf("private suspend fun cancelRemote(")
        val confirmStart = runner.indexOf("private suspend fun ensureRemoteCanceled(")
        assertTrue("the nudge disappeared", nudgeStart >= 0)
        assertTrue("the confirm wrapper disappeared", confirmStart > nudgeStart)
        val nudge = runner.substring(nudgeStart, confirmStart)
        val confirm = runner.substring(confirmStart)
        assertTrue("the nudge still returns whether it was accepted, not Unit", nudge.contains(": Boolean"))
        // Cancel-transition conflicts are the state being asked for, so they count as accepted.
        assertTrue(nudge.contains("QcaErrorKind.CONFLICT"))
        assertTrue(nudge.contains("QcaErrorKind.SESSION_NOT_FOUND"))
        // Acceptance is not confirmation: only the converger establishes that.
        assertTrue(
            confirm.substringBefore("\n    /**").contains("remoteCancel.ensureCanceled(sessionId)"),
        )
        assertTrue(remote.contains(": RemoteCancelOutcome"))
    }

    // ---- §5.4: the marker routes the attempt to the compensator -------------------------------

    @Test
    fun `an unconfirmed cancel is enqueued, and a confirmed one is compensated in the foreground first`() {
        val conclude = block(runner, "private suspend fun concludeCanceled(")
        val unconfirmed = conclude.indexOf("if (!remoteConfirmed)")
        val compensate = conclude.indexOf("compensator.compensate(canonical.taskId, canonical.attempt)")
        assertTrue(unconfirmed >= 0)
        assertTrue(compensate >= 0)
        assertTrue(
            "nothing may be deleted while the Session could still be running — a live Session would " +
                "recreate the objects just removed",
            unconfirmed < compensate,
        )
        // The unconfirmed branch hands the attempt straight to the Worker and does not compensate here.
        val unconfirmedBranch = conclude.substring(unconfirmed, compensate)
        assertTrue(unconfirmedBranch.contains("enqueueCancelCompensation(canonical.taskId, canonical.attempt)"))
        assertFalse(unconfirmedBranch.contains("compensator.compensate("))
        // The foreground pass only enqueues what it could not finish; a COMPLETED pass enqueues nothing.
        assertTrue(conclude.contains("CompensationOutcome.RETRY"))
        assertEquals(
            "exactly two enqueue sites: the unconfirmed branch and the RETRY fallback",
            2,
            conclude.split("enqueueCancelCompensation(").size - 1,
        )
        // Everything after the commit is best effort and must never roll back Room.
        assertTrue(conclude.contains("taskRepository.concludeCanceledAndRestorePackage(intent, snapshot)"))
        assertTrue(conclude.contains("taskRepository.publishTaskJson(identityId, canonical"))
        assertFalse(
            "the terminal is published from the canonical row, never from the pre-merge intent",
            conclude.contains("publishTaskJson(identityId, intent"),
        )
    }

    @Test
    fun `the container gives the compensator the repository as its store and the real manifest writer`() {
        val wiring = call(container, "val cancelCompensator = CancelCompensator(")
        assertTrue(wiring.contains("store = taskRepository"))
        assertTrue(wiring.contains("manifestWriter = manifestWriter"))
        assertTrue(wiring.contains("drive = drive"))
        assertTrue(wiring.contains("remoteCancel = remoteCancelConverger"))
        // Identity comes from the join inside the store, so the compensator is not handed an account.
        assertFalse(wiring.contains("accountRepository"))
        assertFalse(wiring.contains("identityId"))
        // And the runner shares that one compensator and that one converger, rather than building a
        // second of either with a different order.
        val runner = call(container, "val taskRunner = TaskRunner(")
        assertTrue(runner.contains("compensator = cancelCompensator"))
        assertTrue(runner.contains("remoteCancel = remoteCancelConverger"))
        assertTrue(
            "the enqueue lambda must reach the same Worker the cold-start rule enqueues",
            runner.flat().contains("CancelCompensationWorker.enqueue(appContext,taskId,attempt)"),
        )
    }

    @Test
    fun `the compensator keeps the design's order and clears the marker last`() {
        // ZLQ-114 §5.4. The name cannot carry the section number: a JVM method name may not contain a dot.
        val compensator = read("core/database/src/main/java/com/superstudent/core/repository/CancelCompensator.kt")
        val pass = block(compensator, "suspend fun compensate(")
        val find = pass.indexOf("store.findAttemptWithOwner(taskId, attempt)")
        val remote = pass.indexOf("remoteCancel.ensureCanceled(sessionId)")
        val delete = pass.indexOf("drive.deleteSubtree(identityId, tmp)")
        val publish = pass.indexOf("manifestWriter.publishState(")
        val clear = pass.indexOf("store.clearCancelCleanupPending(taskId, attempt)")
        listOf(find to "the row read", remote to "the remote confirm", delete to "the tmp delete",
            publish to "the manifest publish", clear to "the marker clear").forEach { (at, what) ->
            assertTrue("$what disappeared from the compensation pass", at >= 0)
        }
        assertTrue("remote cancel before any delete", find < remote && remote < delete)
        assertTrue("manifest publish last, so it never claims a state the cleanup did not reach", delete < publish)
        assertTrue("the marker is cleared only after everything it stands for is proven", publish < clear)
        // The tmp path is derived from this row's own triple, never from a package-wide prefix.
        assertTrue(pass.contains("DrivePath.tmpDir(run.packageId, \"${'$'}{run.taskId}-${'$'}{run.attempt}\")"))
        // Identity is the package owner's from the join, not the signed-in account's.
        assertTrue(pass.contains("val identityId = found.ownerIdentityId"))
        // A row that is not a pending cancel is done, not retried — that is what makes a redelivery harmless.
        assertTrue(pass.contains("run.state != TaskState.CANCELED.name || !run.cleanupPending"))
        assertTrue(pass.contains("RemoteCancelOutcome.CONVERGED"))
    }

    @Test
    fun `the worker awaits the pass and maps its outcome onto WorkManager's`() {
        val work = block(worker, "override suspend fun doWork()")
        assertTrue(work.contains("compensate(taskId, attempt)"))
        assertTrue(work.contains("CompensationOutcome.COMPLETED -> Result.success()"))
        assertTrue(work.contains("CompensationOutcome.RETRY -> Result.retry()"))
        assertTrue(
            "a malformed input has nothing to compensate and must not retry forever",
            work.contains("if (taskId == null || attempt <= 0) return Result.success()"),
        )
        assertFalse(work.contains("GlobalScope"))
    }
}
