package com.superstudent.app.features.packages.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.superstudent.app.MainActivity
import com.superstudent.app.appContainer
import com.superstudent.core.repository.UploadOutcome
import com.superstudent.core.upload.SourceFailureClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs user-initiated uploads in the foreground so a backgrounded app keeps uploading (design §6.2).
 *
 * This service owns **no** business state. Every outcome is written to Room by the shared
 * [UploadExecutor] first; the notification only mirrors it, and it is removed as soon as the last
 * task of the batch completes, fails or is cancelled — no zombie "上传中" is left behind.
 */
class UploadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batches = ConcurrentHashMap<String, Batch>()
    private val lastText = ConcurrentHashMap<Int, String>()
    private var progressJob: Job? = null

    /** One package = one foreground notification and one serial queue. */
    private class Batch(val packageId: String, val identityId: String, val notificationId: Int) {
        private val queue = ArrayDeque<String>()
        private var completed = 0
        private var offered = 0
        private var current: String? = null
        var running: Job? = null

        @Synchronized
        fun offer(sourceIds: List<String>) {
            sourceIds.forEach { if (it !in queue) { queue.addLast(it); offered++ } }
        }

        @Synchronized
        fun poll(): String? = queue.removeFirstOrNull()

        @Synchronized
        fun advance() {
            completed++
        }

        /** 1-based position of the source being uploaded right now, and the batch total. */
        @Synchronized
        fun position(): Pair<Int, Int> = (completed + 1).coerceAtMost(offered.coerceAtLeast(1)) to offered

        @Synchronized
        fun markCurrent(sourceId: String?) {
            current = sourceId
        }

        @Synchronized
        fun isCurrent(sourceId: String): Boolean = current == sourceId

        @Synchronized
        fun clear() {
            queue.clear()
        }

        /**
         * Removes one source from this batch and reports whether it was the one in flight, which is
         * what tells the caller to cancel the running coroutine (ZLQ-130 §3.9 item 3).
         */
        @Synchronized
        fun drop(sourceId: String): Boolean {
            val wasCurrent = current == sourceId
            if (queue.remove(sourceId)) offered--
            if (wasCurrent) current = null
            return wasCurrent
        }

        @Synchronized
        fun hasQueued(): Boolean = queue.isNotEmpty()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageId = intent?.getStringExtra(EXTRA_PACKAGE)
        // `ContextCompat.startForegroundService` obliges this service to reach `startForeground`
        // within a few seconds on *every* start, including the ones that find nothing to do: an
        // early return instead kills the process with ForegroundServiceDidNotStartInTimeException,
        // which is what a cancel arriving after the batch finished used to do (ZLQ-91 P0-8).
        holdForeground(packageId, intent?.getStringExtra(EXTRA_ACTION) == ACTION_CANCEL)
        if (packageId.isNullOrBlank()) {
            releaseForeground()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val batch = batches[packageId]

        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_CANCEL) {
            val only = intent.getStringExtra(EXTRA_SOURCE)
            if (only.isNullOrBlank()) {
                batch?.clear()
                // Cancelling the coroutine makes the in-flight attempt record USER_CANCELLED.
                batch?.running?.cancel()
                if (batch != null) batch.running = null
                settle(packageId)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            // ZLQ-130 §3.9 item 3: a delete arms a tombstone for ONE source while the rest of the
            // package is still queued. Cancelling the whole batch would drop work the student never
            // asked to drop; cancelling nothing would leave this queue PUTting a file that is being
            // deleted.
            if (batch != null) {
                if (batch.drop(only)) {
                    batch.running?.cancel()
                    batch.running = null
                    if (batch.hasQueued()) {
                        drain(batch, startId)
                        return START_NOT_STICKY
                    }
                }
                if (!batch.hasQueued() && batch.running?.isActive != true) settle(packageId)
            } else {
                settle(packageId)
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val identityId = intent.getStringExtra(EXTRA_IDENTITY)
        val sourceIds = intent.getStringArrayListExtra(EXTRA_SOURCES).orEmpty()
        if (identityId.isNullOrBlank() || sourceIds.isEmpty()) {
            if (batch == null) {
                releaseForeground()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }

        val active = batch ?: Batch(packageId, identityId, notificationIdFor(packageId)).also {
            batches[packageId] = it
        }
        active.offer(sourceIds)
        ensureProgressMirror()
        ServiceCompat.startForeground(
            this,
            active.notificationId,
            buildNotification(active.notificationId, "准备上传资料…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        drain(active, startId)
        return START_NOT_STICKY
    }

    /** Satisfies the `startForeground` obligation of one `startForegroundService` call. */
    private fun holdForeground(packageId: String?, cancelling: Boolean) {
        val id = packageId?.takeIf { it.isNotBlank() }?.let { notificationIdFor(it) }
            ?: ABORT_NOTIFICATION_ID
        ensureChannel(this)
        runCatching {
            ServiceCompat.startForeground(
                this,
                id,
                buildNotification(id, if (cancelling) "正在取消上传…" else "准备上传资料…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    /** Drops the notification [holdForeground] posted when there turned out to be nothing to do. */
    private fun releaseForeground() {
        getSystemService(NotificationManager::class.java)?.cancel(ABORT_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun drain(batch: Batch, startId: Int) {
        if (batch.running?.isActive == true) return
        batch.running = scope.launch {
            val container = applicationContext.appContainer
            while (true) {
                val sourceId = batch.poll() ?: break
                val (position, total) = batch.position()
                batch.markCurrent(sourceId)
                val outcome = runCatching { container.uploadExecutor.upload(batch.identityId, sourceId) }
                    .getOrElse { UploadOutcome.Failed(SourceFailureClassifier.classify(it)) }
                batch.markCurrent(null)
                batch.advance()
                if (outcome is UploadOutcome.Failed && outcome.failure.retryable) {
                    // Hand the remaining backoff to WorkManager so it survives this service dying.
                    SourceUploadWorker.enqueue(applicationContext, sourceId)
                }
                mirror(batch, summary(outcome, position, total))
            }
            // Every task of this batch has completed, failed or been cancelled.
            getSystemService(NotificationManager::class.java)?.cancel(batch.notificationId)
            lastText.remove(batch.notificationId)
            batches.remove(batch.packageId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    /** A cancel with nothing in flight still has to clear the notification and stop the service. */
    private fun settle(packageId: String) {
        val batch = batches.remove(packageId)
        val id = batch?.notificationId ?: notificationIdFor(packageId)
        getSystemService(NotificationManager::class.java)?.cancel(id)
        lastText.remove(id)
        if (batches.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun summary(outcome: UploadOutcome, done: Int, total: Int): String = when (outcome) {
        UploadOutcome.Uploaded -> if (done >= total) "已上传 $total 份资料" else "已上传 $done/$total"
        UploadOutcome.Skipped -> "已处理 $done/$total"
        // The row is not this process's to upload right now; the coordinator owns its next deadline.
        UploadOutcome.Deferred -> "已转后台重试 $done/$total"
        is UploadOutcome.Failed -> "上传失败：${outcome.failure.message}"
        is UploadOutcome.LocalOnly -> "需重新选择文件：${outcome.failure.message}"
    }

    /** One shared collector drives every batch's progress line; Room stays the state source. */
    private fun ensureProgressMirror() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            UploadProgressBus.ticks.collect { ticks ->
                batches.values.forEach { batch ->
                    val active = ticks.entries.firstOrNull { batch.isCurrent(it.key) } ?: return@forEach
                    val (position, total) = batch.position()
                    mirror(
                        batch,
                        "正在上传 $position/$total · ${active.value.displayName} · ${active.value.percent}%",
                    )
                }
            }
        }
    }

    private fun mirror(batch: Batch, text: String) {
        if (lastText[batch.notificationId] == text) return
        lastText[batch.notificationId] = text
        getSystemService(NotificationManager::class.java)
            ?.notify(batch.notificationId, buildNotification(batch.notificationId, text))
    }

    private fun buildNotification(id: Int, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            id,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("超级学生")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ss_upload"

        /** Used when a start carries no package at all, so no batch id can be derived. */
        private const val ABORT_NOTIFICATION_ID = 999
        const val EXTRA_IDENTITY = "identity_id"
        const val EXTRA_PACKAGE = "package_id"
        const val EXTRA_SOURCES = "source_ids"
        const val EXTRA_ACTION = "action"
        const val EXTRA_SOURCE = "source_id"
        const val ACTION_CANCEL = "cancel"

        /** One stable progress notification per package, so batches cannot fight over an id. */
        fun notificationIdFor(packageId: String): Int = 1000 + Math.floorMod(packageId.hashCode(), 9000)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "资料上传", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "学习资料上传进度"; setShowBadge(false) },
            )
        }

        /** User-initiated: run these sources now, in the foreground. */
        fun start(context: Context, identityId: String, packageId: String, sourceIds: List<String>) {
            if (sourceIds.isEmpty()) return
            ensureChannel(context)
            val intent = Intent(context, UploadForegroundService::class.java)
                .putExtra(EXTRA_IDENTITY, identityId)
                .putExtra(EXTRA_PACKAGE, packageId)
                .putExtra(EXTRA_SOURCES, ArrayList(sourceIds))
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, packageId: String) {
            val intent = Intent(context, UploadForegroundService::class.java)
                .putExtra(EXTRA_PACKAGE, packageId)
                .putExtra(EXTRA_ACTION, ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops one source without disturbing the rest of its package's batch (ZLQ-130 §3.9 item 3).
         * Called by the delete path, which arms a tombstone for exactly this source.
         */
        fun cancelSource(context: Context, packageId: String, sourceId: String) {
            ensureChannel(context)
            val intent = Intent(context, UploadForegroundService::class.java)
                .putExtra(EXTRA_PACKAGE, packageId)
                .putExtra(EXTRA_SOURCE, sourceId)
                .putExtra(EXTRA_ACTION, ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
