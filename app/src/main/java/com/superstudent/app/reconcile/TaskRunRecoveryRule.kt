package com.superstudent.app.reconcile

/**
 * The generation-task half of the one startup pass, re-homed from ZLQ-106's cold-start call in
 * `MainActivity`'s identity-ready branch onto the ZLQ-113 coordinator (ZLQ-110 §3.4). Registering it
 * here is what leaves a single process-level startup reconciliation entry: the application pass and
 * the identity-ready signal both drive [StartupReconciler], and neither grows its own scan.
 *
 * Unlike [SourceFailureReconcileRule] and [ManifestProjectionReconcileRule], this rule is deliberately
 * NOT gated on [hasValidatedNetwork]. The recovery it delegates to — `TaskForegroundService
 * .reconcileAndResume` — converges the sessionless orphan rows a killed process left between
 * `createAttempt` and the sessionId write as its very first step, and that convergence is pure Room:
 * no Drive write, no cloud call. It therefore has to run on an offline cold start too. Network-gating
 * the whole rule would leave such an orphan active, still counting against the D-2 first-build
 * admission guard, and block that identity's next generation until a launch happened to be online —
 * exactly the ZLQ-102 symptom. The resume/observe half that does need the network fails gracefully
 * offline and is picked up by `RunResumeWorker` when connectivity returns.
 *
 * The recovery is injected rather than reached through a `Context` so the offline path is testable on
 * the JVM: this repo has no Robolectric or instrumented harness.
 */
class TaskRunRecoveryRule(
    private val recover: suspend () -> Unit,
) : StartupReconcileRule {

    override val name = "task-run-recovery"

    /**
     * The convergence half is pure Room, so it is owed by a cold start that has lost its credential
     * too (ZLQ-126 §2.4); the resume half runs its own pre-flight inside `TaskRunner` and converges
     * the run to `AUTH_EXPIRED` rather than being skipped.
     */
    override val requiresCredential = false

    override suspend fun reconcile() = recover()
}
