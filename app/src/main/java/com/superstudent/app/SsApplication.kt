package com.superstudent.app

import android.app.Application
import com.superstudent.app.features.packages.upload.UploadForegroundService
import com.superstudent.app.features.tasks.CancelCompensationWorker
import com.superstudent.app.features.tasks.ResumeReason
import com.superstudent.app.features.tasks.TaskForegroundService
import com.superstudent.app.reconcile.CanceledRunReconcileRule
import com.superstudent.app.reconcile.ManifestProjectionReconcileRule
import com.superstudent.app.reconcile.SourceFailureReconcileRule
import com.superstudent.app.reconcile.SourceUploadRecoveryRule
import com.superstudent.app.reconcile.StartupReconciler
import com.superstudent.app.reconcile.TaskRunRecoveryRule
import com.superstudent.core.database.DatabaseBootstrapState
import com.superstudent.core.database.SsDatabase

class SsApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        UploadForegroundService.ensureChannel(this)
        // Opened before the container on purpose: `AppContainer` reaches `db.packageDao()` while it is
        // being built, so a migration that throws would take the whole process down before anything
        // could say why. `DatabaseBootstrapState` holds no Room handle, which is what lets the shell
        // still route to the blocking page when the database is the thing that failed (ZLQ-132 §3.7).
        if (SsDatabase.bootstrap(this) is DatabaseBootstrapState.Bootstrap.Failed) return
        container = AppContainer(this)
        // The one startup reconciliation pass (ZLQ-110 §3.4). Rules share this single entry point; a
        // second full-table scan must register here instead of scheduling its own. Each rule keeps its
        // own DAO condition and state machine — ZLQ-106's generation-task orphan rule registers here
        // too, and must not grow a second coordinator.
        //
        // ZLQ-130's upload-lease recovery is one of those rules, and it replaces the independent
        // `SourceUploadSweeper.resume` call that used to sit here beside `runOnce`. Two startup scans
        // of `source_asset` was the shape ZLQ-117 closed off.
        StartupReconciler.register(SourceUploadRecoveryRule(container.sourceRecovery))
        StartupReconciler.register(SourceFailureReconcileRule(this, container.packageRepository))
        StartupReconciler.register(
            ManifestProjectionReconcileRule(
                context = this,
                accountRepository = container.accountRepository,
                packageRepository = container.packageRepository,
                manifestWriter = container.manifestWriter,
            )
        )
        // ZLQ-106's generation-task orphan recovery, re-homed onto this same coordinator instead of a
        // second cold-start call from MainActivity's identity-ready branch. Not network-gated: its
        // first step is the pure-Room convergence that must also run on an offline cold start.
        StartupReconciler.register(
            TaskRunRecoveryRule {
                TaskForegroundService.reconcileAndResume(this, ResumeReason.PROCESS_START)
            }
        )
        // ZLQ-114 §5.5: the canceled-run convergence, on this same coordinator rather than a second
        // cold-start entry. Adjacent to TaskRunRecoveryRule because the two are siblings — both are
        // pure-Room first and both must run on an offline cold start. Registering it here is what
        // keeps one process-level startup reconciliation pass.
        StartupReconciler.register(
            CanceledRunReconcileRule(
                store = container.taskRepository,
                enqueue = { taskId, attempt ->
                    CancelCompensationWorker.enqueue(this, taskId, attempt)
                },
            )
        )
        StartupReconciler.runOnce(this)
    }
}

val Application.ssContainer: AppContainer
    get() = (this as SsApplication).container
