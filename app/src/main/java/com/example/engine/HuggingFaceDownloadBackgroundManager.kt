package com.example.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs user-started GGUF downloads independently from the Activity/ViewModel lifecycle through
 * one dataSync foreground service on every supported Android version.
 */
object HuggingFaceDownloadBackgroundManager {
    const val ACTION_PROGRESS = "com.example.llmlab.MODEL_DOWNLOAD_PROGRESS"
    const val ACTION_COMPLETED = "com.example.llmlab.MODEL_DOWNLOAD_COMPLETED"
    const val ACTION_FAILED = "com.example.llmlab.MODEL_DOWNLOAD_FAILED"

    const val EXTRA_MODEL_ID = "model_id"
    const val EXTRA_DOWNLOADED = "downloaded_bytes"
    const val EXTRA_TOTAL = "total_bytes"
    const val EXTRA_ERROR = "error_message"
    const val EXTRA_MODEL_IDS = "model_ids"
    const val ACTION_CANCEL = "com.example.llmlab.CANCEL_BACKGROUND_DOWNLOAD"

    private const val PREFS = "huggingface_background_downloads"
    private const val KEY_PENDING_MODEL_IDS = "pending_model_ids"

    fun enqueue(context: Context, models: List<HuggingFaceModel>) {
        val app = context.applicationContext
        if (models.isEmpty()) return

        // Start directly from the visible, user-initiated download action. This avoids the
        // Android 14+ JobScheduler network-constraint permission path entirely.
        val intent = Intent(app, HuggingFaceModelDownloadForegroundService::class.java)
            .putStringArrayListExtra(EXTRA_MODEL_IDS, ArrayList(models.map { it.id }))
        ContextCompat.startForegroundService(app, intent)
    }

    suspend fun download(
        context: Context,
        model: HuggingFaceModel,
        onProgress: (Long, Long) -> Unit
    ): File {
        val app = context.applicationContext

        // Start the foreground service synchronously on the main thread. The call originates
        // from the visible Download action, so Android can reliably attribute it to the user.
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val receiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
                lateinit var receiver: BroadcastReceiver

                fun unregisterReceiver() {
                    if (receiverRegistered.compareAndSet(true, false)) {
                        runCatching { app.unregisterReceiver(receiver) }
                    }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.getStringExtra(EXTRA_MODEL_ID) != model.id || !continuation.isActive) return

                        when (intent.action) {
                            ACTION_PROGRESS -> {
                                try {
                                    onProgress(
                                        intent.getLongExtra(EXTRA_DOWNLOADED, 0L),
                                        intent.getLongExtra(EXTRA_TOTAL, model.estimatedSizeBytes)
                                    )
                                } catch (error: Throwable) {
                                    unregisterReceiver()
                                    val cancellation = error as? CancellationException
                                        ?: CancellationException("Download-Fortschritt wurde abgebrochen.").apply {
                                            initCause(error)
                                        }
                                    continuation.cancel(cancellation)
                                }
                            }

                            ACTION_COMPLETED -> {
                                unregisterReceiver()
                                val file = File(app.filesDir, "models/${model.filename}")
                                if (file.isFile && file.length() > 0L) {
                                    continuation.resume(file)
                                } else {
                                    continuation.resumeWithException(
                                        IOException("Download für ${model.filename} wurde ohne fertige Datei gemeldet.")
                                    )
                                }
                            }

                            ACTION_FAILED -> {
                                unregisterReceiver()
                                continuation.resumeWithException(
                                    IOException(
                                        intent.getStringExtra(EXTRA_ERROR)
                                            ?: "Download für ${model.filename} fehlgeschlagen."
                                    )
                                )
                            }
                        }
                    }
                }

                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter().apply {
                        addAction(ACTION_PROGRESS)
                        addAction(ACTION_COMPLETED)
                        addAction(ACTION_FAILED)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered.set(true)

                continuation.invokeOnCancellation {
                    unregisterReceiver()
                    cancel(app, model.id)
                }

                if (!continuation.isActive) {
                    unregisterReceiver()
                    return@suspendCancellableCoroutine
                }

                try {
                    enqueue(app, listOf(model))
                } catch (error: Throwable) {
                    unregisterReceiver()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    fun cancel(context: Context, modelId: String) {
        val app = context.applicationContext
        app.startService(
            Intent(app, HuggingFaceModelDownloadForegroundService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_MODEL_ID, modelId)
        )
    }

    internal fun broadcastProgress(context: Context, model: HuggingFaceModel, downloaded: Long, total: Long) {
        context.sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_DOWNLOADED, downloaded)
                .putExtra(EXTRA_TOTAL, total)
        )
    }

    internal fun broadcastCompleted(context: Context, model: HuggingFaceModel) {
        context.sendBroadcast(
            Intent(ACTION_COMPLETED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MODEL_ID, model.id)
        )
    }

    internal fun broadcastFailed(context: Context, model: HuggingFaceModel, message: String) {
        context.sendBroadcast(
            Intent(ACTION_FAILED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_ERROR, message)
        )
    }

    internal fun pendingModelIds(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PENDING_MODEL_IDS, emptySet()).orEmpty().toMutableSet()
    }

    internal fun savePendingModelIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PENDING_MODEL_IDS, ids).apply()
    }
}

/** User-started model downloads on all supported Android versions. */
class HuggingFaceModelDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadLock = Any()

    override fun onCreate() {
        super.onCreate()
        DownloadNotification.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HuggingFaceDownloadBackgroundManager.ACTION_CANCEL) {
            val modelId = intent.getStringExtra(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_ID)
            if (modelId != null) {
                val model = HuggingFaceModelCatalog.find(modelId) ?: return START_NOT_STICKY
                synchronized(downloadLock) {
                    val pending = HuggingFaceDownloadBackgroundManager.pendingModelIds(this)
                    pending.remove(modelId)
                    HuggingFaceDownloadBackgroundManager.savePendingModelIds(this, pending)
                    activeDownloadJobs[modelId]?.cancel(CancellationException(USER_CANCEL_REASON))
                }
                HuggingFaceDownloadBackgroundManager.broadcastFailed(
                    this,
                    model,
                    "Download wurde vom Nutzer abgebrochen. Der bisherige Fortschritt bleibt erhalten."
                )
            }
            if (HuggingFaceDownloadBackgroundManager.pendingModelIds(this).isEmpty()) {
                processor?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        synchronized(downloadLock) {
            val pending = HuggingFaceDownloadBackgroundManager.pendingModelIds(this)
            intent?.getStringArrayListExtra(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_IDS)
                ?.filter { HuggingFaceModelCatalog.find(it) != null }
                ?.forEach { pending += it }
            HuggingFaceDownloadBackgroundManager.savePendingModelIds(this, pending)
        }

        startForeground(
            NOTIFICATION_ID,
            DownloadNotification.serviceNotification(this, "Modell-Downloads werden vorbereitet…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()
        if (processor?.isActive != true) processor = serviceScope.launch { processPendingDownloads() }
        return START_STICKY
    }

    private suspend fun processPendingDownloads() {
        while (true) {
            val batch = HuggingFaceDownloadBackgroundManager.pendingModelIds(this).take(2)
            if (batch.isEmpty()) break

            coroutineScope {
                batch.map { modelId ->
                    async {
                        val model = HuggingFaceModelCatalog.find(modelId) ?: return@async
                        val ownJob = currentCoroutineContext()[Job]
                            ?: throw IllegalStateException("Download-Coroutine ohne Job-Kontext.")
                        // Registration and queue removal are synchronized so a cancel action
                        // cannot slip between checking the queue and registering the active job.
                        val shouldRun = synchronized(downloadLock) {
                            val pending = HuggingFaceDownloadBackgroundManager.pendingModelIds(
                                this@HuggingFaceModelDownloadForegroundService
                            )
                            if (modelId !in pending) {
                                false
                            } else {
                                activeDownloadJobs[modelId] = ownJob
                                true
                            }
                        }
                        if (!shouldRun) return@async

                        var finished = false
                        try {
                            HuggingFaceModelDownloader(applicationContext).download(model) { downloaded, total ->
                                val progress = if (total > 0L) (downloaded * 100L / total).toInt().coerceIn(0, 100) else 0
                                val text = "${model.title}: $progress%"
                                getSystemService(NotificationManager::class.java).notify(
                                    NOTIFICATION_ID,
                                    DownloadNotification.serviceNotification(this@HuggingFaceModelDownloadForegroundService, text, progress)
                                )
                                HuggingFaceDownloadBackgroundManager.broadcastProgress(applicationContext, model, downloaded, total)
                            }
                            HuggingFaceDownloadBackgroundManager.broadcastCompleted(applicationContext, model)
                            finished = true
                        } catch (cancelled: CancellationException) {
                            // User cancellation is isolated to this model. System/service cancellation
                            // still propagates so the resumable .part file can be resumed later.
                            if (cancelled.message == USER_CANCEL_REASON) {
                                finished = true
                            } else {
                                throw cancelled
                            }
                        } catch (error: Throwable) {
                            HuggingFaceDownloadBackgroundManager.broadcastFailed(
                                applicationContext, model, error.message ?: error.javaClass.simpleName
                            )
                            finished = true
                        } finally {
                            synchronized(downloadLock) {
                                activeDownloadJobs.remove(modelId, ownJob)
                                if (finished) {
                                    val pending = HuggingFaceDownloadBackgroundManager.pendingModelIds(
                                        this@HuggingFaceModelDownloadForegroundService
                                    )
                                    pending.remove(model.id)
                                    HuggingFaceDownloadBackgroundManager.savePendingModelIds(
                                        this@HuggingFaceModelDownloadForegroundService,
                                        pending
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        if (wakeLock?.isHeld == true) return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LLMLab:ModelDownload")
            .apply { acquire(4L * 60L * 60L * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        processor?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 41_699
        const val USER_CANCEL_REASON = "LLMLAB_USER_CANCELLED"
    }
}

private object DownloadNotification {
    private const val CHANNEL_ID = "model_downloads"
    private const val CHANNEL_NAME = "Modell-Downloads"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun build(context: Context, model: HuggingFaceModel, downloaded: Long, total: Long, status: String): Notification {
        ensureChannel(context)
        val percent = if (total > 0L) (downloaded * 100L / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(model.title)
            .setContentText("$status · $percent%")
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun serviceNotification(context: Context, status: String, progress: Int? = null): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LLM Lab · Modell-Downloads")
            .setContentText(status)
            .setProgress(100, (progress ?: 0).coerceIn(0, 100), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
