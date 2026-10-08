package com.superstudent.app.reconcile

import android.content.Context
import com.superstudent.app.AppContainer
import com.superstudent.app.appContainer
import com.superstudent.app.auth.AuthSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One repair pass over records a previous process left in a state the student cannot act on.
 *
 * A rule must be repeatable and must not assume it is the only one running: it owns its DAO condition
 * and its state machine, and nothing else.
 */
interface StartupReconcileRule {
    /** Identifies the rule in logs. */
    val name: String

    /**
     * Whether the rule's work needs a decryptable credential (ZLQ-119 §2.4).
     *
     * Defaults to `true`, because a rule that reaches Drive or QCA is the common case and forgetting
     * to declare it must fail towards "gated", not towards "runs with no PAT". A rule that overrides
     * this to `false` is asserting that everything it does is pure Room and therefore still owed by a
     * cold start that has lost its credential — gating those is what turned a missing PAT into a
     * permanently blocked first build (ZLQ-102's symptom).
     */
    val requiresCredential: Boolean get() = true

    suspend fun reconcile()
}

/**
 * Everything one pass reads from the app besides the rules themselves. Split out so the pass can be
 * driven through its real entry point on the JVM: this repo has no Robolectric or instrumented
 * harness, so an entry-level test injects these two and registers the real rule classes.
 */
class StartupDeps(
    val currentIdentityId: suspend () -> String?,
    val resolveAuth: suspend () -> AuthSessionState,
) {
    companion object {
        fun from(container: AppContainer) = StartupDeps(
            currentIdentityId = { container.accountRepository.currentIdentityId() },
            resolveAuth = { container.authSession.resolve() },
        )
    }
}

/**
 * The single app-start reconciliation entry point (ZLQ-110 §3.4).
 *
 * Rules register here and share one trigger, one single-flight guard and one defer-on-failure policy.
 * There must never be a second full-table startup scan alongside this: ZLQ-106's orphan-run rule plugs
 * into this same coordinator rather than bringing its own.
 *
 * Nothing is scheduled and nothing blocks a screen — a rule that cannot run now (no identity, no
 * credential, offline, Drive unreachable) is simply left for the next launch, which is why rules must
 * be safe to re-run.
 *
 * One pass runs in two phases, in this order and no other (ZLQ-126 §2.4/§2.5):
 *
 *  1. every rule that declares [StartupReconcileRule.requiresCredential] `false`, in registration
 *     order — pure-Room convergence, ungated, because it is exactly what an offline credential-less
 *     cold start still owes;
 *  2. the credential pre-flight, and only if it passes, the remaining rules in registration order.
 *
 * A failed pre-flight stops the pass at that point and publishes the session state; the phase-1 rules
 * that already ran are NOT rolled back, and the pass is handed back so a later call — typically right
 * after a login — gets another chance at phase 2.
 */
object StartupReconciler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val rules = CopyOnWriteArrayList<StartupReconcileRule>()
    private val done = AtomicBoolean(false)

    /** The pass in flight, so a JVM test can await it instead of racing the dispatcher. */
    @Volatile
    private var pass: Job? = null

    fun register(rule: StartupReconcileRule) {
        rules.addIfAbsent(rule)
    }

    /**
     * Runs every registered rule at most once per process. Safe to call from several places: the
     * application start and each identity-ready signal both call it, and whichever finds an identity
     * first does the pass.
     */
    fun runOnce(context: Context) {
        runOnce(StartupDeps.from(context.applicationContext.appContainer))
    }

    fun runOnce(deps: StartupDeps) {
        if (!done.compareAndSet(false, true)) return
        pass = scope.launch {
            mutex.withLock {
                // Without an identity there is no Drive to talk to and no rows of this user's to
                // repair. Hand the pass back so the next call — typically right after a login — gets it.
                if (deps.currentIdentityId() == null) {
                    done.set(false)
                    return@withLock
                }
                val (local, remote) = rules.toList().partition { !it.requiresCredential }
                local.forEach { rule -> runCatching { rule.reconcile() } }
                // Resolving publishes the state transition itself, so a UI already on screen sees
                // `ReauthenticationRequired` without this coordinator knowing anything about screens.
                if (deps.resolveAuth() !is AuthSessionState.Authenticated) {
                    done.set(false)
                    return@withLock
                }
                remote.forEach { rule -> runCatching { rule.reconcile() } }
            }
        }
    }

    /** Test-only: clears the once-per-process latch and the registry between cases. */
    internal fun resetForTest() {
        done.set(false)
        rules.clear()
    }

    /**
     * Test-only: blocks until the pass in flight has finished.
     *
     * The pass runs on [Dispatchers.IO], so awaiting the mutex instead would be a race — the caller
     * could acquire it before the pass ever started and then assert on an untouched database.
     */
    internal fun awaitPassForTest() {
        runBlocking { pass?.join() }
    }
}
