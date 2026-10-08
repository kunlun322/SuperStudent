package com.superstudent.app.auth

import com.superstudent.core.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four owners ZLQ-119 §4.3 names, plus §4.4's restart policy and the login/logout ordering §8.1
 * item 4 asks for — pinned as far as the JVM allows.
 *
 * Everything here is about *which call a path makes, and before what*. None of it is observable on the
 * JVM: `TaskForegroundService` is a `Service`, `LoginViewModel` needs an `AppContainer` with a Keystore
 * behind it, and `TaskRunner`'s pre-flights need a cloud. So each is read off one brace-matched block of
 * the production source, the way `CancelCompensationWiringTest` and `TaskStartOwnershipTest` do it. The
 * behaviour those calls have is proven elsewhere and not repeated here: the credential state machine by
 * `AuthSessionTest`, the exception boundary by
 * `core/network/src/test/java/com/superstudent/core/network/MissingCredentialBoundaryTest.kt`, and the
 * local convergence's real Room writes by `core/database`'s `CancelCompensationSqlTest` against the
 * exported v3 schema.
 */
class CredentialGateWiringTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    private val runner = read("app/src/main/java/com/superstudent/app/features/tasks/TaskRunner.kt")
    private val service =
        read("app/src/main/java/com/superstudent/app/features/tasks/TaskForegroundService.kt")
    private val worker = read("app/src/main/java/com/superstudent/app/features/tasks/RunResumeWorker.kt")
    private val reconciler = read("app/src/main/java/com/superstudent/app/reconcile/StartupReconciler.kt")
    private val loginViewModel =
        read("app/src/main/java/com/superstudent/app/features/auth/LoginViewModel.kt")
    private val profileViewModel =
        read("app/src/main/java/com/superstudent/app/features/profile/ProfileScreen.kt")
    private val container = read("app/src/main/java/com/superstudent/app/AppContainer.kt")
    private val mainActivity = read("app/src/main/java/com/superstudent/app/MainActivity.kt")

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
    private fun params(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker `$marker` disappeared", start >= 0)
        var depth = 0
        for (i in source.indexOf('(', start) until source.length) {
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

    /** Whitespace-stripped, so an assertion survives a reformat but not a semantic change. */
    private fun String.flat(): String = replace("\\s".toRegex(), "")

    /** Asserts [markers] occur in this order inside [haystack], and that each occurs at all. */
    private fun ordered(haystack: String, vararg markers: String) {
        var previous = -1
        var previousMarker = "<start of block>"
        markers.forEach { marker ->
            val at = haystack.indexOf(marker)
            assertTrue("`$marker` disappeared", at >= 0)
            assertTrue("`$marker` moved before `$previousMarker`", at > previous)
            previous = at
            previousMarker = marker
        }
    }

    // ---- §8.1 item 5: the local convergence makes no API call at all ----------------------------

    @Test
    fun `the sessionless convergence touches Room and nothing else, so it needs no credential`() {
        val converge = block(runner, "private suspend fun reconcileLocked(")
        // Not "does not call the cloud by accident" — the only collaborators in the whole block are the
        // repository, the recovery decision table, the in-memory submit keys and the log. There is no
        // `api` and no `drive` in scope to call, which is what makes the API count zero rather than
        // merely observed to be zero on one fixture.
        assertFalse(converge.contains("api."))
        assertFalse(converge.contains("drive."))
        assertFalse(converge.contains("manifestWriter."))
        assertTrue(converge.contains("taskRepository.listActiveWithOwner()"))
        assertTrue(converge.contains("taskRepository.convergeCanceled("))
        // And the public entry the three owners call is that block, not a second implementation.
        assertTrue(
            runner.flat().contains("suspendfunreconcileSessionlessActiveRuns():List<ActiveRunRow>=startMutex.withLock{reconcileLocked()}"),
        )
    }

    @Test
    fun `every owner runs the local convergence before it asks for a credential`() {
        // The coordinator: phase 1 in full, then the pre-flight, then phase 2.
        val pass = block(reconciler, "fun runOnce(deps: StartupDeps)")
        ordered(pass, "local.forEach", "deps.resolveAuth()", "remote.forEach")
        // The service's own resume pass.
        val resumeAll = block(service, "private fun handleResumeAll()")
        ordered(resumeAll, "reconcileSessionlessActiveRuns()", "credentialGate.requireCredential()")
        // And the handoff the worker and the login path both use. Its signature went multi-line when
        // ZLQ-120 gave it a reason, so the marker stops at the name.
        val handoff = block(service, "suspend fun reconcileAndResume(")
        ordered(handoff, "reconcileSessionlessActiveRuns()", "credentialGate.requireCredential()")
        // A gate that ran first would be indistinguishable from ZLQ-102's original symptom: no
        // credential, so no startup work, so the orphan keeps counting against admission forever.
        assertTrue(
            "the pre-flight must be able to stop only the remote half",
            resumeAll.indexOf("reconcileSessionlessActiveRuns()") < resumeAll.indexOf("return@launch"),
        )
    }

    // ---- §8.1 item 6: a sessionful resume with no PAT -------------------------------------------

    @Test
    fun `observe reads the credential before the observation loop and converges the run to AUTH_EXPIRED`() {
        val observe = block(runner, "suspend fun observe(")
        val gate = observe.indexOf("credentialGate.requireCredential()")
        val loop = observe.indexOf("while (true)")
        assertTrue(gate >= 0)
        assertTrue("the pre-flight must precede the observation loop", gate < loop)
        val preflight = observe.substring(gate, loop)
        val before = observe.substring(0, gate)
        // §2.4 inside `observe`: the local sessionless recovery is ahead of the gate and is not what
        // the gate stops.
        assertTrue(before.contains("reconcileSessionlessActiveRuns()"))
        // And nothing cloud-bound sits between the pre-flight and the loop, so the loop's first read
        // of the Session really is behind the gate. (`observe` declares local suspend helpers above
        // the loop that only the loop invokes, so raw text order across the whole function would not
        // be a meaningful assertion — this window is.)
        assertFalse(preflight.contains("api."))
        assertTrue(observe.substring(loop).contains("qcaCall { api.getSession"))
        // The terminal it writes is AUTH_EXPIRED, i.e. the state the UI already reads as
        // "re-authenticate", and the reason recorded is the enum name — never the credential.
        assertTrue(preflight.contains("TaskState.AUTH_EXPIRED"))
        assertTrue(preflight.contains("credential=\${reason.name}"))
        assertTrue(preflight.contains("return"))
        assertFalse(preflight.contains("pat"))
        assertEquals(TaskState.AUTH_EXPIRED.name, "AUTH_EXPIRED")
    }

    @Test
    fun `a credential-less resume pass stops the service with its own startId and schedules no worker`() {
        val resumeAll = block(service, "private fun handleResumeAll()")
        ordered(
            resumeAll,
            "credentialGate.requireCredential()",
            "joinAll()",
            "notifyReauthenticationRequired()",
            "stopSelfResult(resumeStartId)",
        )
        // The observers are awaited before the service stops: stopping first would leave the terminal
        // write unfinished and the row still looking resumable to the next pass.
        assertTrue(
            "the gate path must not fall through to the ungated dispatch",
            resumeAll.indexOf("stopSelfResult(resumeStartId)") < resumeAll.lastIndexOf("resumable.forEach"),
        )
        // Nothing is handed to a worker that is forbidden to retry it.
        assertFalse(resumeAll.contains("RunResumeWorker.schedule"))
        // The startId is the one this action was delivered with, captured before the dispatch.
        val start = block(service, "override fun onStartCommand(")
        ordered(start, "resumeStartId = startId", "handleResumeAll()")
        // The clickable half: one notification, the unified copy, and it opens the app.
        val notify = block(service, "private fun notifyReauthenticationRequired()")
        assertTrue(notify.contains("REAUTHENTICATION_MESSAGE"))
        assertTrue(notify.contains("PendingIntent.getActivity("))
        assertTrue(notify.contains("MainActivity::class.java"))
        assertTrue(notify.contains("setContentIntent(openLogin)"))
        assertTrue(notify.contains("NOTIFICATION_ID"))
    }

    @Test
    fun `the resume worker answers a credential failure with success, never with a backoff`() {
        val work = block(worker, "override suspend fun doWork()")
        ordered(work, "reconcileAndResume(applicationContext)", "outcome.credentialFailure", "Result.success()")
        assertTrue(work.flat().contains("if(outcome.credentialFailure!=null)returnResult.success()"))
        // A refused handoff is still transient, so the retry branch survives for it.
        assertTrue(work.flat().contains("returnif(outcome.serviceStarted)Result.success()elseResult.retry()"))
        // The reason it can tell the two apart is a field on the outcome, not an exception type.
        val outcome = params(service, "data class ResumeOutcome(")
        assertTrue(outcome.contains("val credentialFailure: CredentialFailureReason?"))
    }

    // ---- §8.1 item 7: RESUME_ALL is not redelivered ---------------------------------------------

    @Test
    fun `RESUME_ALL is non-sticky and every other action keeps redelivery`() {
        val start = block(service, "override fun onStartCommand(")
        assertTrue(
            start.flat().contains(
                "valrestartPolicy=if(intent?.action==ACTION_RESUME_ALL)START_NOT_STICKY" +
                    "elseSTART_REDELIVER_INTENT",
            ),
        )
        assertTrue(start.contains("return restartPolicy"))
        // START_NEW's redelivery is what lets a submit survive a death mid-chain, so it must not have
        // been swept up in the same change.
        assertTrue(start.contains("ACTION_START_NEW -> handleStartNew(intent)"))
        // startForeground still precedes every validation: the 5-second contract holds even for an
        // intent the service is about to reject.
        ordered(start, "startForeground(NOTIFICATION_ID", "when (intent?.action)")
    }

    @Test
    fun `a START_NEW with no credential is rejected before an attempt row or a Session exists`() {
        val submit = block(runner, "suspend fun submitNew(")
        val gate = submit.indexOf("credentialGate.requireCredential()")
        assertTrue(gate >= 0)
        assertTrue("no attempt row", gate < submit.indexOf("taskRepository.createAttempt("))
        assertTrue("no cloud call", gate < submit.indexOf("api."))
        val rejected = submit.substring(gate, submit.indexOf("val pkg ="))
        assertTrue(rejected.contains("StartOutcome.Rejected(REAUTHENTICATION_MESSAGE)"))
        // Replay is the same shape: a redelivered intent must not re-send a task message with no
        // credential to send it with.
        val replay = block(runner, "suspend fun replaySubmit(")
        assertTrue(replay.indexOf("credentialGate.requireCredential()") < replay.indexOf("api."))
        // The rejection reaches the student, because nothing in Room records a start that produced no
        // row — and the job then drains, so no long-lived FGS is left behind (§4.4).
        val startNew = block(service, "private fun handleStartNew(")
        assertTrue(startNew.contains("is StartOutcome.NotStarted -> TaskStartBus.post("))
        assertTrue(startNew.contains("onJobFinished(ref)"))
    }

    // ---- §8.1 item 4: login order, rollback, logout ---------------------------------------------

    @Test
    fun `login saves the credential first, publishes on success, and rolls both back on failure`() {
        val login = block(loginViewModel, "fun login(rawPat: String, rawUsername: String)")
        ordered(
            login,
            "credentialStore.savePat(pat)",
            "accountRepository.login(normalized)",
            "authSession.markAuthenticated(session.identityId)",
        )
        // Published before the Drive restore, which is best effort: leaving the state at `Checking`
        // while the student is already looking at their packages is what makes a later gate a surprise.
        assertTrue(
            login.indexOf("authSession.markAuthenticated(") < login.indexOf("restoreRepository.restore("),
        )
        val rollback = login.substring(login.indexOf("catch (t: Throwable)"))
        // ZLQ-138: the clear is now conditional, so the guard is part of the contract. A connection
        // failure never reached a server and nothing has judged the credential, so it survives — unless
        // an identity is still bound, in which case keeping it would sign the next cold start into the
        // previous account (§4.1).
        ordered(rollback, "LoginFailurePolicy.keepsCredential(t, identityBound)", "credentialStore.clear()")
        ordered(rollback, "credentialStore.clear()", "authSession.markLoginRolledBack()", "LoginUiState(error = describe(t),")
        // A half-written login must not leave a credential the next cold start reads as signed in.
        assertFalse(rollback.contains("markAuthenticated"))
        // ZLQ-133 §1.3: a failed login is not a logout. Both land on `SignedOut`, and the only thing
        // that keeps them apart in the log is which method the caller reached for — `LOGIN` vs
        // `LOGOUT` — so the rollback must not borrow the logout one.
        assertFalse(rollback.contains("markLoggedOut"))
    }

    @Test
    fun `the login screen never logs or renders the token it was given`() {
        assertFalse(loginViewModel.contains("Log."))
        assertFalse(loginViewModel.contains("println("))
        // The PAT travels from the field into the store and nowhere else: no UI state field carries it.
        val state = params(loginViewModel, "data class LoginUiState(")
        assertFalse(state.contains("pat"))
        val described = block(loginViewModel, "private fun describe(t: Throwable)")
        assertFalse(described.contains("pat"))
        // The store call is the one place the local variable appears as an argument.
        assertEquals(1, Regex("""savePat\(pat\)""").findAll(loginViewModel).count())
    }

    @Test
    fun `logout clears the identity and the credential and keeps the business data`() {
        val profile = block(profileViewModel, "fun logout() {")
        ordered(profile, "accountRepository.logout(identity)", "container.logout()")
        val appLogout = block(container, "fun logout() {")
        ordered(appLogout, "credentialStore.clear()", "authSession.markLoggedOut()")
        // The one call site that means "the user signed out", so it is the one that may say LOGOUT.
        assertFalse(appLogout.contains("markLoginRolledBack"))
        // §4.1: identity and business data survive a lost credential, so the UI can say who has to log
        // back in. Only an explicit logout clears the identity, and it does so through the repository.
        assertFalse(appLogout.contains("Dao"))
        assertFalse(appLogout.contains("delete"))
        val account = read("core/database/src/main/java/com/superstudent/core/repository/AccountRepository.kt")
        val repoLogout = block(account, "suspend fun logout(identityId: String)")
        assertTrue(repoLogout.contains("prefs.clearCurrentAccount()"))
        assertTrue(
            "Room rows are an identity-scoped cache and hold no secret, so logout keeps them",
            repoLogout.contains("Room rows stay"),
        )
    }

    // ---- §4.5: one copy, one route, from every surface ------------------------------------------

    @Test
    fun `a re-authentication state routes to the login page with the one unified notice`() {
        val main = block(mainActivity, "override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(main.contains("container.authSession.state.collectAsStateWithLifecycle()"))
        assertTrue(main.contains("AuthSessionState.ReauthenticationRequired"))
        assertTrue(main.contains("REAUTHENTICATION_MESSAGE"))
        ordered(main, "val reauthNotice", "LaunchedEffect(authState)", "navController.navigate(Routes.LOGIN)")
        // Guarded on a composed destination: before the NavHost exists there is nothing to navigate
        // from, and the cold-start branch below already picks LOGIN on its own.
        assertTrue(main.contains("navController.currentDestination != null"))
        // The post-login recovery callback keeps its position and its timing (ZLQ-118 constraint 6 as
        // re-scoped by ZLQ-126 §3.7): it is still the first thing the callback does.
        val callback = main.substring(main.indexOf("LoginScreen(onLoggedIn"))
        ordered(
            callback,
            "TaskForegroundService.reconcileAndResume(",
            "navController.navigate(",
        )
        assertTrue("the notice reaches the screen", callback.contains("notice = reauthNotice"))
        val screen = read("app/src/main/java/com/superstudent/app/features/auth/LoginScreen.kt").flat()
        assertTrue(screen.contains("funLoginScreen(onLoggedIn:()->Unit,"))
        // Optional with a default, so every existing caller keeps compiling unchanged.
        assertTrue(screen.contains("notice:String?=null,"))
        assertTrue(screen.contains("\"login_notice\""))
        // The callback still fires off the logged-in state and nothing else: its timing is the one
        // thing §3.7 says must not move.
        assertTrue(screen.contains("LaunchedEffect(state.loggedIn){if(state.loggedIn)onLoggedIn()}"))
    }
}
