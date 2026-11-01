package com.example.ui

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.TestRunEntity
import com.example.engine.ExportManager
import com.example.engine.GgufInfo
import com.example.engine.GgufMetadataParser
import com.example.engine.HuggingFaceModelCatalog
import com.example.engine.HuggingFaceDownloadBackgroundManager
import com.example.engine.AutomatedModelSuitePlan
import com.example.engine.AutomatedModelSuiteCoordinator
import com.example.engine.AutomatedModelSuiteProgress
import com.example.engine.AutomatedWorkflowResult
import com.example.engine.BackendPreference
import com.example.engine.NativeBackendInfo
import com.example.engine.InferenceCallback
import com.example.engine.InferenceConfig
import com.example.engine.NativeInferenceEngine
import com.example.engine.NativeMetrics
import com.example.engine.ProfilePreset
import com.example.engine.PromptResultValidator
import com.example.engine.PromptTestCatalog
import com.example.engine.PromptTestCase
import com.example.engine.InferenceTestVariant
import com.example.engine.SystemTelemetry
import com.example.engine.SystemTelemetryManager
import com.example.engine.TimeoutConfig
import com.example.engine.TimeoutController
import com.example.engine.InferenceTimeoutKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.text.DecimalFormat

data class HuggingFaceDownloadUiState(
    val modelId: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isDownloading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class AutomatedModelSuiteUiState(
    val isRunning: Boolean = false,
    val phase: String = "IDLE",
    val currentModelIndex: Int = 0,
    val modelCount: Int = 0,
    val currentModelName: String = "",
    val quickPassed: Int = 0,
    val quickFailed: Int = 0,
    val fullTestsCompleted: Int = 0,
    val fullTestsTotal: Int = 0,
    val message: String = "",
    val error: String? = null
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val metrics: NativeMetrics? = null,
    val isStreaming: Boolean = false
)

sealed class ModelLifecycleState {
    object Unloaded : ModelLifecycleState()
    data class ModelSelected(
        val uri: Uri,
        val fileName: String,
        val fileSizeBytes: Long,
        val ggufInfo: GgufInfo
    ) : ModelLifecycleState()
    data class Initializing(
        val progressPercent: Float?,
        val phase: String,
        val elapsedMs: Long
    ) : ModelLifecycleState()
    data class Ready(
        val fileName: String,
        val fileSizeBytes: Long,
        val ggufInfo: GgufInfo,
        val initDurationMs: Long,
        val engineVersion: String,
        val loadedConfig: InferenceConfig,
        val activeBackendName: String
    ) : ModelLifecycleState()
    data class InferenceRunning(
        val prompt: String,
        val streamingText: String,
        val tokenCount: Int,
        val tps: Float,
        val elapsedMs: Long,
        val stage: String = "Prompt-Evaluierung",
        val progressPercent: Float? = null
    ) : ModelLifecycleState()
    object Aborting : ModelLifecycleState()
    data class Error(
        val errorMessage: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ModelLifecycleState()
}

data class BenchmarkSummary(
    val totalRuns: Int = 0,
    val successfulRuns: Int = 0,
    val failedRuns: Int = 0,
    val timeoutRuns: Int = 0,
    val avgDurationMs: Long = 0,
    val p50DurationMs: Long = 0,
    val p95DurationMs: Long = 0,
    val avgTps: Float = 0f,
    val isRunning: Boolean = false,
    val currentIteration: Int = 0,
    val stopReason: String? = null
)

class LLMLabViewModel @JvmOverloads constructor(
    application: Application,
    private val timeoutConfig: TimeoutConfig = TimeoutConfig.MOBILE
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dao = db.testRunDao()
    private val nativeBridge = NativeInferenceEngine(application)
    private val timeoutController = TimeoutController(timeoutConfig)
    private val automatedSuiteCoordinator = AutomatedModelSuiteCoordinator()
    private val _downloadedModelIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModelIds: StateFlow<Set<String>> = _downloadedModelIds.asStateFlow()
    private val _huggingFaceDownload = MutableStateFlow(HuggingFaceDownloadUiState())
    val huggingFaceDownload: StateFlow<HuggingFaceDownloadUiState> = _huggingFaceDownload.asStateFlow()
    private val _automatedSuite = MutableStateFlow(AutomatedModelSuiteUiState())
    val automatedSuite: StateFlow<AutomatedModelSuiteUiState> = _automatedSuite.asStateFlow()
    private var huggingFaceDownloadJob: Job? = null
    @Volatile private var activeHuggingFaceDownloadId: String? = null

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _modelState = MutableStateFlow<ModelLifecycleState>(ModelLifecycleState.Unloaded)
    val modelState: StateFlow<ModelLifecycleState> = _modelState.asStateFlow()

    private val _config = MutableStateFlow(InferenceConfig.fromPreset(ProfilePreset.BALANCED))
    val config: StateFlow<InferenceConfig> = _config.asStateFlow()

    private val _availableBackends = MutableStateFlow<List<NativeBackendInfo>>(emptyList())
    val availableBackends: StateFlow<List<NativeBackendInfo>> = _availableBackends.asStateFlow()

    private val welcomeMessageId = java.util.UUID.randomUUID().toString()
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage(
            id = welcomeMessageId,
            isUser = false,
            text = "Willkommen im LLM Lab. Wähle im Bereich Modell eine GGUF-Datei aus und initialisiere sie für lokale Inferenz."
        )
    ))
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _telemetry = MutableStateFlow(SystemTelemetryManager.capture(application))
    val telemetry: StateFlow<SystemTelemetry> = _telemetry.asStateFlow()

    private val _labActiveTest = MutableStateFlow<PromptTestCase?>(null)
    val labActiveTest: StateFlow<PromptTestCase?> = _labActiveTest.asStateFlow()

    private val _labTestOutput = MutableStateFlow<String>("")
    val labTestOutput: StateFlow<String> = _labTestOutput.asStateFlow()

    private val _labMetrics = MutableStateFlow<NativeMetrics?>(null)
    val labMetrics: StateFlow<NativeMetrics?> = _labMetrics.asStateFlow()

    private val _benchmarkSummary = MutableStateFlow(BenchmarkSummary())
    val benchmarkSummary: StateFlow<BenchmarkSummary> = _benchmarkSummary.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    @Volatile private var lastWorkflowBackendReport: String = ""
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    val testRunHistory = dao.getRecentRuns(200).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private var activeInferenceJob: Job? = null
    @Volatile private var engineOperationGeneration: Long = 0L
    private val nativeEngineUnresponsive = AtomicBoolean(false)
    private val automatedSuiteAbortRequested = AtomicBoolean(false)
    private val nativeCallInProgress = AtomicBoolean(false)
    private val nativeCallGeneration = AtomicLong(0L)

    private fun beginNativeCall() {
        nativeCallGeneration.incrementAndGet()
        nativeCallInProgress.set(true)
    }

    private fun endNativeCall() {
        nativeCallInProgress.set(false)
        nativeCallGeneration.incrementAndGet()
    }

    private companion object {
        const val NATIVE_ABORT_GRACE_MS = 10_000L
    }
    private var localCachedModelUri: Uri? = null
    private var lastModelSelection: ModelLifecycleState.ModelSelected? = null
    /** Active GGUF path used by llama.cpp; differs for managed HF downloads and user imports. */
    private var activeModelFile: File? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _availableBackends.value = nativeBridge.getBackendInfo()
        }
        refreshDownloadedModels()
        // Starte periodische Telemetrieerfassung
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                _telemetry.value = SystemTelemetryManager.capture(getApplication())
                delay(3000)
            }
        }
    }

    /**
     * JNI calls are blocking. Operations are serialized and the next operation
     * waits for the previous native call to return after its abort signal.
     */
    private fun monitorNativeAbortResponsiveness(
        operation: Job?,
        phase: String,
        expectedNativeCallGeneration: Long = nativeCallGeneration.get()
    ) {
        if (operation == null) return
        viewModelScope.launch(Dispatchers.Default) {
            delay(NATIVE_ABORT_GRACE_MS)
            if (operation.isActive && nativeCallInProgress.get() &&
                nativeCallGeneration.get() == expectedNativeCallGeneration) {
                // The JNI engine now lives in :inference. If its abort signal is
                // not acknowledged, kill that process instead of declaring the
                // whole application permanently unresponsive.
                nativeBridge.killInferenceProcess()
                nativeEngineUnresponsive.set(false)
                val message = "$phase: Der isolierte Inference-Prozess hat den Abbruch nicht innerhalb von ${NATIVE_ABORT_GRACE_MS / 1000}s bestätigt und wurde beendet. Beim nächsten Vorgang wird er neu verbunden."
                _modelState.value = ModelLifecycleState.Error(message)
                _labActiveTest.value = null
                _benchmarkSummary.value = _benchmarkSummary.value.copy(
                    isRunning = false,
                    stopReason = message
                )
                _chatMessages.value = _chatMessages.value.map { item ->
                    if (item.isStreaming) item.copy(text = message, isStreaming = false) else item
                }
            }
        }
    }

    private fun launchEngineOperation(block: suspend () -> Unit) {
        if (nativeEngineUnresponsive.get()) {
            _modelState.value = ModelLifecycleState.Error(
                "Die native Engine hat zuvor nicht auf einen Abbruch reagiert. Bitte LLM Lab neu starten."
            )
            return
        }

        val generation = ++engineOperationGeneration
        val previous = activeInferenceJob
        if (previous?.isActive == true) {
            nativeBridge.requestAbort()
            monitorNativeAbortResponsiveness(previous, "Vorheriger nativer Vorgang")
        }

        val next = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            if (generation != engineOperationGeneration) return@launch
            if (nativeEngineUnresponsive.get()) {
                _modelState.value = ModelLifecycleState.Error(
                    "Die native Engine ist nicht mehr antwortfähig. Bitte LLM Lab neu starten."
                )
                return@launch
            }
            nativeBridge.prepareForOperation()
            block()
        }
        activeInferenceJob = next
    }

    private suspend fun executeInferenceWithWatchdog(
        prompt: String,
        config: InferenceConfig,
        callback: InferenceCallback,
        timeoutKind: InferenceTimeoutKind,
        requestedTimeoutMs: Long,
        timeoutProfile: TimeoutConfig = timeoutConfig
    ): NativeMetrics {
        val operation = activeInferenceJob
        val timeoutSession = TimeoutController(timeoutProfile).startInferenceWatchdog(
            scope = viewModelScope,
            kind = timeoutKind,
            requestedLimitMs = requestedTimeoutMs,
            onTimeout = {
                _modelState.value = ModelLifecycleState.Aborting
                nativeBridge.requestAbort()
                monitorNativeAbortResponsiveness(operation, "Inferenz-Timeout")
            }
        )
        return try {
            val nativeMetrics = try {
                beginNativeCall()
                nativeBridge.executeInference(prompt, config, timeoutSession.wrap(callback), requestedTimeoutMs)
            } finally {
                endNativeCall()
            }
            when {
                nativeEngineUnresponsive.get() -> nativeMetrics.copy(
                    finishReason = "NATIVE_ERROR",
                    errorMessage = "Die native Engine hat das Abbruchsignal nicht rechtzeitig quittiert. App-Neustart erforderlich."
                )
                timeoutSession.isTimedOut -> nativeMetrics.copy(
                    finishReason = "TIMEOUT",
                    errorMessage = listOfNotNull(
                        timeoutSession.reason ?: "Inferenz-Zeitlimit erreicht.",
                        nativeMetrics.errorMessage
                    ).joinToString(" ")
                )
                else -> nativeMetrics
            }
        } finally {
            timeoutSession.cancel()
        }
    }

    private fun updateWelcomeMessage(text: String) {
        _chatMessages.value = _chatMessages.value.map { message ->
            if (message.id == welcomeMessageId) message.copy(text = text) else message
        }
    }

    fun selectTab(index: Int) {
        _currentTab.value = index
    }

    fun updateConfig(newConfig: InferenceConfig) {
        _config.value = newConfig
    }

    /** True when a setting changed a parameter that is fixed in the loaded native context. */
    fun requiresModelReload(): Boolean {
        val ready = _modelState.value as? ModelLifecycleState.Ready ?: return false
        return !ready.loadedConfig.hasSameNativeContext(_config.value)
    }

    fun setProfilePreset(preset: ProfilePreset) {
        _config.value = InferenceConfig.fromPreset(preset).copy(
            timeoutInitSec = _config.value.timeoutInitSec,
            timeoutShortTestSec = _config.value.timeoutShortTestSec,
            timeoutNormalInferenceSec = _config.value.timeoutNormalInferenceSec,
            timeoutStressTestSec = _config.value.timeoutStressTestSec,
            backendPreference = _config.value.backendPreference
        )
    }

    fun updateTimeout(initSec: Int, shortSec: Int, normalSec: Int, stressSec: Int) {
        _config.value = _config.value.copy(
            timeoutInitSec = initSec,
            timeoutShortTestSec = shortSec,
            timeoutNormalInferenceSec = normalSec,
            timeoutStressTestSec = stressSec
        )
    }

    // ----------------- MODELLVERWALTUNG -----------------

    fun onModelFileSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                var fileName = "Unbekanntes GGUF-Modell"
                var fileSize = 0L

                val directFile = if (uri.scheme == "file") {
                    File(uri.path ?: throw IOException("Lokaler Modellpfad fehlt."))
                } else null
                if (directFile != null) {
                    if (!directFile.isFile) throw IOException("Lokale Modell-Datei existiert nicht: ${directFile.name}")
                    fileName = directFile.name
                    fileSize = directFile.length()
                } else {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }

                // GGUF-Header und Metadaten vor dem Modellladen prüfen.
                val ggufInfo = directFile?.let { GgufMetadataParser.parse(it) }
                    ?: GgufMetadataParser.parse(context, uri, fileSize)
                if (!ggufInfo.isValid) {
                    _modelState.value = ModelLifecycleState.Error(
                        ggufInfo.errorMessage ?: "Ungültige GGUF-Datei"
                    )
                    return@launch
                }

                val selected = ModelLifecycleState.ModelSelected(
                    uri = uri,
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    ggufInfo = ggufInfo
                )
                lastModelSelection = selected
                _modelState.value = selected
            } catch (e: Exception) {
                _modelState.value = ModelLifecycleState.Error("Fehler bei Modellauswahl: ${e.message}")
            }
        }
    }

    /**
     * Search all app-private locations used by previous LLM Lab builds before
     * showing a model as missing. Never delete or redownload a discovered file.
     */
    private fun findLocalHuggingFaceModel(model: com.example.engine.HuggingFaceModel): File? {
        val context = getApplication<Application>()
        val candidates = listOfNotNull(
            File(context.filesDir, "models/${model.filename}"),
            File(context.filesDir, model.filename),
            context.getExternalFilesDir("models")?.let { File(it, model.filename) },
            context.getExternalFilesDir(null)?.let { File(it, "models/${model.filename}") },
            context.getExternalFilesDir(null)?.let { File(it, model.filename) }
        ).distinctBy { it.absolutePath }
        return candidates.firstOrNull { file ->
            file.isFile && file.length() >= (model.estimatedSizeBytes * 0.85).toLong()
        }
    }

    fun refreshDownloadedModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadedModelIds.value = HuggingFaceModelCatalog.models
                .filter { model -> findLocalHuggingFaceModel(model) != null }
                .map { it.id }
                .toSet()
        }
    }

    fun selectDownloadedHuggingFaceModel(modelId: String) {
        val model = HuggingFaceModelCatalog.find(modelId) ?: return
        val file = findLocalHuggingFaceModel(model)
        if (file == null) {
            refreshDownloadedModels()
            _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                modelId = modelId,
                error = "In den lokalen App-Speichern wurde keine vollständige Datei gefunden. Es wird nichts gelöscht; bitte erst Speicher prüfen, bevor du erneut herunterlädst."
            )
            return
        }
        onModelFileSelected(Uri.fromFile(file))
    }

    fun downloadHuggingFaceModel(modelId: String) {
        val model = HuggingFaceModelCatalog.find(modelId) ?: return
        if (huggingFaceDownloadJob?.isActive == true) return
        activeHuggingFaceDownloadId = model.id

        huggingFaceDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                modelId = model.id,
                isDownloading = true,
                message = "Hintergrund-Download wird eingeplant…"
            )
            try {
                val file = HuggingFaceDownloadBackgroundManager.download(getApplication(), model) { downloaded, total ->
                    _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                        modelId = model.id,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        isDownloading = true,
                        message = if (total > 0L && downloaded >= total) {
                            "Prüfe SHA-256 und GGUF-Header…"
                        } else {
                            "Hintergrund-Download läuft…"
                        }
                    )
                }
                _downloadedModelIds.value = _downloadedModelIds.value + model.id
                _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                    modelId = model.id,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    isDownloading = false,
                    message = "Download und SHA-256-Prüfung erfolgreich. Modell ist lokal verfügbar."
                )
                onModelFileSelected(Uri.fromFile(file))
            } catch (cancelled: CancellationException) {
                _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                    modelId = model.id,
                    isDownloading = false,
                    message = "Download wird im Hintergrund fortgesetzt; vorhandene Bytes bleiben erhalten."
                )
                throw cancelled
            } catch (error: Exception) {
                _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                    modelId = model.id,
                    isDownloading = false,
                    message = "Download fehlgeschlagen.",
                    error = error.message ?: "Unbekannter Download-Fehler"
                )
            } finally {
                activeHuggingFaceDownloadId = null
                refreshDownloadedModels()
            }
        }
    }

    fun cancelHuggingFaceDownload() {
        activeHuggingFaceDownloadId?.let { HuggingFaceDownloadBackgroundManager.cancel(getApplication(), it) }
        huggingFaceDownloadJob?.cancel()
    }

    fun initializeModel() {
        val selected = lastModelSelection
            ?: (_modelState.value as? ModelLifecycleState.ModelSelected)
            ?: return

        launchEngineOperation {
            val startTime = System.currentTimeMillis()
            val timedOut = AtomicBoolean(false)
            val watchdog = timeoutController.startModelInitializationWatchdog(
                scope = viewModelScope,
                requestedLimitMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                    _config.value.timeoutInitSec.coerceAtLeast(1).toLong()
                )
            ) {
                timedOut.set(true)
                _modelState.value = ModelLifecycleState.Aborting
                nativeBridge.requestAbort()
                monitorNativeAbortResponsiveness(activeInferenceJob, "Modellinitialisierung")
            }
            val timeoutMs = watchdog.timeoutMs

            try {
                _modelState.value = ModelLifecycleState.Initializing(
                    null, "Bereite GGUF-Datei für native Engine vor...", 0
                )

                val context = getApplication<Application>()
                // filesDir statt cacheDir: Das Betriebssystem darf das große Modell
                // nicht wie einen entbehrlichen Cache-Eintrag entfernen.
                val managedModelsDir = File(context.filesDir, "models")
                val selectedLocalFile = if (selected.uri.scheme == "file") {
                    selected.uri.path?.let { path -> File(path) }
                } else null
                val selectedManagedFile = selectedLocalFile?.takeIf { candidate ->
                    candidate.isFile &&
                        candidate.canonicalPath.startsWith(managedModelsDir.canonicalPath + File.separator)
                }
                // Hugging-Face-Modelle bleiben an ihrem dauerhaften Speicherort. So vermeiden
                // wir eine zweite 0.5–1.1-GB-Kopie unter active_model.gguf.
                val cacheModel = selectedManagedFile ?: File(context.filesDir, "active_model.gguf")
                val cachePart = File(context.filesDir, "active_model.gguf.part")
                val canReuse = selectedManagedFile != null ||
                    (localCachedModelUri == selected.uri &&
                        cacheModel.isFile &&
                        (selected.fileSizeBytes <= 0L || cacheModel.length() == selected.fileSizeBytes))

                if (!canReuse) {
                    cachePart.delete()
                    _modelState.value = ModelLifecycleState.Initializing(
                        if (selected.fileSizeBytes > 0L) 10f else null,
                        "Kopiere GGUF in den internen App-Speicher...", 0
                    )
                    val input = context.contentResolver.openInputStream(selected.uri)
                        ?: throw IOException("Die ausgewählte GGUF-Datei konnte nicht geöffnet werden.")

                    var totalCopied = 0L
                    input.use { source ->
                        FileOutputStream(cachePart).use { output ->
                            val buffer = ByteArray(512 * 1024)
                            while (true) {
                                if (timedOut.get() || nativeBridge.isAbortRequested()) {
                                    throw InterruptedIOException("Kopiervorgang durch Timeout oder Nutzer abgebrochen.")
                                }
                                val read = source.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                totalCopied += read
                                val percent: Float? = if (selected.fileSizeBytes > 0L) {
                                    (10f + 70f * totalCopied.toFloat() / selected.fileSizeBytes.toFloat()).coerceIn(10f, 80f)
                                } else {
                                    null
                                }
                                _modelState.value = ModelLifecycleState.Initializing(
                                    percent,
                                    "Kopiere ${totalCopied / (1024 * 1024)} MB...",
                                    System.currentTimeMillis() - startTime
                                )
                            }
                            output.fd.sync()
                        }
                    }

                    if (timedOut.get() || nativeBridge.isAbortRequested()) {
                        throw InterruptedIOException("Kopiervorgang abgebrochen.")
                    }
                    if (totalCopied <= 0L) {
                        throw IOException("Die ausgewählte GGUF-Datei ist leer.")
                    }
                    if (selected.fileSizeBytes > 0L && totalCopied != selected.fileSizeBytes) {
                        throw IOException(
                            "Unvollständige Modellkopie: ${totalCopied} von ${selected.fileSizeBytes} Bytes."
                        )
                    }

                    if (cacheModel.exists() && !cacheModel.delete()) {
                        throw IOException("Vorhandene Modellkopie konnte nicht ersetzt werden.")
                    }
                    if (!cachePart.renameTo(cacheModel)) {
                        cachePart.copyTo(cacheModel, overwrite = true)
                        cachePart.delete()
                    }
                }

                if (timedOut.get() || nativeBridge.isAbortRequested()) {
                    throw InterruptedIOException("Modellinitialisierung vor dem nativen Laden abgebrochen.")
                }
                if (!cacheModel.isFile || cacheModel.length() <= 0L) {
                    throw IOException("Die lokale Modellkopie ist nicht vollständig vorhanden.")
                }

                localCachedModelUri = selected.uri
                activeModelFile = cacheModel

                _modelState.value = ModelLifecycleState.Initializing(
                    null, "Lade Modell und erstelle llama.cpp Context...", System.currentTimeMillis() - startTime
                )

                val configForLoad = _config.value
                val outError = arrayOfNulls<String>(1)
                val loadSuccess = try {
                    beginNativeCall()
                    nativeBridge.loadModel(cacheModel.absolutePath, configForLoad, outError, timeoutMs)
                } finally {
                    endNativeCall()
                }

                if (timedOut.get() || nativeBridge.isAbortRequested()) {
                    if (loadSuccess) nativeBridge.unloadModel()
                    _modelState.value = ModelLifecycleState.Error(
                        if (timedOut.get()) "TIMEOUT: Modellinitialisierung überschritt ${timeoutMs / 1_000L}s."
                        else "Modellinitialisierung wurde abgebrochen."
                    )
                } else if (!loadSuccess) {
                    _modelState.value = ModelLifecycleState.Error(
                        outError[0] ?: "Native llama.cpp Modellinitialisierung fehlgeschlagen."
                    )
                } else if (nativeEngineUnresponsive.get()) {
                    _modelState.value = ModelLifecycleState.Error(
                        "Die native Modellinitialisierung hat nicht rechtzeitig auf den Abbruch reagiert. Bitte App neu starten."
                    )
                } else {
                    updateWelcomeMessage("Modell ${selected.fileName} ist lokal geladen und einsatzbereit. Stelle jetzt deine Frage im Chat oder starte einen Test im Prompt-Labor.")
                    _modelState.value = ModelLifecycleState.Ready(
                        fileName = selected.fileName,
                        fileSizeBytes = cacheModel.length(),
                        ggufInfo = selected.ggufInfo,
                        initDurationMs = System.currentTimeMillis() - startTime,
                        engineVersion = nativeBridge.getEngineVersion(),
                        loadedConfig = configForLoad,
                        activeBackendName = nativeBridge.getActiveBackendName()
                    )
                }
            } catch (e: Exception) {
                File(getApplication<Application>().filesDir, "active_model.gguf.part").delete()
                val message = when {
                    nativeEngineUnresponsive.get() -> "Die native Modellinitialisierung reagiert nicht mehr. Bitte App neu starten."
                    timedOut.get() -> "TIMEOUT: Modellinitialisierung überschritt ${timeoutMs / 1_000L}s."
                    nativeBridge.isAbortRequested() -> "Modellinitialisierung wurde durch den Nutzer abgebrochen."
                    else -> "Initialisierungsfehler: ${e.message}"
                }
                _modelState.value = ModelLifecycleState.Error(message)
            } finally {
                watchdog.cancel()
            }
        }
    }

    fun unloadModel() {
        launchEngineOperation {
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.unloadModel()
            updateWelcomeMessage("Modell entladen. Wähle im Bereich Modell erneut eine GGUF-Datei aus, um die lokale Inferenz zu starten.")
            _modelState.value = lastModelSelection ?: ModelLifecycleState.Unloaded
        }
    }

    // ----------------- INFERENZ & CHAT -----------------

    fun sendChatMessage(promptText: String) {
        if (promptText.isBlank()) return
        val readyState = _modelState.value as? ModelLifecycleState.Ready
        if (readyState == null) {
            _chatMessages.value = _chatMessages.value + ChatMessage(
                isUser = false,
                text = "Fehler: Kein vollständig geladenes natives Modell. Bitte initialisiere das GGUF-Modell."
            )
            return
        }

        val userMsg = ChatMessage(isUser = true, text = promptText)
        val currentConfig = _config.value
        if (!readyState.loadedConfig.hasSameNativeContext(currentConfig)) {
            _chatMessages.value = _chatMessages.value + userMsg + ChatMessage(
                isUser = false,
                text = "Context-Parameter geändert. Bitte im Bereich Modell Erneut laden auswählen, bevor eine neue Inferenz startet."
            )
            return
        }

        val assistantPlaceholderId = java.util.UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            isUser = false,
            text = "Native Inferenz wird gestartet...",
            isStreaming = true
        )
        _chatMessages.value = _chatMessages.value + userMsg + assistantPlaceholder
        launchEngineOperation {
            var streamedResponse = StringBuilder()
            var tokenCounter = 0

            _modelState.value = ModelLifecycleState.InferenceRunning(
                prompt = promptText,
                streamingText = "",
                tokenCount = 0,
                tps = 0f,
                elapsedMs = 0
            )

            val startTime = System.currentTimeMillis()
            val callback = object : InferenceCallback {
                override fun onToken(tokenText: String, tokenIndex: Int) {
                    streamedResponse.append(tokenText)
                    tokenCounter = tokenIndex + 1
                    val elapsed = System.currentTimeMillis() - startTime
                    _modelState.value = ModelLifecycleState.InferenceRunning(
                        prompt = promptText,
                        streamingText = streamedResponse.toString(),
                        tokenCount = tokenCounter,
                        tps = 0f,
                        elapsedMs = elapsed,
                        stage = "Token-Generierung",
                        progressPercent = null
                    )
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == assistantPlaceholderId) {
                            message.copy(text = streamedResponse.toString(), isStreaming = true)
                        } else message
                    }
                }

                override fun onProgress(progressPercent: Float, step: Int) {
                    val elapsed = System.currentTimeMillis() - startTime
                    _modelState.value = ModelLifecycleState.InferenceRunning(
                        prompt = promptText,
                        streamingText = streamedResponse.toString(),
                        tokenCount = tokenCounter,
                        tps = 0f,
                        elapsedMs = elapsed,
                        stage = "Prompt-Evaluierung",
                        progressPercent = progressPercent.coerceIn(0f, 100f)
                    )
                }
            }

            val finalMetrics = executeInferenceWithWatchdog(
                prompt = promptText,
                config = currentConfig,
                callback = callback,
                timeoutKind = InferenceTimeoutKind.GENERAL,
                requestedTimeoutMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                    currentConfig.timeoutNormalInferenceSec.coerceAtLeast(1).toLong()
                )
            )
            val response = streamedResponse.toString()

            _chatMessages.value = _chatMessages.value.map { message ->
                if (message.id == assistantPlaceholderId) {
                    message.copy(
                        text = when (finalMetrics.finishReason) {
                            "TIMEOUT" -> if (response.isBlank()) {
                                "UNVOLLSTÄNDIG: Das Zeitlimit wurde erreicht. ${finalMetrics.errorMessage.orEmpty()}"
                            } else {
                                "UNVOLLSTÄNDIGE ANTWORT – Zeitlimit erreicht (${finalMetrics.totalDurationMs} ms). Nicht als vollständige Antwort werten.\n\n$response"
                            }
                            "ABORTED_BY_USER" -> if (response.isBlank()) {
                                "ABGEBROCHEN: ${finalMetrics.errorMessage.orEmpty()}"
                            } else {
                                "ABGEBROCHENE TEILANTWORT – keine vollständige Antwort.\n\n$response"
                            }
                            "NATIVE_ERROR" -> "INFERENZFEHLER: ${finalMetrics.errorMessage.orEmpty()}" +
                                if (response.isBlank()) "" else "\n\nTeiltext:\n$response"
                            else -> if (response.isEmpty()) "Keine Modellantwort erhalten." else response
                        },
                        metrics = finalMetrics,
                        isStreaming = false
                    )
                } else message
            }

            // Chat-Inhalte sind potenziell personenbezogen und werden nicht in
            // Diagnosehistorie oder Export übernommen.
            dao.insertRun(TestRunEntity(
                testCategory = "CHAT",
                testTitle = "Chat Inferenz (Inhalt redigiert)",
                promptText = "[CHAT_PROMPT_REDACTED; chars=${promptText.length}]",
                responseText = "[CHAT_RESPONSE_REDACTED; chars=${response.length}]",
                promptTokens = finalMetrics.promptTokens,
                generatedTokens = finalMetrics.generatedTokens,
                promptEvalDurationMs = finalMetrics.promptEvalDurationMs,
                generationDurationMs = finalMetrics.generationDurationMs,
                tokensPerSecond = finalMetrics.tokensPerSecond,
                totalDurationMs = finalMetrics.totalDurationMs,
                nCtx = currentConfig.nCtx,
                nBatch = currentConfig.nBatch,
                nUbatch = currentConfig.nUbatch,
                nThreads = currentConfig.nThreads,
                    nThreadsBatch = currentConfig.nThreadsBatch,
                    backendPreference = currentConfig.backendPreference.name,
                    activeBackend = readyState.activeBackendName,
                    temperature = currentConfig.temperature,
                    topK = currentConfig.topK,
                    topP = currentConfig.topP,
                    maxTokens = currentConfig.maxTokens,
                    appCpuUsagePercent = _telemetry.value.appCpuUsagePercent,
                finishReason = finalMetrics.finishReason,
                isSuccess = (finalMetrics.finishReason == "STOP_TOKEN" || finalMetrics.finishReason == "MAX_TOKENS") &&
                    finalMetrics.generatedTokens > 0,
                errorMessage = finalMetrics.errorMessage,
                javaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                nativeHeapBytes = nativeBridge.getAllocatedHeap(),
                modelName = readyState.fileName,
                promptTokenizeDurationMs = finalMetrics.promptTokenizeDurationMs,
                firstTokenDurationMs = finalMetrics.firstTokenDurationMs,
                promptDecodeCalls = finalMetrics.promptDecodeCalls,
                generationDecodeCalls = finalMetrics.generationDecodeCalls
            ))

            if (finalMetrics.finishReason == "NATIVE_ERROR") {
                nativeBridge.unloadModel()
                _modelState.value = ModelLifecycleState.Error(
                    finalMetrics.errorMessage ?: "Native Inferenzfehler; Modell wurde aus Sicherheitsgründen entladen."
                )
            } else {
                _modelState.value = readyState
            }
        }
    }

    fun requestAbort() {
        val suiteRunning = _automatedSuite.value.isRunning
        if (suiteRunning) automatedSuiteAbortRequested.set(true)
        engineOperationGeneration++
        val runningInference = activeInferenceJob?.takeIf { it.isActive }
        if (runningInference != null || suiteRunning) {
            // Auch während Download oder Modellwechsel muss die Suite den Abbruch sehen.
            nativeBridge.requestAbort()
            if (runningInference != null) {
                _modelState.value = ModelLifecycleState.Aborting
                monitorNativeAbortResponsiveness(runningInference, "Manueller Abbruch")
            }
        }
        // Kein Job.cancel(): JNI wird über das native Abort-Flag beendet.
    }

    fun resetChat() {
        _chatMessages.value = listOf(
            ChatMessage(
                isUser = false,
                text = "Unterhaltung zurückgesetzt. Bereit für neue Eingaben."
            )
        )
    }

    // ----------------- PROMPT-LABOR -----------------

    /**
     * Reload the already-copied GGUF with a new native configuration.
     * The workflow runs serially on the engine-operation dispatcher.
     */
    private data class PreparedTestProfile(
        val ready: ModelLifecycleState.Ready,
        val mode: String
    )

    private suspend fun reloadCachedModelForTest(
        baseReady: ModelLifecycleState.Ready,
        config: InferenceConfig,
        timeoutProfile: TimeoutConfig = timeoutConfig
    ): PreparedTestProfile? {
        val cacheModel = activeModelFile
            ?: File(getApplication<Application>().filesDir, "active_model.gguf")
        if (!cacheModel.isFile || cacheModel.length() <= 0L) {
            _modelState.value = ModelLifecycleState.Error(
                "Die interne Modellkopie fehlt. Bitte das GGUF-Modell erneut initialisieren."
            )
            return null
        }

        val timedOut = AtomicBoolean(false)
        val watchdog = TimeoutController(timeoutProfile).startModelInitializationWatchdog(
            scope = viewModelScope,
            requestedLimitMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                config.timeoutInitSec.coerceAtLeast(1).toLong()
            )
        ) {
            timedOut.set(true)
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.requestAbort()
            monitorNativeAbortResponsiveness(activeInferenceJob, "Testprofil-Konfiguration")
        }
        val timeoutMs = watchdog.timeoutMs

        try {
            _modelState.value = ModelLifecycleState.Initializing(
                null, "Passe Context an; GGUF-Gewichte bleiben bei gleichem Backend geladen...", 0
            )
            val previousUsesAccelerator =
                baseReady.loadedConfig.backendPreference == BackendPreference.ACCELERATOR_REQUIRED
            val requestedUsesAccelerator =
                config.backendPreference == BackendPreference.ACCELERATOR_REQUIRED
            val canKeepModelWeights = previousUsesAccelerator == requestedUsesAccelerator
            val errors = arrayOfNulls<String>(1)
            val startedAt = SystemClock.elapsedRealtime()

            if (canKeepModelWeights) {
                nativeBridge.prepareForOperation()
                val contextUpdated = try {
                    beginNativeCall()
                    nativeBridge.reconfigureContext(config, errors, timeoutMs)
                } finally {
                    endNativeCall()
                }

                if (contextUpdated) {
                    return PreparedTestProfile(
                        ready = baseReady.copy(
                            initDurationMs = SystemClock.elapsedRealtime() - startedAt,
                            loadedConfig = config,
                            activeBackendName = nativeBridge.getActiveBackendName()
                        ),
                        mode = "CONTEXT_REBUILT_MODEL_RETAINED"
                    )
                }
                if (timedOut.get() || nativeBridge.isAbortRequested()) {
                    _modelState.value = ModelLifecycleState.Error(
                        if (timedOut.get()) "TIMEOUT: Context-Neukonfiguration überschritt ${timeoutMs / 1_000L}s."
                        else errors[0] ?: "Context-Neukonfiguration wurde abgebrochen."
                    )
                    return null
                }
                Log.w("LLMLabViewModel", "Context-Neukonfiguration fehlgeschlagen; vollständiges Modellladen als Fallback: ${errors[0]}")
            }

            // CPU <-> Vulkan changes weight placement; only this path reloads the GGUF.
            nativeBridge.unloadModel()
            nativeBridge.prepareForOperation()
            _modelState.value = ModelLifecycleState.Initializing(
                null, "Backendwechsel: lade GGUF-Gewichte für neue Geräteplatzierung...", 0
            )
            val loaded = try {
                beginNativeCall()
                nativeBridge.loadModel(cacheModel.absolutePath, config, errors, timeoutMs)
            } finally {
                endNativeCall()
            }

            if (timedOut.get() || nativeBridge.isAbortRequested()) {
                if (loaded) nativeBridge.unloadModel()
                _modelState.value = ModelLifecycleState.Error(
                    if (timedOut.get()) "TIMEOUT: Modellinitialisierung des Testprofils überschritt ${timeoutMs / 1_000L}s."
                    else "Modellinitialisierung des Testprofils wurde abgebrochen."
                )
                return null
            }
            if (!loaded) {
                _modelState.value = ModelLifecycleState.Error(
                    errors[0] ?: "Das Modell konnte für dieses Testprofil nicht geladen werden."
                )
                return null
            }

            return PreparedTestProfile(
                ready = baseReady.copy(
                    initDurationMs = SystemClock.elapsedRealtime() - startedAt,
                    loadedConfig = config,
                    activeBackendName = nativeBridge.getActiveBackendName()
                ),
                mode = if (canKeepModelWeights) "MODEL_RELOADED_AFTER_CONTEXT_FAILURE" else "MODEL_RELOADED_BACKEND_SWITCH"
            )
        } catch (error: Throwable) {
            _modelState.value = ModelLifecycleState.Error(
                "Fehler beim Laden des Testprofils: ${error.message ?: error.javaClass.simpleName}"
            )
            return null
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun persistWorkflowRun(
        testCase: PromptTestCase,
        variant: InferenceTestVariant,
        config: InferenceConfig,
        metrics: NativeMetrics,
        response: String,
        activeBackend: String,
        succeeded: Boolean,
        error: String?,
        profileSetupDurationMs: Long,
        profileSetupMode: String,
        appCpuUsagePercent: Float? = null
    ) {
        dao.insertRun(TestRunEntity(
            testCategory = "${testCase.id}_${variant.id}",
            testTitle = "${testCase.title} – ${variant.label}",
            promptText = testCase.prompt,
            responseText = response,
            promptTokens = metrics.promptTokens,
            generatedTokens = metrics.generatedTokens,
            promptEvalDurationMs = metrics.promptEvalDurationMs,
            generationDurationMs = metrics.generationDurationMs,
            tokensPerSecond = metrics.tokensPerSecond,
            totalDurationMs = metrics.totalDurationMs,
            nCtx = config.nCtx,
            nBatch = config.nBatch,
            nUbatch = config.nUbatch,
            nThreads = config.nThreads,
            nThreadsBatch = config.nThreadsBatch,
            backendPreference = config.backendPreference.name,
            activeBackend = activeBackend,
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            maxTokens = config.maxTokens,
            appCpuUsagePercent = appCpuUsagePercent,
            profileSetupDurationMs = profileSetupDurationMs,
            profileSetupMode = profileSetupMode,
            processPssBytes = runCatching { Debug.getPss().toLong() * 1024L }.getOrDefault(0L),
            finishReason = metrics.finishReason,
            isSuccess = succeeded,
            errorMessage = error,
            javaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            nativeHeapBytes = nativeBridge.getAllocatedHeap(),
            modelName = lastModelSelection?.fileName ?: "Unbekanntes Modell",
            promptTokenizeDurationMs = metrics.promptTokenizeDurationMs,
            firstTokenDurationMs = metrics.firstTokenDurationMs,
            promptDecodeCalls = metrics.promptDecodeCalls,
            generationDecodeCalls = metrics.generationDecodeCalls
        ))
    }

    fun runLabTest(testCase: PromptTestCase) {
        val originalReady = _modelState.value as? ModelLifecycleState.Ready ?: return
        val originalConfig = _config.value
        if (!originalReady.loadedConfig.hasSameNativeContext(originalConfig)) {
            _labTestOutput.value = "Context-Parameter geändert. Bitte das Modell im Bereich Modell erneut laden, bevor ein Vergleich startet."
            return
        }
        launchEngineOperation {
            runLabTestInternal(testCase, originalReady, originalConfig)
        }
    }

    private suspend fun runLabTestInternal(
        testCase: PromptTestCase,
        originalReady: ModelLifecycleState.Ready,
        originalConfig: InferenceConfig,
        timeoutProfile: TimeoutConfig = timeoutConfig,
        backendOverride: BackendPreference? = null
    ): Boolean {

            val variants = AutomatedModelSuitePlan.variantsFor(testCase).map { variant ->
                backendOverride?.let { variant.copy(backendPreference = it) } ?: variant
            }
            _labActiveTest.value = testCase
            _labTestOutput.value = "Automatischer Vergleich gestartet: ${variants.size} native Profile × 1 Test."
            _labMetrics.value = null
            lastWorkflowBackendReport = ""
            val summaries = mutableListOf<String>()
            val backendEvidence = StringBuilder()
            var allSucceeded = true
            var currentReady: ModelLifecycleState.Ready? = originalReady

            for ((index, variant) in variants.withIndex()) {
                if (nativeEngineUnresponsive.get()) {
                    summaries += "Weitere Profile gestoppt: native Engine hat den Abbruch nicht quittiert. App-Neustart erforderlich."
                    break
                }

                val configForTest = variant.toConfig(originalConfig, testCase.maxTokens)
                _config.value = configForTest

                val profileSetupStartedAt = SystemClock.elapsedRealtime()
                val alreadyPrepared = currentReady
                    ?.takeIf { it.loadedConfig.hasSameNativeContext(configForTest) }
                    ?.let { PreparedTestProfile(it, "MODEL_REUSED") }
                val preparedProfile = alreadyPrepared ?: reloadCachedModelForTest(
                    currentReady ?: originalReady,
                    configForTest,
                    timeoutProfile
                )
                val profileSetupDurationMs = SystemClock.elapsedRealtime() - profileSetupStartedAt
                val readyForVariant = preparedProfile?.ready
                val profileSetupMode = preparedProfile?.mode ?: "PROFILE_PREPARATION_FAILED"

                if (readyForVariant == null) {
                    val error = (_modelState.value as? ModelLifecycleState.Error)?.errorMessage
                        ?: "Modellinitialisierung für dieses Profil fehlgeschlagen."
                    val failedMetrics = NativeMetrics(
                        promptTokens = 0,
                        generatedTokens = 0,
                        promptEvalDurationMs = 0,
                        generationDurationMs = 0,
                        totalDurationMs = 0,
                        decodeCalls = 0,
                        tokensPerSecond = 0f,
                        finishReason = "NATIVE_ERROR",
                        errorMessage = error
                    )
                    persistWorkflowRun(
                        testCase, variant, configForTest, failedMetrics, "",
                        nativeBridge.getActiveBackendName(), false, error,
                        profileSetupDurationMs, profileSetupMode
                    )
                    backendEvidence.appendLine("Profil ${index + 1}/${variants.size} – ${variant.id}: MODELLLADEN FEHLGESCHLAGEN")
                    backendEvidence.appendLine(error)
                    backendEvidence.appendLine(nativeBridge.getModelLoadLog())
                    backendEvidence.appendLine()
                    allSucceeded = false
                    summaries += "Profil ${index + 1}/${variants.size} – ${variant.label}: FEHLGESCHLAGEN – $error"
                    currentReady = null
                    continue
                }

                currentReady = readyForVariant
                _modelState.value = readyForVariant

                // This is the exact native llama.cpp model-load log for this profile,
                // not merely the selected backend label.
                val loadLog = nativeBridge.getModelLoadLog()
                val loadLines = loadLog.lineSequence().toList()
                val weightOffloadLines = loadLines.filter { line ->
                    line.contains("offloading", ignoreCase = true) ||
                        (line.contains("offloaded", ignoreCase = true) && line.contains("layers to GPU", ignoreCase = true))
                }
                val weightGpuLayerLines = loadLines.filter { line ->
                    line.contains("load_tensors: layer", ignoreCase = true) &&
                        line.contains("assigned to device Vulkan", ignoreCase = true)
                }
                val kvGpuLayerLines = loadLines.filter { line ->
                    line.contains("llama_kv_cache: layer", ignoreCase = true) &&
                        line.contains("dev = Vulkan", ignoreCase = true)
                }
                val backendDeviceLines = loadLines.filter { line ->
                    line.contains("using device Vulkan", ignoreCase = true) ||
                        line.contains("Vulkan_Host model buffer", ignoreCase = true) ||
                        line.contains("Vulkan0 KV buffer", ignoreCase = true) ||
                        line.contains("compute buffer", ignoreCase = true)
                }
                backendEvidence.appendLine("Profil ${index + 1}/${variants.size} – ${variant.id}: backend=${readyForVariant.activeBackendName}")
                backendEvidence.appendLine("Profilvorbereitung : $profileSetupMode · ${profileSetupDurationMs} ms")
                backendEvidence.appendLine("Modellgewichte GPU : ${weightGpuLayerLines.size} explizite Layer-Zuordnungen")
                backendEvidence.appendLine("KV-Cache GPU       : ${kvGpuLayerLines.size} explizite Layer-Zuordnungen")
                backendEvidence.appendLine("Gewicht-Offload    :")
                backendEvidence.appendLine(weightOffloadLines.ifEmpty { listOf("Keine explizite offloaded-X/Y-Layer-Meldung im erhaltenen Modell-Lade-Log.") }.joinToString("\n"))
                if (backendDeviceLines.isNotEmpty()) {
                    backendEvidence.appendLine("Backend-/Pufferhinweise:")
                    backendEvidence.appendLine(backendDeviceLines.joinToString("\n"))
                }
                if (weightGpuLayerLines.isNotEmpty()) {
                    backendEvidence.appendLine("GPU-Modellgewicht-Layer:")
                    backendEvidence.appendLine(weightGpuLayerLines.joinToString("\n"))
                }
                if (kvGpuLayerLines.isNotEmpty()) {
                    backendEvidence.appendLine("GPU-KV-Cache-Layer:")
                    backendEvidence.appendLine(kvGpuLayerLines.joinToString("\n"))
                }
                backendEvidence.appendLine()

                val localStream = StringBuilder()
                val callback = object : InferenceCallback {
                    override fun onToken(tokenText: String, tokenIndex: Int) {
                        localStream.append(tokenText)
                        _labTestOutput.value = "Profil ${index + 1}/${variants.size} – ${variant.label}\n" + localStream.toString()
                    }

                    override fun onProgress(progressPercent: Float, step: Int) {
                        _labTestOutput.value =
                            "Profil ${index + 1}/${variants.size} – ${variant.label}\nPrompt-Prefill: ${progressPercent.toInt()}% · Decode-Aufruf $step"
                    }
                }

                val cpuTimeBeforeMs = android.os.Process.getElapsedCpuTime()
                val wallTimeBeforeMs = SystemClock.elapsedRealtime()
                val metrics = executeInferenceWithWatchdog(
                    prompt = testCase.prompt,
                    config = configForTest,
                    callback = callback,
                    timeoutKind = testCase.timeoutKind,
                    requestedTimeoutMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                        testCase.timeoutSec.coerceAtLeast(1).toLong()
                    ),
                    timeoutProfile = timeoutProfile
                )
                val wallDurationMs = (SystemClock.elapsedRealtime() - wallTimeBeforeMs).coerceAtLeast(1L)
                val cpuDurationMs = (android.os.Process.getElapsedCpuTime() - cpuTimeBeforeMs).coerceAtLeast(0L)
                val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val cpuUsagePercent = (
                    cpuDurationMs.toFloat() * 100f / (wallDurationMs.toFloat() * cpuCores)
                ).coerceIn(0f, 100f)
                _labMetrics.value = metrics

                val response = localStream.toString()
                val inferenceCompleted =
                    (metrics.finishReason == "STOP_TOKEN" || metrics.finishReason == "MAX_TOKENS") &&
                        metrics.generatedTokens > 0
                val validation = PromptResultValidator.validate(testCase, response)
                val succeeded = inferenceCompleted && validation.passed
                if (!succeeded) allSucceeded = false
                val error = PromptResultValidator.failureReason(metrics.errorMessage, inferenceCompleted, validation)
                val actualBackend = nativeBridge.getActiveBackendName()

                persistWorkflowRun(
                    testCase, variant, configForTest, metrics, response,
                    actualBackend, succeeded, error,
                    profileSetupDurationMs, profileSetupMode, cpuUsagePercent
                )

                val prefillTokensPerSecond = if (metrics.promptEvalDurationMs > 0L) {
                    metrics.promptTokens * 1000f / metrics.promptEvalDurationMs.toFloat()
                } else 0f
                summaries += "Profil ${index + 1}/${variants.size} – ${variant.label}: ${if (succeeded) "BESTANDEN" else "FEHLGESCHLAGEN"} · ${metrics.finishReason}\n" +
                    "First Token ${metrics.firstTokenDurationMs} ms · Prefill ${metrics.promptEvalDurationMs} ms / ${metrics.promptTokens} Tokens (${"%.2f".format(prefillTokensPerSecond)} Tok/s) · " +
                    "Generierung ${metrics.generationDurationMs} ms / ${metrics.generatedTokens} Tokens · ${"%.2f".format(metrics.tokensPerSecond)} Tok/s\n" +
                    "Backend: $actualBackend · Prompt-Decode: ${metrics.promptDecodeCalls} · Generation-Decode: ${metrics.generationDecodeCalls} · Prozess-CPU: ${"%.1f".format(cpuUsagePercent)}%\n" +
                    "Profilvorbereitung: $profileSetupMode · ${profileSetupDurationMs} ms · PSS: ${runCatching { Debug.getPss() }.getOrDefault(0)} KB\n" +
                    "Inhalt: ${validation.details}" +
                    (error?.let { "\nFehler: $it" } ?: "")

                // Vulkan has no reliable native abort callback in this pinned llama.cpp build.
                // If it fails to acknowledge cancellation, don't touch the engine again.
                if (nativeEngineUnresponsive.get()) {
                    summaries += "Weitere Profile gestoppt: native Engine reagiert nicht auf Abbruchsignale. Bitte App neu starten."
                    break
                }
                if (metrics.finishReason == "ABORTED_BY_USER") {
                    summaries += "Weitere Profile wurden nach dem manuellen Abbruch nicht gestartet."
                    break
                }
            }

            lastWorkflowBackendReport = backendEvidence.toString().take(16384)
            _labTestOutput.value = summaries.joinToString("\n\n").ifBlank { "Kein Testprofil ausgeführt." }

            // Restore the exact user-selected context/backend after the comparison.
            _config.value = originalConfig
            if (!nativeEngineUnresponsive.get()) {
                val restoredProfile = currentReady
                    ?.takeIf { it.loadedConfig.hasSameNativeContext(originalConfig) }
                    ?.let { PreparedTestProfile(it, "MODEL_REUSED") }
                    ?: reloadCachedModelForTest(currentReady ?: originalReady, originalConfig, timeoutProfile)
                if (restoredProfile != null) {
                    _modelState.value = restoredProfile.ready
                    _labTestOutput.value += "\n\nUrsprüngliches Modellprofil wurde wiederhergestellt."
                } else {
                    val restoreError = (_modelState.value as? ModelLifecycleState.Error)?.errorMessage
                        ?: "Das ursprüngliche Modellprofil konnte nicht wiederhergestellt werden."
                    _modelState.value = ModelLifecycleState.Error(restoreError)
                    _labTestOutput.value += "\n\nWiederherstellung fehlgeschlagen: $restoreError"
                }
            } else {
                _modelState.value = ModelLifecycleState.Error(
                    "Die native Engine reagiert nicht mehr auf Abbruchsignale. Bitte LLM Lab neu starten."
                )
            }

            _labActiveTest.value = null
            return allSucceeded && !nativeEngineUnresponsive.get()
    }

    private suspend fun loadModelForAutomatedSuite(
        file: File,
        config: InferenceConfig,
        timeoutProfile: TimeoutConfig = timeoutConfig
    ): ModelLifecycleState.Ready? {
        if (!file.isFile || file.length() <= 0L) {
            _automatedSuite.value = _automatedSuite.value.copy(error = "Modell-Datei fehlt oder ist leer: ${file.name}")
            return null
        }
        val info = GgufMetadataParser.parse(file)
        if (!info.isValid || info.tensorCount <= 0L) {
            _automatedSuite.value = _automatedSuite.value.copy(
                error = "GGUF-Prüfung fehlgeschlagen für ${file.name}: ${info.errorMessage ?: "ungültiger Header"}"
            )
            return null
        }

        // llama.cpp is switched serially: unload first, then load the next GGUF file.
        nativeBridge.unloadModel()
        nativeBridge.prepareForOperation()
        _modelState.value = ModelLifecycleState.Initializing(
            null, "Automatischer Modellwechsel: ${file.name} wird geladen…", 0
        )
        val startedAt = SystemClock.elapsedRealtime()
        val timedOut = AtomicBoolean(false)
        val watchdog = TimeoutController(timeoutProfile).startModelInitializationWatchdog(
            scope = viewModelScope,
            requestedLimitMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                config.timeoutInitSec.coerceAtLeast(1).toLong()
            )
        ) {
            timedOut.set(true)
            nativeBridge.requestAbort()
            monitorNativeAbortResponsiveness(activeInferenceJob, "Automatischer Qwen-Modellwechsel")
        }
        val timeoutMs = watchdog.timeoutMs
        try {
            val errors = arrayOfNulls<String>(1)
            val loaded = try {
                beginNativeCall()
                nativeBridge.loadModel(file.absolutePath, config, errors, timeoutMs)
            } finally {
                endNativeCall()
            }
            if (timedOut.get() || nativeBridge.isAbortRequested()) {
                if (loaded) nativeBridge.unloadModel()
                _modelState.value = ModelLifecycleState.Error(
                    if (timedOut.get()) "TIMEOUT: ${file.name} konnte nicht innerhalb von ${timeoutMs / 1_000L} Sekunden geladen werden."
                    else "Modellwechsel zu ${file.name} wurde abgebrochen."
                )
                return null
            }
            if (!loaded) {
                _modelState.value = ModelLifecycleState.Error(errors[0] ?: "llama.cpp konnte ${file.name} nicht laden.")
                return null
            }

            val selection = ModelLifecycleState.ModelSelected(
                uri = Uri.fromFile(file),
                fileName = file.name,
                fileSizeBytes = file.length(),
                ggufInfo = info
            )
            lastModelSelection = selection
            localCachedModelUri = selection.uri
            activeModelFile = file
            _config.value = config
            val ready = ModelLifecycleState.Ready(
                fileName = file.name,
                fileSizeBytes = file.length(),
                ggufInfo = info,
                initDurationMs = SystemClock.elapsedRealtime() - startedAt,
                engineVersion = nativeBridge.getEngineVersion(),
                loadedConfig = config,
                activeBackendName = nativeBridge.getActiveBackendName()
            )
            _modelState.value = ready
            return ready
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _modelState.value = ModelLifecycleState.Error(
                "Modellwechsel zu ${file.name} fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            )
            return null
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun runQuickModelCheck(
        model: com.example.engine.HuggingFaceModel,
        ready: ModelLifecycleState.Ready,
        config: InferenceConfig,
        modelIndex: Int,
        modelCount: Int,
        timeoutProfile: TimeoutConfig = timeoutConfig
    ): Boolean {
        val prompt = AutomatedModelSuitePlan.quickPrompt
        val testCase = PromptTestCase(
            id = "AUTO_QUICK_CHECK",
            title = "Automatischer Qwen-Quick-Check",
            description = "Prüft Modellinitialisierung, Vulkan-Offload und kurze Token-Generierung.",
            prompt = prompt,
            expectedKeywords = listOf("BEREIT"),
            maxTokens = 12,
            timeoutSec = 90
        )
        val loadLog = nativeBridge.getModelLoadLog()
        val gpuWeightLayerCount = loadLog.lineSequence().count { line ->
            line.contains("load_tensors: layer", ignoreCase = true) &&
                line.contains("assigned to device Vulkan", ignoreCase = true)
        }
        val backendName = ready.activeBackendName
        val localText = StringBuilder()
        _labActiveTest.value = testCase
        _automatedSuite.value = _automatedSuite.value.copy(
            phase = "QUICK_INFERENCE",
            currentModelIndex = modelIndex,
            currentModelName = "${model.title} · ${model.parameters} · ${model.quantization}",
            message = "Quick-Check: Vulkan-Backend, GPU-Offload und Inferenz prüfen…"
        )
        val callback = object : InferenceCallback {
            override fun onToken(tokenText: String, tokenIndex: Int) {
                localText.append(tokenText)
                _labTestOutput.value = "Quick-Check $modelIndex/$modelCount – ${model.filename}\n$localText"
            }
            override fun onProgress(progressPercent: Float, step: Int) {
                _labTestOutput.value = "Quick-Check $modelIndex/$modelCount – ${model.filename}\nPrefill: ${progressPercent.toInt()}%"
            }
        }
        val quickConfig = config.copy(maxTokens = 12)
        val metrics = executeInferenceWithWatchdog(
            prompt,
            quickConfig,
            callback,
            InferenceTimeoutKind.PERFORMANCE,
            java.util.concurrent.TimeUnit.SECONDS.toMillis(testCase.timeoutSec.coerceAtLeast(1).toLong()),
            timeoutProfile = timeoutProfile
        )
        val passed = AutomatedModelSuitePlan.quickCheckPassed(
            activeBackendName = backendName,
            gpuWeightLayerCount = gpuWeightLayerCount,
            finishReason = metrics.finishReason,
            generatedTokens = metrics.generatedTokens,
            response = localText.toString()
        )
        val errors = buildList {
            if (!backendName.contains("Vulkan", ignoreCase = true)) add("Aktives Backend ist nicht Vulkan: $backendName")
            if (gpuWeightLayerCount <= 0) add("Keine explizite Vulkan-Layer-Zuordnung im Modell-Lade-Log.")
            if (metrics.finishReason !in listOf("STOP_TOKEN", "MAX_TOKENS") || metrics.generatedTokens <= 0) {
                add("Inferenz nicht sauber abgeschlossen: ${metrics.finishReason} ${metrics.errorMessage.orEmpty()}")
            }
            if (!passed && isEmpty()) add("Die Quick-Antwort entsprach nicht exakt BEREIT.")
        }
        val resultText = if (passed) {
            "BESTANDEN: ${model.filename} · Vulkan · $gpuWeightLayerCount GPU-Layer · ${metrics.tokensPerSecond} Tok/s"
        } else {
            "FEHLGESCHLAGEN: ${model.filename}\n${errors.joinToString("\n")}\nAntwort: ${localText.toString().take(120)}"
        }
        persistWorkflowRun(
            testCase = testCase,
            variant = InferenceTestVariant(
                id = "VULKAN_QUICK",
                label = "Vulkan Quick-Check",
                nBatch = config.nBatch,
                nUbatch = config.nUbatch,
                nThreads = config.nThreads,
                nThreadsBatch = config.nThreadsBatch,
                backendPreference = config.backendPreference
            ),
            config = quickConfig,
            metrics = metrics,
            response = localText.toString(),
            activeBackend = backendName,
            succeeded = passed,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" "),
            profileSetupDurationMs = ready.initDurationMs,
            profileSetupMode = "FULL_MODEL_LOAD_FOR_QUICK_CHECK"
        )
        _labTestOutput.value = resultText
        _labActiveTest.value = null
        return passed
    }

    fun runQwenQuickCheck() = runQwenModelSuite(automatedSuiteCoordinator.runQuick())

    fun runQwenQuickThenFull() = runQwenModelSuite(automatedSuiteCoordinator.runFull())

    fun runSingleModel(modelId: String) = runQwenModelSuite(automatedSuiteCoordinator.runSingle(modelId))

    private fun runQwenModelSuite(request: com.example.engine.SuiteRequest) {
        if (_automatedSuite.value.isRunning || huggingFaceDownloadJob?.isActive == true) return

        val previousReady = _modelState.value as? ModelLifecycleState.Ready
        val previousSelection = lastModelSelection
        val previousFile = activeModelFile
        val previousConfig = _config.value
        val suiteConfig = previousConfig.copy(
            selectedProfile = ProfilePreset.MAX_THROUGHPUT,
            nCtx = 2048,
            nBatch = 512,
            nUbatch = 64,
            nThreads = 4,
            nThreadsBatch = 8,
            backendPreference = if (request.mode == com.example.engine.SuiteMode.FULL) {
                BackendPreference.ACCELERATOR_REQUIRED
            } else {
                BackendPreference.AUTO
            },
            temperature = 0f,
            topK = 1,
            topP = 1f,
            maxTokens = 32
        )

        val models = HuggingFaceModelCatalog.models
        val requiredModels = if (request.mode == com.example.engine.SuiteMode.SINGLE) {
            models.filter { it.id == request.singleModel }
        } else {
            models
        }
        val modelDirectory = File(getApplication<Application>().filesDir, "models")
        automatedSuiteAbortRequested.set(false)

        // Register Android's user-initiated transfer jobs immediately from the visible
        // suite action, before waiting for any previous native engine operation to finish.
        try {
            if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                throw IOException("Der interne Modellordner konnte nicht angelegt werden.")
            }
            val remainingModelBytes = requiredModels.sumOf { model ->
                    val complete = File(modelDirectory, model.filename)
                    val partial = File(modelDirectory, "${model.filename}.part")
                    when {
                        findLocalHuggingFaceModel(model) != null -> 0L
                        partial.isFile -> (model.estimatedSizeBytes - partial.length()).coerceAtLeast(0L)
                        else -> model.estimatedSizeBytes
                    }
                }
                val storageReserveBytes = 128L * 1024L * 1024L
                val availableBytes = android.os.StatFs(modelDirectory.absolutePath).availableBytes
                if (availableBytes < remainingModelBytes + storageReserveBytes) {
                    throw IOException(
                        "Für die ausgewählten Qwen-Modelle fehlen ungefähr " +
                            "${(remainingModelBytes + storageReserveBytes - availableBytes) / (1024L * 1024L)} MB Speicher. " +
                            "Benötigt werden rund 3,1 GB für die Modelle plus Reserve."
                    )
                }

            HuggingFaceDownloadBackgroundManager.enqueue(getApplication(), requiredModels)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            _automatedSuite.value = AutomatedModelSuiteUiState(
                isRunning = false,
                phase = "ERROR",
                message = "Suite konnte nicht vorbereitet werden.",
                error = message
            )
            _labTestOutput.value = "Suite konnte nicht vorbereitet werden: $message"
            return
        }

        _automatedSuite.value = AutomatedModelSuiteUiState(
            isRunning = true,
            phase = "DOWNLOAD",
            currentModelIndex = 0,
            modelCount = requiredModels.size,
            message = "Hintergrund-Downloads eingeplant; maximal zwei gleichzeitig."
        )
        _labTestOutput.value = "Automatische Qwen-Testreihe gestartet."
        _config.value = suiteConfig

        launchEngineOperation {
            try {
                automatedSuiteCoordinator.run(
                    request = request,
                    models = models,
                    modelDirectory = modelDirectory,
                    config = suiteConfig,
                    fullRoutine = AutomatedModelSuitePlan.fullRoutine(),
                    isAborted = {
                        automatedSuiteAbortRequested.get() ||
                            nativeBridge.isAbortRequested() ||
                            nativeEngineUnresponsive.get()
                    },
                    onProgress = { progress: AutomatedModelSuiteProgress ->
                        _automatedSuite.value = AutomatedModelSuiteUiState(
                            isRunning = progress.isRunning,
                            phase = progress.phase,
                            currentModelIndex = progress.currentModelIndex,
                            modelCount = progress.modelCount,
                            currentModelName = progress.currentModelName,
                            quickPassed = progress.quickPassed,
                            quickFailed = progress.quickFailed,
                            fullTestsCompleted = progress.fullTestsCompleted,
                            fullTestsTotal = progress.fullTestsTotal,
                            message = progress.message,
                            error = progress.error
                        )
                    },
                    onOutput = { output -> _labTestOutput.value = output },
                    downloadModel = { model, index, count ->
                        val existing = findLocalHuggingFaceModel(model)
                        val file = existing ?: HuggingFaceDownloadBackgroundManager.download(getApplication(), model) { downloaded, total ->
                            if (automatedSuiteAbortRequested.get() || nativeBridge.isAbortRequested()) {
                                throw CancellationException("Automatische Testreihe wurde während des Downloads abgebrochen.")
                            }
                            _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                                modelId = model.id,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                isDownloading = true,
                                message = "Automatischer Download $index/$count"
                            )
                        }
                        _downloadedModelIds.value = _downloadedModelIds.value + model.id
                        _huggingFaceDownload.value = HuggingFaceDownloadUiState(
                            modelId = model.id,
                            downloadedBytes = file.length(),
                            totalBytes = file.length(),
                            isDownloading = false,
                            message = if (existing != null) {
                                "Vorhandene lokale Datei wiederverwendet; kein erneuter Download."
                            } else {
                                "Download und SHA-256-Prüfung erfolgreich."
                            }
                        )
                    },
                    loadModel = { file, config, timeout ->
                        val model = models.firstOrNull { it.filename == file.name }
                        val existing = model?.let { findLocalHuggingFaceModel(it) }
                        loadModelForAutomatedSuite(existing ?: file, config, timeout)
                    },
                    quickCheck = { model, ready, index, count, timeout ->
                        runQuickModelCheck(model, ready, suiteConfig, index, count, timeout)
                    },
                    runWorkflow = { testCase, ready, config, timeout ->
                        val runsBefore = dao.getCount()
                        val backendOverride = if (config.backendPreference == BackendPreference.AUTO) {
                            BackendPreference.AUTO
                        } else {
                            null
                        }
                        val succeeded = runLabTestInternal(
                            testCase, ready, config, timeoutProfile = timeout, backendOverride = backendOverride
                        )
                        val actualRuns = (dao.getCount() - runsBefore).coerceAtLeast(0)
                        AutomatedWorkflowResult(actualRuns = actualRuns, succeeded = succeeded)
                    }
                )
            } catch (error: Throwable) {
                val aborted = error is CancellationException ||
                    automatedSuiteAbortRequested.get() ||
                    nativeBridge.isAbortRequested()
                _automatedSuite.value = _automatedSuite.value.copy(
                    phase = if (aborted) "ABORTED" else "ERROR",
                    isRunning = false,
                    message = if (aborted) "Automatische Qwen-Testreihe abgebrochen." else "Automatische Qwen-Testreihe fehlgeschlagen.",
                    error = if (aborted) null else error.message ?: error.javaClass.simpleName
                )
                _labTestOutput.value = "Automatische Qwen-Testreihe fehlgeschlagen: ${error.message}"
            } finally {
                _labActiveTest.value = null
                if (!nativeEngineUnresponsive.get()) {
                    if (previousReady != null && previousFile?.isFile == true && previousSelection != null) {
                        val restored = loadModelForAutomatedSuite(previousFile, previousConfig)
                        if (restored != null) {
                            lastModelSelection = previousSelection
                            localCachedModelUri = previousSelection.uri
                            activeModelFile = previousFile
                            _config.value = previousConfig
                            _modelState.value = restored.copy(fileName = previousReady.fileName)
                            _labTestOutput.value += "\nUrsprüngliches Modell wurde wiederhergestellt."
                        } else {
                            _modelState.value = ModelLifecycleState.Error(
                                "Die Qwen-Testreihe ist beendet, aber das ursprüngliche Modell konnte nicht wiederhergestellt werden."
                            )
                        }
                    } else {
                        nativeBridge.unloadModel()
                        activeModelFile = previousFile
                        lastModelSelection = previousSelection
                        localCachedModelUri = previousSelection?.uri
                        _modelState.value = previousSelection ?: ModelLifecycleState.Unloaded
                        _config.value = previousConfig
                    }
                }
                _huggingFaceDownload.value = _huggingFaceDownload.value.copy(isDownloading = false)
                refreshDownloadedModels()
            }
        }
    }

    // ----------------- STRESSTEST & BENCHMARK -----------------

    fun run3xBenchmark() {
        val readyState = _modelState.value as? ModelLifecycleState.Ready ?: return
        val config = _config.value
        if (!readyState.loadedConfig.hasSameNativeContext(config)) {
            _benchmarkSummary.value = BenchmarkSummary(
                stopReason = "Context-Parameter geändert. Bitte das Modell neu laden, bevor der Benchmark startet."
            )
            return
        }

        launchEngineOperation {
            val prompt = "Antworte ausschließlich mit OK."
            val durations = mutableListOf<Long>()
            var successfulRuns = 0
            var failedRuns = 0
            var timeoutRuns = 0
            var tpsSum = 0f

            _benchmarkSummary.value = BenchmarkSummary(
                totalRuns = 3,
                isRunning = true,
                currentIteration = 0
            )

            var stopReason: String? = null
            for (iteration in 1..3) {
                if (nativeBridge.isAbortRequested()) {
                    stopReason = "Durch Nutzer abgebrochen."
                    break
                }
                _benchmarkSummary.value = _benchmarkSummary.value.copy(
                    currentIteration = iteration
                )
                val collectedText = StringBuilder()
                val callback = object : InferenceCallback {
                    override fun onToken(tokenText: String, tokenIndex: Int) {
                        collectedText.append(tokenText)
                    }
                    override fun onProgress(progressPercent: Float, step: Int) {
                        // Native Prompt-Eval Progress Callback.
                    }
                }

                val metrics = executeInferenceWithWatchdog(
                    prompt = prompt,
                    config = config,
                    callback = callback,
                    timeoutKind = InferenceTimeoutKind.PERFORMANCE,
                    requestedTimeoutMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                        config.timeoutStressTestSec.coerceAtLeast(1).toLong()
                    )
                )
                val inferenceCompleted =
                    (metrics.finishReason == "STOP_TOKEN" || metrics.finishReason == "MAX_TOKENS") &&
                        metrics.generatedTokens > 0
                val responseMatches = collectedText.toString().trim() == "OK"
                val succeeded = inferenceCompleted && responseMatches
                val runError = metrics.errorMessage?.takeIf { it.isNotBlank() } ?: if (inferenceCompleted && !responseMatches) {
                    "Erwartet wurde exakt OK; erhalten: ${collectedText.toString().take(120)}"
                } else null

                dao.insertRun(TestRunEntity(
                    testCategory = "BENCHMARK_STRESS",
                    testTitle = "Stabilitätstest Durchlauf #$iteration / 3",
                    promptText = prompt,
                    responseText = collectedText.toString(),
                    promptTokens = metrics.promptTokens,
                    generatedTokens = metrics.generatedTokens,
                    promptEvalDurationMs = metrics.promptEvalDurationMs,
                    generationDurationMs = metrics.generationDurationMs,
                    tokensPerSecond = metrics.tokensPerSecond,
                    totalDurationMs = metrics.totalDurationMs,
                    nCtx = config.nCtx,
                    nBatch = config.nBatch,
                    nUbatch = config.nUbatch,
                    nThreads = config.nThreads,
                    nThreadsBatch = config.nThreadsBatch,
                    backendPreference = config.backendPreference.name,
                    activeBackend = readyState.activeBackendName,
                    temperature = config.temperature,
                    topK = config.topK,
                    topP = config.topP,
                    maxTokens = config.maxTokens,
                    appCpuUsagePercent = _telemetry.value.appCpuUsagePercent,
                    finishReason = metrics.finishReason,
                    isSuccess = succeeded,
                    errorMessage = runError,
                    modelName = readyState.fileName,
                    promptTokenizeDurationMs = metrics.promptTokenizeDurationMs,
                    firstTokenDurationMs = metrics.firstTokenDurationMs,
                    promptDecodeCalls = metrics.promptDecodeCalls,
                    generationDecodeCalls = metrics.generationDecodeCalls
                ))

                if (!succeeded) {
                    failedRuns++
                    when (metrics.finishReason) {
                        "TIMEOUT" -> {
                            timeoutRuns++
                            stopReason = "Auto-Stop: Timeout nach ${config.timeoutStressTestSec}s; nativer Abbruch wurde ausgelöst."
                        }
                        "ABORTED_BY_USER" -> {
                            stopReason = "Durch Nutzer abgebrochen."
                        }
                        else -> {
                            stopReason = if (inferenceCompleted && !responseMatches) {
                                "Auto-Stop: Inhaltstest fehlgeschlagen; erwartet wurde exakt OK."
                            } else {
                                "Auto-Stop: Native Engine-Fehler: ${metrics.errorMessage ?: metrics.finishReason}"
                            }
                            if (metrics.finishReason == "NATIVE_ERROR") {
                                nativeBridge.unloadModel()
                                _modelState.value = ModelLifecycleState.Error(
                                    metrics.errorMessage ?: "Native Engine-Fehler"
                                )
                            }
                        }
                    }
                    break
                }

                successfulRuns++
                durations.add(metrics.totalDurationMs)
                tpsSum += metrics.tokensPerSecond

                val sortedDurations = durations.sorted()
                val averageDuration = sortedDurations.average().toLong()
                val p50Index = ((sortedDurations.size * 0.50).toInt()).coerceIn(0, sortedDurations.lastIndex)
                val p95Index = (kotlin.math.ceil(sortedDurations.size * 0.95).toInt() - 1)
                    .coerceIn(0, sortedDurations.lastIndex)

                _benchmarkSummary.value = _benchmarkSummary.value.copy(
                    successfulRuns = successfulRuns,
                    failedRuns = failedRuns,
                    timeoutRuns = timeoutRuns,
                    avgDurationMs = averageDuration,
                    p50DurationMs = sortedDurations[p50Index],
                    p95DurationMs = sortedDurations[p95Index],
                    avgTps = tpsSum / successfulRuns
                )

                delay(50)
            }

            _benchmarkSummary.value = _benchmarkSummary.value.copy(
                successfulRuns = successfulRuns,
                failedRuns = failedRuns,
                timeoutRuns = timeoutRuns,
                isRunning = false,
                stopReason = stopReason
            )

            if (!nativeEngineUnresponsive.get() && _modelState.value !is ModelLifecycleState.Error) {
                _modelState.value = readyState
            }
        }
    }

    // ----------------- DIAGNOSE-EXPORT -----------------

    fun exportReport(asJson: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            _exportStatus.value = "Diagnoseexport wird erstellt..."
            var report: String? = null
            val fileName = "llmlab_diagnose_${System.currentTimeMillis()}.${if (asJson) "json" else "txt"}"
            val mimeType = if (asJson) "application/json" else "text/plain"
            try {
                val runs = testRunHistory.value.take(200)
                val engineVer = nativeBridge.getEngineVersion()
                val state = _modelState.value
                val selectedModel = when (state) {
                    is ModelLifecycleState.Ready -> Triple(state.fileName, state.fileSizeBytes, state.ggufInfo)
                    is ModelLifecycleState.ModelSelected -> Triple(state.fileName, state.fileSizeBytes, state.ggufInfo)
                    else -> lastModelSelection?.let { Triple(it.fileName, it.fileSizeBytes, it.ggufInfo) }
                }
                val modelName = selectedModel?.first ?: "Nicht geladen"
                val modelSizeBytes = selectedModel?.second ?: 0L
                val modelSize = if (modelSizeBytes > 0L) "${modelSizeBytes / (1024 * 1024)} MB" else "Nicht verfügbar"
                val modelInfo = selectedModel?.third
                val modelStatus = when (state) {
                    is ModelLifecycleState.Ready -> "READY"
                    is ModelLifecycleState.ModelSelected -> "SELECTED_NOT_LOADED"
                    is ModelLifecycleState.Initializing -> "INITIALIZING"
                    is ModelLifecycleState.InferenceRunning -> "INFERENCE_RUNNING"
                    ModelLifecycleState.Aborting -> "ABORTING"
                    is ModelLifecycleState.Error -> "ERROR"
                    else -> "NOT_LOADED"
                }
                report = if (asJson) {
                    ExportManager.generateJson(context, runs, _config.value, _telemetry.value, engineVer, modelName, modelSize, modelInfo, modelStatus, _availableBackends.value, nativeBridge.getActiveBackendName(), nativeBridge.getModelLoadLog(), lastWorkflowBackendReport)
                } else {
                    ExportManager.generateTxt(context, runs, _config.value, _telemetry.value, engineVer, modelName, modelSize, modelInfo, modelStatus, _availableBackends.value, nativeBridge.getActiveBackendName(), nativeBridge.getModelLoadLog(), lastWorkflowBackendReport)
                }

                val savedPath = ExportManager.saveExportToDownloads(context, report!!, fileName, mimeType)
                withContext(Dispatchers.Main) {
                    _exportStatus.value = "Gespeichert: $savedPath"
                }
            } catch (error: Throwable) {
                Log.e("LLMLabViewModel", "Diagnoseexport fehlgeschlagen", error)
                val emergencyCopy = report?.let {
                    runCatching { ExportManager.saveInternalFallback(context, it, fileName) }.getOrNull()
                }
                val errorFile = File(context.filesDir, "llmlab-last-export-error.txt")
                runCatching {
                    FileOutputStream(errorFile).use { stream ->
                        val details = "LLM Lab Diagnoseexport fehlgeschlagen\n${System.currentTimeMillis()}\n${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}"
                        stream.write(details.toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                }
                val status = if (emergencyCopy != null) {
                    "Downloads fehlgeschlagen. Notfallkopie intern gespeichert: $emergencyCopy"
                } else {
                    "Export fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}. Fehlerprotokoll intern gespeichert."
                }
                withContext(Dispatchers.Main) { _exportStatus.value = status }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }

    override fun onCleared() {
        nativeBridge.requestAbort()
        // The native process is independent; killing it must not wait for a
        // stuck Binder/JNI call to return.
        nativeBridge.close()
        super.onCleared()
    }
}
