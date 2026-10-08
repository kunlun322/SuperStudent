package com.superstudent.app

import android.content.Context
import com.superstudent.app.auth.AuthSession
import com.superstudent.app.auth.AuthSessionState
import com.superstudent.app.auth.CredentialGate
import com.superstudent.app.features.auth.LoginDiagnostics
import com.superstudent.app.features.packages.upload.SourceDeleteWorker
import com.superstudent.app.features.packages.upload.SourceRecoveryWorker
import com.superstudent.app.features.packages.upload.SourceUploadRecoveryCoordinator
import com.superstudent.app.features.packages.upload.SourceUploadWorker
import com.superstudent.app.features.packages.upload.UploadExecutor
import com.superstudent.app.features.tasks.CancelCompensationWorker
import com.superstudent.app.features.tasks.QmindDeleter
import com.superstudent.app.features.tasks.ResumeAllCoordinator
import com.superstudent.app.features.tasks.TaskRunner
import com.superstudent.app.reconcile.hasValidatedNetwork
import com.superstudent.core.database.Prefs
import com.superstudent.core.database.SsDatabase
import com.superstudent.core.drive.DriveRepository
import com.superstudent.core.network.NetworkFactory
import com.superstudent.core.network.PatProvider
import com.superstudent.core.network.PresignedTransfer
import com.superstudent.core.network.RemoteCancelConverger
import com.superstudent.core.network.SseWatcher
import com.superstudent.core.repository.AccountRepository
import com.superstudent.core.repository.CancelCompensator
import com.superstudent.core.repository.DriveBinaryCache
import com.superstudent.core.repository.IndexStore
import com.superstudent.core.repository.ManifestWriter
import com.superstudent.core.repository.PackageRepository
import com.superstudent.core.repository.ProgressRepository
import com.superstudent.core.repository.ProgressStore
import com.superstudent.core.repository.ProfileRepository
import com.superstudent.core.repository.RestoreRepository
import com.superstudent.core.repository.ResultsRepository
import com.superstudent.core.repository.TaskRepository
import com.superstudent.core.security.CredentialFailureReason
import com.superstudent.core.security.CredentialStore
import com.superstudent.core.upload.ProcessLeaseRegistry
import java.io.File

/** Fixed QCA resources (design §0). Never contains a PAT. */
object QcaConfig {
    const val BASE_URL = "https://api.qoder.com/"
    // Replace with the Template ID you create on Qoder Cloud Agents (see docs/build-and-run.md).
    const val TEMPLATE_ID = "tmpl_REPLACE_WITH_YOUR_TEMPLATE_ID"
    const val APP_NAME = "superstudent-android"
}

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val credentialStore = CredentialStore(appContext)

    val patProvider = PatProvider { credentialStore.loadPat() }

    /**
     * OS-level lease ownership (design §3.1), built before the database on purpose: it holds no Room
     * handle, so a process whose migration failed can still say who it is, and opening the schema can
     * never be blocked behind a filesystem lock.
     *
     * Under `noBackupFilesDir` because a lock file is process-local truth. Restoring one onto another
     * device would hand that device a lock nobody holds, which reads as "the owner is alive" forever.
     */
    val processLeaseRegistry = ProcessLeaseRegistry(File(appContext.noBackupFilesDir, "upload-leases"))

    val db: SsDatabase = SsDatabase.get(appContext)
    val prefs = Prefs(appContext)

    val apiClient = NetworkFactory.apiClient(patProvider, BuildConfig.DEBUG)
    val transferClient = NetworkFactory.transferClient(BuildConfig.DEBUG)
    val sseClient = NetworkFactory.sseClient(patProvider)

    val api = NetworkFactory.retrofit(apiClient, QcaConfig.BASE_URL).create(
        com.superstudent.core.network.QcaApi::class.java
    )

    val drive = DriveRepository(api, PresignedTransfer(transferClient))

    val indexStore = IndexStore(drive)
    val progressStore = ProgressStore(drive)

    /** The one write path for `package.json` / `index.json`; uploads and tasks share its lock. */
    val manifestWriter = ManifestWriter(
        drive = drive,
        packageDao = db.packageDao(),
        sourceDao = db.sourceDao(),
        indexStore = indexStore,
    )

    val accountRepository = AccountRepository(api, drive, db.accountDao(), prefs)

    /**
     * The one authentication state every owner reads (ZLQ-119 §4.2). Application-scoped, so a
     * credential that dies mid-session is published once and seen by every screen, instead of being
     * rediscovered as an exception by whichever call happened to be in flight.
     */
    val authSession = AuthSession(
        currentIdentityId = { accountRepository.currentIdentityId() },
        inspect = { credentialStore.inspect() },
        discardCredential = { credentialStore.clear() },
    )

    /**
     * Why a login attempt failed, in the log (ZLQ-138 §5.2). Lives here rather than in the ViewModel
     * because that file is scanned for `android.util.Log` — it holds the PAT — and because the interceptor
     * line says one call failed, not that the login did.
     */
    val loginDiagnostics = LoginDiagnostics()

    /**
     * Pre-flight for the cloud-facing entry points (§4.3). Only "signed in with a credential that
     * actually decrypts" is let through: a signed-out or still-checking installation has nothing to
     * authenticate with either, and discovering that inside OkHttp is the crash this replaces.
     */
    val credentialGate = CredentialGate {
        when (val resolved = authSession.resolve()) {
            is AuthSessionState.Authenticated -> null
            is AuthSessionState.ReauthenticationRequired -> resolved.reason
            else -> CredentialFailureReason.MISSING_FILE
        }
    }

    /**
     * The one merge point for every `RESUME_ALL` trigger (ZLQ-120 §5.2). Application-scoped, because
     * the triggers are not: the application pass, the post-login callback and the network-back worker
     * all ask for the same recovery, and only a guard they share can give the system one intent
     * instead of three.
     */
    val resumeAll = ResumeAllCoordinator()
    val packageRepository = PackageRepository(
        drive = drive,
        packageDao = db.packageDao(),
        sourceDao = db.sourceDao(),
        manifestWriter = manifestWriter,
        credentialAvailable = { patProvider.currentPat() != null },
        leases = processLeaseRegistry,
    )

    /**
     * The one place that decides whether an `UPLOADING` row may be taken from its owner (ZLQ-132
     * §3.2/§3.4). Application-scoped, because its triggers are not: the startup rule, the post-login
     * callback, the network-back worker, an upload outcome and a page becoming visible all ask for the
     * same convergence, and only a guard they share can give them one pass instead of five.
     */
    val sourceRecovery = SourceUploadRecoveryCoordinator(
        listCandidates = { now -> packageRepository.listRecoveryCandidates(now) },
        releaseOrphan = { sourceId, now -> packageRepository.interruptOrphan(sourceId, now) },
        requestStandDown = { sourceId, now -> packageRepository.requestInterrupt(sourceId, now) },
        listResumable = { now -> packageRepository.listResumable(now) },
        nextDeadline = { packageRepository.nextRecoveryDeadline() },
        leases = processLeaseRegistry,
        // Gates enqueuing only. The local convergence is not gated: §3.6 makes liveness a pure local
        // rule, so an offline cold start still owes it.
        canUpload = { patProvider.currentPat() != null && hasValidatedNetwork(appContext) },
        enqueueUpload = { sourceId -> SourceUploadWorker.enqueue(appContext, sourceId) },
        enqueueDelete = { sourceId -> SourceDeleteWorker.enqueue(appContext, sourceId) },
        armDeadline = { delayMillis -> SourceRecoveryWorker.arm(appContext, delayMillis) },
    )
    val taskRepository = TaskRepository(drive, db, db.taskRunDao())
    val progressRepository = ProgressRepository(
        drive = drive,
        cardDao = db.flashcardProgressDao(),
        exerciseDao = db.exerciseProgressDao(),
        progressStore = progressStore,
    )
    val resultsRepository = ResultsRepository(drive, db.artifactDao(), db)
    val profileRepository = ProfileRepository(drive)
    val binaryCache = DriveBinaryCache(appContext, drive)
    val restoreRepository = RestoreRepository(
        drive = drive,
        db = db,
        packageDao = db.packageDao(),
        sourceDao = db.sourceDao(),
        taskDao = db.taskRunDao(),
        artifactDao = db.artifactDao(),
        cardDao = db.flashcardProgressDao(),
        exerciseDao = db.exerciseProgressDao(),
    )

    val sseWatcher = SseWatcher(sseClient)

    /** Shared by the upload foreground service and the WorkManager retry worker. */
    val uploadExecutor = UploadExecutor(appContext, packageRepository)

    val qmindDeleter = QmindDeleter(
        api = api,
        patProvider = patProvider,
        profileRepository = profileRepository,
        templateId = QcaConfig.TEMPLATE_ID,
    )

    /** Shared by TaskRunner's foreground cancel and the compensation Worker (ZLQ-114 §5.3/§5.4). */
    val remoteCancelConverger = RemoteCancelConverger(api)

    /** One instance, two callers: whichever finishes the compensation first clears the marker. */
    val cancelCompensator = CancelCompensator(
        store = taskRepository,
        drive = drive,
        manifestWriter = manifestWriter,
        remoteCancel = remoteCancelConverger,
    )

    val taskRunner = TaskRunner(
        api = api,
        drive = drive,
        sseWatcher = sseWatcher,
        patProvider = patProvider,
        credentialGate = credentialGate,
        onCredentialRejected = { authSession.reportRemoteRejected() },
        taskRepository = taskRepository,
        packageRepository = packageRepository,
        manifestWriter = manifestWriter,
        resultsRepository = resultsRepository,
        profileRepository = profileRepository,
        prefs = prefs,
        templateId = QcaConfig.TEMPLATE_ID,
        remoteCancel = remoteCancelConverger,
        compensator = cancelCompensator,
        enqueueCancelCompensation = { taskId, attempt ->
            CancelCompensationWorker.enqueue(appContext, taskId, attempt)
        },
    )

    fun hasPat(): Boolean = credentialStore.hasPat()

    fun logout() {
        credentialStore.clear()
        authSession.markLoggedOut()
    }
}
