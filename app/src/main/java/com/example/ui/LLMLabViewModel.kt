package com.example.ui

import android.app.Application
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
import com.example.engine.InferenceCallback
import com.example.engine.InferenceConfig
import com.example.engine.LlamaNativeBridge
import com.example.engine.NativeMetrics
import com.example.engine.ProfilePreset
import com.example.engine.PromptResultValidator
import com.example.engine.PromptTestCatalog
import com.example.engine.PromptTestCase
import com.example.engine.SystemTelemetry
import com.example.engine.SystemTelemetryManager
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
import java.text.DecimalFormat

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
        val loadedConfig: InferenceConfig
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

class LLMLabViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dao = db.testRunDao()
    private val nativeBridge = LlamaNativeBridge()

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _modelState = MutableStateFlow<ModelLifecycleState>(ModelLifecycleState.Unloaded)
    val modelState: StateFlow<ModelLifecycleState> = _modelState.asStateFlow()

    private val _config = MutableStateFlow(InferenceConfig.fromPreset(ProfilePreset.BALANCED))
    val config: StateFlow<InferenceConfig> = _config.asStateFlow()

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
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    val testRunHistory = dao.getAllRuns().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private var activeInferenceJob: Job? = null
    @Volatile private var engineOperationGeneration: Long = 0L
    private val nativeEngineUnresponsive = AtomicBoolean(false)

    private companion object {
        const val NATIVE_ABORT_GRACE_MS = 10_000L
    }
    private var localCachedModelUri: Uri? = null
    private var lastModelSelection: ModelLifecycleState.ModelSelected? = null

    init {
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
    private fun monitorNativeAbortResponsiveness(operation: Job?, phase: String) {
        if (operation == null) return
        viewModelScope.launch(Dispatchers.Default) {
            delay(NATIVE_ABORT_GRACE_MS)
            if (operation.isActive) {
                nativeEngineUnresponsive.set(true)
                val message = "$phase: Der native JNI-Aufruf hat das Abbruchsignal nicht innerhalb von ${NATIVE_ABORT_GRACE_MS / 1000}s quittiert. Die Engine kann nicht sicher gewaltsam beendet werden. Bitte LLM Lab neu starten."
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
        timeoutSeconds: Int
    ): NativeMetrics {
        val timedOut = AtomicBoolean(false)
        val operation = activeInferenceJob
        val watchdog = viewModelScope.launch(Dispatchers.Default) {
            delay(timeoutSeconds.coerceAtLeast(1) * 1000L)
            timedOut.set(true)
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.requestAbort()
            monitorNativeAbortResponsiveness(operation, "Inferenz-Timeout")
        }

        return try {
            val nativeMetrics = nativeBridge.executeInference(prompt, config, callback)
            when {
                nativeEngineUnresponsive.get() -> nativeMetrics.copy(
                    finishReason = "NATIVE_ERROR",
                    errorMessage = "Die native Engine hat das Abbruchsignal nicht rechtzeitig quittiert. App-Neustart erforderlich."
                )
                timedOut.get() -> nativeMetrics.copy(
                    finishReason = "TIMEOUT",
                    errorMessage = listOfNotNull(
                        "Timeout nach ${timeoutSeconds.coerceAtLeast(1)}s; nativer Abbruch wurde angefordert.",
                        nativeMetrics.errorMessage
                    ).joinToString(" ")
                )
                else -> nativeMetrics
            }
        } finally {
            watchdog.cancel()
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
            timeoutStressTestSec = _config.value.timeoutStressTestSec
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

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // GGUF-Header und Metadaten vor dem Modellladen prüfen.
                val ggufInfo = GgufMetadataParser.parse(context, uri, fileSize)
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

    fun initializeModel() {
        val selected = lastModelSelection
            ?: (_modelState.value as? ModelLifecycleState.ModelSelected)
            ?: return

        launchEngineOperation {
            val startTime = System.currentTimeMillis()
            val timeoutSeconds = _config.value.timeoutInitSec.coerceAtLeast(1)
            val timedOut = AtomicBoolean(false)
            val watchdog = viewModelScope.launch(Dispatchers.Default) {
                delay(timeoutSeconds * 1000L)
                timedOut.set(true)
                _modelState.value = ModelLifecycleState.Aborting
                nativeBridge.requestAbort()
                monitorNativeAbortResponsiveness(activeInferenceJob, "Modellinitialisierung")
            }

            try {
                _modelState.value = ModelLifecycleState.Initializing(
                    null, "Bereite GGUF-Datei für native Engine vor...", 0
                )

                val context = getApplication<Application>()
                // filesDir statt cacheDir: Das Betriebssystem darf das große Modell
                // nicht wie einen entbehrlichen Cache-Eintrag entfernen.
                val cacheModel = File(context.filesDir, "active_model.gguf")
                val cachePart = File(context.filesDir, "active_model.gguf.part")
                val canReuse = localCachedModelUri == selected.uri &&
                    cacheModel.isFile &&
                    (selected.fileSizeBytes <= 0L || cacheModel.length() == selected.fileSizeBytes)

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

                _modelState.value = ModelLifecycleState.Initializing(
                    null, "Lade Modell und erstelle llama.cpp Context...", System.currentTimeMillis() - startTime
                )

                val configForLoad = _config.value
                val outError = arrayOfNulls<String>(1)
                val loadSuccess = nativeBridge.loadModel(cacheModel.absolutePath, configForLoad, outError)

                if (timedOut.get() || nativeBridge.isAbortRequested()) {
                    if (loadSuccess) nativeBridge.unloadModel()
                    _modelState.value = ModelLifecycleState.Error(
                        if (timedOut.get()) "TIMEOUT: Modellinitialisierung überschritt ${timeoutSeconds}s."
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
                        loadedConfig = configForLoad
                    )
                }
            } catch (e: Exception) {
                File(getApplication<Application>().filesDir, "active_model.gguf.part").delete()
                val message = when {
                    nativeEngineUnresponsive.get() -> "Die native Modellinitialisierung reagiert nicht mehr. Bitte App neu starten."
                    timedOut.get() -> "TIMEOUT: Modellinitialisierung überschritt ${timeoutSeconds}s."
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
        val assistantPlaceholderId = java.util.UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            isUser = false,
            text = "Native Inferenz wird gestartet...",
            isStreaming = true
        )
        _chatMessages.value = _chatMessages.value + userMsg + assistantPlaceholder

        val currentConfig = _config.value
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
                timeoutSeconds = currentConfig.timeoutNormalInferenceSec
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
        engineOperationGeneration++
        if (activeInferenceJob?.isActive == true) {
            // Das Signal läuft unabhängig vom Coroutine-Thread direkt zum nativen
            // Abort-Flag und wird dort während llama_decode geprüft.
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.requestAbort()
            monitorNativeAbortResponsiveness(activeInferenceJob, "Manueller Abbruch")
        }
        // Kein Job.cancel(): Ein blockierender JNI-Aufruf wird nur durch das
        // native Abort-Signal beendet. Die Operation räumt sich selbst auf.
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

    fun runLabTest(testCase: PromptTestCase) {
        val readyState = _modelState.value as? ModelLifecycleState.Ready ?: return

        launchEngineOperation {
            _labActiveTest.value = testCase
            _labTestOutput.value = "Testlauf gestartet..."
            _labMetrics.value = null

            val timeoutSeconds = if (testCase.id == "TEST_1_OK") {
                _config.value.timeoutShortTestSec
            } else {
                testCase.timeoutSec
            }
            val outcomes = mutableListOf<Triple<Int, String, com.example.engine.PromptValidationResult>>()
            var stopReason: String? = null

            for (repetition in 1..testCase.repetitionCount) {
                if (nativeBridge.isAbortRequested()) {
                    stopReason = "Testlauf durch Nutzer abgebrochen."
                    break
                }

                val localStream = StringBuilder()
                val callback = object : InferenceCallback {
                    override fun onToken(tokenText: String, tokenIndex: Int) {
                        localStream.append(tokenText)
                        val prefix = if (testCase.repetitionCount > 1) {
                            "Wiederholung ${repetition} / ${testCase.repetitionCount}\n"
                        } else ""
                        _labTestOutput.value = prefix + localStream.toString()
                    }

                    override fun onProgress(progressPercent: Float, step: Int) {
                        val prefix = if (testCase.repetitionCount > 1) {
                            "Wiederholung ${repetition} / ${testCase.repetitionCount}\n"
                        } else ""
                        _labTestOutput.value = prefix +
                            "Prompt-Evaluierung: ${progressPercent.toInt()}% (nativer Decode-Aufruf ${step})"
                    }
                }

                val metrics = executeInferenceWithWatchdog(
                    prompt = testCase.prompt,
                    config = _config.value,
                    callback = callback,
                    timeoutSeconds = timeoutSeconds
                )
                _labMetrics.value = metrics

                val response = localStream.toString()
                val inferenceCompleted =
                    (metrics.finishReason == "STOP_TOKEN" || metrics.finishReason == "MAX_TOKENS") &&
                        metrics.generatedTokens > 0
                val validation = PromptResultValidator.validate(testCase, response)
                outcomes.add(Triple(repetition, response, validation))
                val runSucceeded = inferenceCompleted && validation.passed
                val runError = metrics.errorMessage ?: if (inferenceCompleted && !validation.passed) {
                    "Inhaltliche Prüfung fehlgeschlagen: ${validation.details}"
                } else null

                dao.insertRun(TestRunEntity(
                    testCategory = testCase.id,
                    testTitle = if (testCase.repetitionCount > 1) {
                        "${testCase.title} (#$repetition)"
                    } else testCase.title,
                    promptText = testCase.prompt,
                    responseText = response,
                    promptTokens = metrics.promptTokens,
                    generatedTokens = metrics.generatedTokens,
                    promptEvalDurationMs = metrics.promptEvalDurationMs,
                    generationDurationMs = metrics.generationDurationMs,
                    tokensPerSecond = metrics.tokensPerSecond,
                    totalDurationMs = metrics.totalDurationMs,
                    nCtx = _config.value.nCtx,
                    nBatch = _config.value.nBatch,
                    nUbatch = _config.value.nUbatch,
                    nThreads = _config.value.nThreads,
                    finishReason = metrics.finishReason,
                    isSuccess = runSucceeded,
                    errorMessage = runError,
                    modelName = readyState.fileName,
                    promptTokenizeDurationMs = metrics.promptTokenizeDurationMs,
                    firstTokenDurationMs = metrics.firstTokenDurationMs,
                    promptDecodeCalls = metrics.promptDecodeCalls,
                    generationDecodeCalls = metrics.generationDecodeCalls
                ))

                if (!inferenceCompleted) {
                    stopReason = "Inferenz nicht erfolgreich: ${metrics.finishReason}. ${metrics.errorMessage.orEmpty()}"
                    if (metrics.finishReason == "NATIVE_ERROR") {
                        nativeBridge.unloadModel()
                        _modelState.value = ModelLifecycleState.Error(
                            metrics.errorMessage ?: "Native Engine-Fehler"
                        )
                    } else if (!nativeEngineUnresponsive.get()) {
                        _modelState.value = readyState
                    }
                    break
                }

                // Inhaltliche Abweichungen im 10x-Test protokollieren, aber alle
                // zehn realen Modellläufe ausführen, um Varianz sichtbar zu machen.
                if (!validation.passed && testCase.repetitionCount == 1) break
            }

            if (outcomes.isNotEmpty()) {
                if (testCase.repetitionCount > 1) {
                    val passedCount = outcomes.count { it.third.passed }
                    val distinctAnswers = outcomes.map { it.second.trim() }.distinct().size
                    val details = outcomes.joinToString("\n\n") { (number, response, validation) ->
                        val resultLabel = if (validation.passed) "BESTANDEN" else "FEHLGESCHLAGEN"
                        "Durchlauf #$number [$resultLabel]: ${validation.details}\nAntwort: $response"
                    }
                    val earlyStop = if (outcomes.size < testCase.repetitionCount) {
                        "\n\nVorzeitig beendet nach ${outcomes.size}/${testCase.repetitionCount} Durchläufen."
                    } else ""
                    _labTestOutput.value =
                        "10er-Wiederholung: $passedCount/${outcomes.size} inhaltlich korrekt\n" +
                            "Unterschiedliche Antworten: $distinctAnswers$earlyStop\n\n$details"
                } else {
                    val outcome = outcomes.last()
                    val label = if (outcome.third.passed) "BESTANDEN" else "FEHLGESCHLAGEN"
                    _labTestOutput.value =
                        "Inhaltstest: $label\n${outcome.third.details}\n\nModellantwort:\n${outcome.second}"
                }
            } else if (stopReason != null) {
                _labTestOutput.value = stopReason
            }

            if (stopReason != null && _labTestOutput.value.isNotBlank()) {
                _labTestOutput.value += "\n\n$stopReason"
            }

            _labActiveTest.value = null
            if (!nativeEngineUnresponsive.get() && _modelState.value !is ModelLifecycleState.Error) {
                _modelState.value = readyState
            }
        }
    }

    // ----------------- STRESSTEST & BENCHMARK -----------------

    fun run20xBenchmark() {
        val readyState = _modelState.value as? ModelLifecycleState.Ready ?: return

        launchEngineOperation {
            val prompt = "Antworte ausschließlich mit OK."
            val config = _config.value
            val durations = mutableListOf<Long>()
            var successfulRuns = 0
            var failedRuns = 0
            var timeoutRuns = 0
            var tpsSum = 0f

            _benchmarkSummary.value = BenchmarkSummary(
                totalRuns = 20,
                isRunning = true,
                currentIteration = 0
            )

            var stopReason: String? = null
            for (iteration in 1..20) {
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
                    timeoutSeconds = config.timeoutStressTestSec
                )
                val inferenceCompleted =
                    (metrics.finishReason == "STOP_TOKEN" || metrics.finishReason == "MAX_TOKENS") &&
                        metrics.generatedTokens > 0
                val responseMatches = collectedText.toString().trim() == "OK"
                val succeeded = inferenceCompleted && responseMatches
                val runError = metrics.errorMessage ?: if (inferenceCompleted && !responseMatches) {
                    "Erwartet wurde exakt OK; erhalten: ${collectedText.toString().take(120)}"
                } else null

                dao.insertRun(TestRunEntity(
                    testCategory = "BENCHMARK_STRESS",
                    testTitle = "Stresstest Durchlauf #$iteration / 20",
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
            _exportStatus.value = "Diagnoseexport wird vorbereitet..."
            try {
                val runs = testRunHistory.value
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
                val fileName = "llmlab_diagnose_${System.currentTimeMillis()}.${if (asJson) "json" else "txt"}"
                val report = if (asJson) {
                    ExportManager.generateJson(context = context, runs = runs, config = _config.value, telemetry = _telemetry.value, engineVersion = engineVer, modelName = modelName, modelSizeFormatted = modelSize, modelInfo = modelInfo, modelStatus = modelStatus)
                } else {
                    ExportManager.generateTxt(context = context, runs = runs, config = _config.value, telemetry = _telemetry.value, engineVersion = engineVer, modelName = modelName, modelSizeFormatted = modelSize, modelInfo = modelInfo, modelStatus = modelStatus)
                }
                withContext(Dispatchers.Main) {
                    ExportManager.shareExport(context, report, fileName, if (asJson) "application/json" else "text/plain")
                    _exportStatus.value = "Exportdatei erstellt. Android-Teilen-Dialog geöffnet."
                }
            } catch (error: Exception) {
                Log.e("LLMLabViewModel", "Diagnoseexport fehlgeschlagen", error)
                val errorFile = File(context.filesDir, "llmlab-last-export-error.txt")
                runCatching {
                    errorFile.writeText("LLM Lab Diagnoseexport fehlgeschlagen\n${System.currentTimeMillis()}\n${error.stackTraceToString()}", Charsets.UTF_8)
                }
                withContext(Dispatchers.Main) {
                    _exportStatus.value = "Export fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}. Fehlerdetails lokal gespeichert."
                }
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
        val runningOperation = activeInferenceJob
        Thread({
            try {
                kotlinx.coroutines.runBlocking {
                    runningOperation?.join()
                }
            } finally {
                nativeBridge.close()
            }
        }, "llmlab-native-cleanup").apply {
            isDaemon = true
            start()
        }
        super.onCleared()
    }
}
