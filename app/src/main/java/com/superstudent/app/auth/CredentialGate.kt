package com.superstudent.app.auth

import com.superstudent.core.security.CredentialFailureReason

/**
 * The pre-flight every cloud-facing entry point asks before it reaches for the network
 * (ZLQ-119 design §4.3). Returns null when the call may be made, or the reason it may not.
 *
 * It gates remote and session-bearing work only. Local sessionless convergence and the `CANCELED`
 * compensation have to stay reachable with no credential and no network at all (ZLQ-126 §2.4), so
 * neither `TaskRunner.reconcileSessionlessActiveRuns` nor `CanceledRunReconcileRule` consults it.
 */
fun interface CredentialGate {
    suspend fun requireCredential(): CredentialFailureReason?
}
