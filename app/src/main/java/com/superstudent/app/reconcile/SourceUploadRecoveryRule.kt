package com.superstudent.app.reconcile

import com.superstudent.app.features.packages.upload.SourceUploadRecoveryCoordinator
import com.superstudent.core.upload.SourceRecoveryTrigger

/**
 * The `PROCESS_START` trigger of design §3.4, as one rule of the existing startup pass.
 *
 * This deliberately replaces the independent `SourceUploadSweeper.resume` call that used to sit
 * beside `StartupReconciler.runOnce` in `SsApplication`. Two startup scans of the same table was the
 * shape ZLQ-117 closed off: they could read the same rows and each act on them, and neither knew the
 * other existed. There is still exactly one `runOnce` call site, and this is a rule inside it.
 *
 * [requiresCredential] is `false`, so the pass runs it in phase 1, ungated. That is the point of
 * §3.6: deciding that a lease owner is gone, and writing `interrupt_requested_at` or releasing an
 * orphan, is pure local work — no identity, no PAT, no network. Gating it behind the credential
 * pre-flight is what would turn an offline cold start into a row stuck in `UPLOADING` with nothing but
 * 删除 to offer, which is the defect this batch exists to fix.
 */
class SourceUploadRecoveryRule(
    private val coordinator: SourceUploadRecoveryCoordinator,
) : StartupReconcileRule {

    override val name = "source-upload-recovery"

    override val requiresCredential = false

    override suspend fun reconcile() {
        coordinator.request(SourceRecoveryTrigger.PROCESS_START)
    }
}
