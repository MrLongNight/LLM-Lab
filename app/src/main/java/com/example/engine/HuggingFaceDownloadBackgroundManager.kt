package com.example.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Schedules user-started GGUF downloads independently from the Activity/ViewModel lifecycle.
 * Android 14+ uses User-Initiated Data Transfer jobs; older supported releases use a dataSync
 * foreground service. Both paths write to the same resumable .part files as the HTTP downloader.
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

    private const val PREFS = "huggingface_background_downloads"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_CANCELLED_IDS = "cancelled_ids"
    private const val KEY_FALLBACK_PENDING_IDS = "fallback_pending_ids"
    private const val JOB_ID_BASE = 41_700
    private val lock = Any()

    fun enqueue(context: Context, models: List<HuggingFaceModel>) {
        val app = context.applicationContext
        if (models.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            enqueueUserInitiatedJobs(app, models)
        } else {
            val intent = Intent(app, HuggingFaceModelDownloadForegroundService::class.java)
                .putStringArrayListExtra(EXTRA_MODEL_IDS, ArrayList(models.map { it.id }))
            ContextCompat.startForegroundService(app, intent)
        }
    }

    suspend fun download(
        context: Context,
        model: HuggingFaceModel,
        onProgress: (Long, Long) -> Unit
    ): File {
        val app = context.applicationContext
        val downloader = HuggingFaceModelDownloader(app)
        downloader.findVerifiedLocalModel(model)?.let {
            onProgress(it.length(), it.length())
            return it
        }

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
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
                                continuation.cancel(error)
                            }
                        }
                        ACTION_COMPLETED -> {
                            val file = File(app.filesDir, "models/${model.filename}")
                            if (file.isFile && file.length() > 0L) continuation.resume(file)
                            else continuation.resumeWithException(IOException("Download für ${model.filename} wurde ohne fertige Datei gemeldet."))
                        }
                        ACTION_FAILED -> continuation.resumeWithException(
                            IOException(intent.getStringExtra(EXTRA_ERROR) ?: "Download für ${model.filename} fehlgeschlagen.")
                        )
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
            continuation.invokeOnCancellation {
                runCatching { app.unregisterReceiver(receiver) }
                cancel(app, model.id)
            }

            try {
                enqueue(app, listOf(model))
            } catch (error: Throwable) {
                runCatching { app.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    fun cancel(context: Context, modelId: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cancelled = prefs.getStringSet(KEY_CANCELLED_IDS, emptySet()).orEmpty().toMutableSet()
            cancelled += modelId
            prefs.edit().putStringSet(KEY_CANCELLED_IDS, cancelled).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            jobIdFor(modelId)?.let { app.getSystemService(JobScheduler::class.java)?.cancel(it) }
        } else {
            app.startService(
                Intent(app, HuggingFaceModelDownloadForegroundService::class.java)
                    .setAction(HuggingFaceModelDownloadForegroundService.ACTION_CANCEL)
                    .putExtra(EXTRA_MODEL_ID, modelId)
            )
        }
    }

    internal fun isExplicitlyCancelled(context: Context, modelId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cancelled = prefs.getStringSet(KEY_CANCELLED_IDS, emptySet()).orEmpty().toMutableSet()
        val wasCancelled = cancelled.remove(modelId)
        if (wasCancelled) prefs.edit().putStringSet(KEY_CANCELLED_IDS, cancelled).apply()
        return wasCancelled
    }

    internal fun markFinished(context: Context, modelId: String) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val scheduled = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
            scheduled.remove(modelId)
            val cancelled = prefs.getStringSet(KEY_CANCELLED_IDS, emptySet()).orEmpty().toMutableSet()
            cancelled.remove(modelId)
            prefs.edit()
                .putStringSet(KEY_SCHEDULED_IDS, scheduled)
                .putStringSet(KEY_CANCELLED_IDS, cancelled)
                .apply()
        }
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

    private fun enqueueUserInitiatedJobs(context: Context, models: List<HuggingFaceModel>) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: throw IllegalStateException("Android JobScheduler ist nicht verfügbar.")
        for (model in models.distinctBy { it.id }) {
            val jobId = jobIdFor(model.id)
                ?: throw IllegalArgumentException("Unbekannte Modell-ID: ${model.id}")
            synchronized(lock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val scheduled = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
                if (model.id in scheduled) return@synchronized

                scheduled += model.id
                prefs.edit().putStringSet(KEY_SCHEDULED_IDS, scheduled).commit()

                val extras = PersistableBundle().apply { putString(EXTRA_MODEL_ID, model.id) }
                val jobInfo = JobInfo.Builder(
                    jobId,
                    ComponentName(context, HuggingFaceModelDownloadJobService::class.java)
                )
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(model.estimatedSizeBytes, 0L)
                    .setExtras(extras)
                    .build()
                if (scheduler.schedule(jobInfo) != JobScheduler.RESULT_SUCCESS) {
                    scheduled.remove(model.id)
                    prefs.edit().putStringSet(KEY_SCHEDULED_IDS, scheduled).commit()
                    throw IOException("Android konnte den Hintergrund-Download für ${model.filename} nicht einplanen.")
                }
            }
        }
    }

    private fun jobIdFor(modelId: String): Int? {
        val index = HuggingFaceModelCatalog.models.indexOfFirst { it.id == modelId }
        return index.takeIf { it >= 0 }?.let { JOB_ID_BASE + it }
    }

    internal fun pendingFallbackIds(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FALLBACK_PENDING_IDS, emptySet()).orEmpty().toMutableSet()
    }

    internal fun saveFallbackIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FALLBACK_PENDING_IDS, ids).apply()
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HuggingFaceModelDownloadJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val modelId = params.extras.getString(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_ID)
        val model = modelId?.let(HuggingFaceModelCatalog::find)
        if (model == null) {
            jobFinished(params, false)
            return false
        }

        val notificationId = params.jobId
        setNotification(
            params,
            notificationId,
            DownloadNotification.build(this, model, 0L, model.estimatedSizeBytes, "Download wird vorbereitet…"),
            JOB_END_NOTIFICATION_POLICY_DETACH
        )

        val job = serviceScope.launch {
            try {
                downloadSlots.withPermit {
                    HuggingFaceModelDownloader(applicationContext).download(model) { downloaded, total ->
                        setNotification(
                            params,
                            notificationId,
                            DownloadNotification.build(this@HuggingFaceModelDownloadJobService, model, downloaded, total, "Modell wird geladen…"),
                            JOB_END_NOTIFICATION_POLICY_DETACH
                        )
                        HuggingFaceDownloadBackgroundManager.broadcastProgress(applicationContext, model, downloaded, total)
                    }
                }
                HuggingFaceDownloadBackgroundManager.markFinished(applicationContext, model.id)
                HuggingFaceDownloadBackgroundManager.broadcastCompleted(applicationContext, model)
                setNotification(
                    params,
                    notificationId,
                    DownloadNotification.build(this@HuggingFaceModelDownloadJobService, model, model.estimatedSizeBytes, model.estimatedSizeBytes, "Download abgeschlossen"),
                    JOB_END_NOTIFICATION_POLICY_DETACH
                )
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                // onStopJob() decides whether Android should restart this resumable transfer.
                throw cancelled
            } catch (error: Throwable) {
                HuggingFaceDownloadBackgroundManager.markFinished(applicationContext, model.id)
                HuggingFaceDownloadBackgroundManager.broadcastFailed(
                    applicationContext,
                    model,
                    error.message ?: error.javaClass.simpleName
                )
                jobFinished(params, false)
            } finally {
                runningJobs.remove(params.jobId)
            }
        }
        runningJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val modelId = params.extras.getString(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_ID).orEmpty()
        runningJobs.remove(params.jobId)?.cancel()
        val cancelledByUser = HuggingFaceDownloadBackgroundManager.isExplicitlyCancelled(this, modelId)
        if (cancelledByUser) HuggingFaceDownloadBackgroundManager.markFinished(this, modelId)
        return !cancelledByUser
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        // Two large GGUF transfers are allowed simultaneously; inference stays serial.
        val downloadSlots = Semaphore(2)
    }
}

/** Foreground-service fallback for Android 10–13, before user-initiated transfer jobs existed. */
class HuggingFaceModelDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val modelId = intent.getStringExtra(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_ID)
            if (modelId != null) {
                val pending = HuggingFaceDownloadBackgroundManager.pendingFallbackIds(this)
                pending.remove(modelId)
                HuggingFaceDownloadBackgroundManager.saveFallbackIds(this, pending)
                HuggingFaceDownloadBackgroundManager.broadcastFailed(this,
                    HuggingFaceModelCatalog.find(modelId) ?: return START_NOT_STICKY,
                    "Download wurde vom Nutzer abgebrochen. Der bisherige Fortschritt bleibt erhalten."
                )
            }
            if (HuggingFaceDownloadBackgroundManager.pendingFallbackIds(this).isEmpty()) {
                processor?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val pending = HuggingFaceDownloadBackgroundManager.pendingFallbackIds(this)
        intent?.getStringArrayListExtra(HuggingFaceDownloadBackgroundManager.EXTRA_MODEL_IDS)
            ?.filter { HuggingFaceModelCatalog.find(it) != null }
            ?.forEach { pending += it }
        HuggingFaceDownloadBackgroundManager.saveFallbackIds(this, pending)

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
            val batch = HuggingFaceDownloadBackgroundManager.pendingFallbackIds(this).take(2)
            if (batch.isEmpty()) break

            coroutineScope {
                batch.map { modelId ->
                    async {
                        val model = HuggingFaceModelCatalog.find(modelId) ?: return@async
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
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            HuggingFaceDownloadBackgroundManager.broadcastFailed(
                                applicationContext, model, error.message ?: error.javaClass.simpleName
                            )
                        } finally {
                            val pending = HuggingFaceDownloadBackgroundManager.pendingFallbackIds(this@HuggingFaceModelDownloadForegroundService)
                            pending.remove(model.id)
                            HuggingFaceDownloadBackgroundManager.saveFallbackIds(this@HuggingFaceModelDownloadForegroundService, pending)
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
        const val ACTION_CANCEL = "com.example.llmlab.CANCEL_BACKGROUND_DOWNLOAD"
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
