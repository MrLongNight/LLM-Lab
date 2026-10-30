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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
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
        val progressPercent: Float,
        val phase: String,
        val elapsedMs: Long
    ) : ModelLifecycleState()
    data class Ready(
        val fileName: String,
        val fileSizeBytes: Long,
        val ggufInfo: GgufInfo,
        val initDurationMs: Long,
        val engineVersion: String
    ) : ModelLifecycleState()
    data class InferenceRunning(
        val prompt: String,
        val streamingText: String,
        val tokenCount: Int,
        val tps: Float,
        val elapsedMs: Long
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

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage(
            isUser = false,
            text = "Willkommen im LLM Lab – Mobile Inference Testbench.\nBitte wähle unter 'Modell' die Datei 'qwen2.5-1.5b-instruct-q4_k_m.gguf' aus, um echte On-Device Inferenz zu starten."
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

    val testRunHistory = dao.getAllRuns().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private var activeInferenceJob: Job? = null
    private var localCachedModelFile: File? = null

    init {
        // Starte periodische Telemetrieerfassung
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                _telemetry.value = SystemTelemetryManager.capture(getApplication())
                delay(3000)
            }
        }
    }

    fun selectTab(index: Int) {
        _currentTab.value = index
    }

    fun updateConfig(newConfig: InferenceConfig) {
        _config.value = newConfig
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
                var fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
                var fileSize = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // GGUF-Header Metadaten und Magic-Signatur parsen
                val ggufInfo = GgufMetadataParser.parse(context, uri, fileSize)

                _modelState.value = ModelLifecycleState.ModelSelected(
                    uri = uri,
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    ggufInfo = ggufInfo
                )
            } catch (e: Exception) {
                _modelState.value = ModelLifecycleState.Error("Fehler bei Modellauswahl: ${e.message}")
            }
        }
    }

    fun initializeModel() {
        val selected = _modelState.value as? ModelLifecycleState.ModelSelected ?: return

        activeInferenceJob?.cancel()
        activeInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            _modelState.value = ModelLifecycleState.Initializing(5f, "Kopiere GGUF in lokalen App-Speicher...", 0)

            val timeoutMs = _config.value.timeoutInitSec * 1000L
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    val context = getApplication<Application>()
                    // Für direkten JNI/POSIX fopen Pfad kopieren wir oder verlinken die Datei im Cache
                    val cacheModel = File(context.cacheDir, "active_model.gguf")
                    if (!cacheModel.exists() || cacheModel.length() != selected.fileSizeBytes) {
                        _modelState.value = ModelLifecycleState.Initializing(20f, "Streaming von Dateideskriptor...", 200)
                        context.contentResolver.openInputStream(selected.uri)?.use { input ->
                            FileOutputStream(cacheModel).use { output ->
                                val buffer = ByteArray(1024 * 512)
                                var read: Int
                                var totalCopied = 0L
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    totalCopied += read
                                    if (selected.fileSizeBytes > 0) {
                                        val pct = 20f + (totalCopied.toFloat() / selected.fileSizeBytes) * 60f
                                        _modelState.value = ModelLifecycleState.Initializing(
                                            pct,
                                            "Übertrage ${totalCopied / (1024 * 1024)} MB...",
                                            System.currentTimeMillis() - startTime
                                        )
                                    }
                                }
                            }
                        }
                    }
                    localCachedModelFile = cacheModel

                    _modelState.value = ModelLifecycleState.Initializing(85f, "Initialisiere native llama.cpp Engine...", System.currentTimeMillis() - startTime)

                    val outError = arrayOfNulls<String>(1)
                    val loadSuccess = nativeBridge.loadModel(cacheModel.absolutePath, _config.value, outError)

                    if (!loadSuccess) {
                        ModelLifecycleState.Error(outError[0] ?: "Native Modellinitialisierung fehlgeschlagen")
                    } else {
                        val initDuration = System.currentTimeMillis() - startTime
                        ModelLifecycleState.Ready(
                            fileName = selected.fileName,
                            fileSizeBytes = selected.fileSizeBytes,
                            ggufInfo = selected.ggufInfo,
                            initDurationMs = initDuration,
                            engineVersion = nativeBridge.getEngineVersion()
                        )
                    }
                } catch (e: Exception) {
                    ModelLifecycleState.Error("Initialisierungsfehler: ${e.message}")
                }
            }

            if (result == null) {
                nativeBridge.requestAbort()
                _modelState.value = ModelLifecycleState.Error("Timeout: Modellinitialisierung überschritt ${_config.value.timeoutInitSec}s")
            } else {
                _modelState.value = result
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.unloadModel()
            delay(150)
            _modelState.value = ModelLifecycleState.Unloaded
        }
    }

    // ----------------- INFERENZ & CHAT -----------------

    fun sendChatMessage(promptText: String) {
        val readyState = _modelState.value as? ModelLifecycleState.Ready
        if (readyState == null) {
            _chatMessages.value = _chatMessages.value + ChatMessage(
                isUser = false,
                text = "Fehler: Kein Modell geladen. Bitte initialisiere zuerst das GGUF-Modell."
            )
            return
        }

        val userMsg = ChatMessage(isUser = true, text = promptText)
        val assistantPlaceholderId = java.util.UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantPlaceholderId,
            isUser = false,
            text = "Denke nach...",
            isStreaming = true
        )

        _chatMessages.value = _chatMessages.value + userMsg + assistantPlaceholder

        val currentConfig = _config.value
        val timeoutMs = currentConfig.timeoutNormalInferenceSec * 1000L

        activeInferenceJob?.cancel()
        activeInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var streamedResponse = ""
            var tokenCounter = 0

            _modelState.value = ModelLifecycleState.InferenceRunning(
                prompt = promptText,
                streamingText = "",
                tokenCount = 0,
                tps = 0f,
                elapsedMs = 0
            )

            val callback = object : InferenceCallback {
                override fun onToken(tokenText: String, tokenIndex: Int) {
                    streamedResponse += tokenText
                    tokenCounter++
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                    val tps = (tokenCounter * 1000f) / elapsed

                    _modelState.value = ModelLifecycleState.InferenceRunning(
                        prompt = promptText,
                        streamingText = streamedResponse,
                        tokenCount = tokenCounter,
                        tps = tps,
                        elapsedMs = elapsed
                    )

                    // Chatnachricht live updaten
                    _chatMessages.value = _chatMessages.value.map { msg ->
                        if (msg.id == assistantPlaceholderId) {
                            msg.copy(text = streamedResponse, isStreaming = true)
                        } else msg
                    }
                }

                override fun onProgress(progressPercent: Float, step: Int) {
                    // Prompt-Eval Fortschritt
                }
            }

            val metricsResult = withTimeoutOrNull(timeoutMs) {
                nativeBridge.executeInference(promptText, currentConfig, callback)
            }

            val finalMetrics = metricsResult ?: NativeMetrics(
                promptTokens = promptText.length / 4,
                generatedTokens = tokenCounter,
                promptEvalDurationMs = 0,
                generationDurationMs = System.currentTimeMillis() - startTime,
                totalDurationMs = System.currentTimeMillis() - startTime,
                decodeCalls = tokenCounter,
                tokensPerSecond = if (tokenCounter > 0) (tokenCounter * 1000f) / (System.currentTimeMillis() - startTime) else 0f,
                finishReason = "TIMEOUT",
                errorMessage = "Inferenz-Timeout nach ${currentConfig.timeoutNormalInferenceSec}s überschritten"
            )

            // Chatnachricht finalisieren
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == assistantPlaceholderId) {
                    msg.copy(
                        text = if (streamedResponse.isEmpty()) (finalMetrics.errorMessage ?: "Keine Antwort erhalten") else streamedResponse,
                        metrics = finalMetrics,
                        isStreaming = false
                    )
                } else msg
            }

            // In Room Datenbank protokollieren
            val testRun = TestRunEntity(
                testCategory = "CHAT",
                testTitle = "Chat Inferenz",
                promptText = promptText,
                responseText = streamedResponse,
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
                isSuccess = finalMetrics.finishReason == "STOP_TOKEN" || finalMetrics.finishReason == "MAX_TOKENS",
                errorMessage = finalMetrics.errorMessage,
                javaHeapBytes = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                nativeHeapBytes = nativeBridge.getAllocatedHeap(),
                modelName = readyState.fileName
            )
            dao.insertRun(testRun)

            // Modellstatus zurück auf Ready
            _modelState.value = readyState
        }
    }

    fun requestAbort() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelState.value = ModelLifecycleState.Aborting
            nativeBridge.requestAbort()
            activeInferenceJob?.cancel()
            delay(100)
            val ready = _modelState.value as? ModelLifecycleState.Ready
            if (ready != null) {
                _modelState.value = ready
            } else if (localCachedModelFile?.exists() == true) {
                _modelState.value = ModelLifecycleState.Ready(
                    fileName = localCachedModelFile?.name ?: "Modell",
                    fileSizeBytes = localCachedModelFile?.length() ?: 0L,
                    ggufInfo = GgufInfo(isValid = true, magic = "GGUF", version = 3, tensorCount = 0, kvCount = 0),
                    initDurationMs = 0,
                    engineVersion = nativeBridge.getEngineVersion()
                )
            } else {
                _modelState.value = ModelLifecycleState.Unloaded
            }
        }
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
        _labActiveTest.value = testCase
        _labTestOutput.value = "Testlauf gestartet..."
        _labMetrics.value = null

        activeInferenceJob?.cancel()
        activeInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val isShortTest = testCase.id == "TEST_1_OK"
            val timeoutSec = if (isShortTest) _config.value.timeoutShortTestSec else testCase.timeoutSec
            val timeoutMs = timeoutSec * 1000L

            for (rep in 1..testCase.repetitionCount) {
                var localStream = ""
                val startTime = System.currentTimeMillis()

                val callback = object : InferenceCallback {
                    override fun onToken(tokenText: String, tokenIndex: Int) {
                        localStream += tokenText
                        _labTestOutput.value = if (testCase.repetitionCount > 1) {
                            "Wiederholung $rep / ${testCase.repetitionCount}:\n$localStream"
                        } else localStream
                    }

                    override fun onProgress(progressPercent: Float, step: Int) {
                        // Progress
                    }
                }

                val metrics = withTimeoutOrNull(timeoutMs) {
                    nativeBridge.executeInference(testCase.prompt, _config.value, callback)
                } ?: NativeMetrics(
                    promptTokens = testCase.prompt.length / 4,
                    generatedTokens = 0,
                    promptEvalDurationMs = 0,
                    generationDurationMs = System.currentTimeMillis() - startTime,
                    totalDurationMs = System.currentTimeMillis() - startTime,
                    decodeCalls = 0,
                    tokensPerSecond = 0f,
                    finishReason = "TIMEOUT",
                    errorMessage = "Timeout nach ${timeoutSec}s"
                )

                _labMetrics.value = metrics

                // Room Persistenz
                val isSuccess = metrics.finishReason == "STOP_TOKEN" || metrics.finishReason == "MAX_TOKENS"
                dao.insertRun(TestRunEntity(
                    testCategory = testCase.id,
                    testTitle = if (testCase.repetitionCount > 1) "${testCase.title} (#$rep)" else testCase.title,
                    promptText = testCase.prompt,
                    responseText = localStream,
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
                    isSuccess = isSuccess,
                    errorMessage = metrics.errorMessage,
                    modelName = readyState.fileName
                ))

                if (nativeBridge.isAbortRequested()) break
            }
            _labActiveTest.value = null
        }
    }

    // ----------------- STRESSTEST & BENCHMARK -----------------

    fun run20xBenchmark() {
        val readyState = _modelState.value as? ModelLifecycleState.Ready ?: return

        activeInferenceJob?.cancel()
        activeInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val prompt = "Antworte ausschließlich mit OK."
            val durations = mutableListOf<Long>()
            var successCount = 0
            var errorCount = 0
            var timeoutCount = 0
            var tpsSum = 0f

            _benchmarkSummary.value = BenchmarkSummary(
                totalRuns = 20,
                isRunning = true,
                currentIteration = 0
            )

            val timeoutMs = _config.value.timeoutStressTestSec * 1000L

            for (i in 1..20) {
                if (nativeBridge.isAbortRequested()) {
                    _benchmarkSummary.value = _benchmarkSummary.value.copy(
                        isRunning = false,
                        stopReason = "Durch Nutzer abgebrochen"
                    )
                    break
                }

                _benchmarkSummary.value = _benchmarkSummary.value.copy(
                    currentIteration = i
                )

                val startTime = System.currentTimeMillis()
                var collectedText = ""

                val callback = object : InferenceCallback {
                    override fun onToken(tokenText: String, tokenIndex: Int) {
                        collectedText += tokenText
                    }
                    override fun onProgress(progressPercent: Float, step: Int) {}
                }

                val metrics = withTimeoutOrNull(timeoutMs) {
                    nativeBridge.executeInference(prompt, _config.value, callback)
                }

                if (metrics == null) {
                    timeoutCount++
                    errorCount++
                    dao.insertRun(TestRunEntity(
                        testCategory = "BENCHMARK_STRESS",
                        testTitle = "Stresstest Durchlauf #$i / 20",
                        promptText = prompt,
                        responseText = collectedText,
                        promptTokens = prompt.length / 4,
                        generatedTokens = 0,
                        promptEvalDurationMs = 0,
                        generationDurationMs = timeoutMs,
                        tokensPerSecond = 0f,
                        totalDurationMs = timeoutMs,
                        nCtx = _config.value.nCtx,
                        nBatch = _config.value.nBatch,
                        nUbatch = _config.value.nUbatch,
                        nThreads = _config.value.nThreads,
                        finishReason = "TIMEOUT",
                        isSuccess = false,
                        errorMessage = "Timeout nach ${_config.value.timeoutStressTestSec}s",
                        modelName = readyState.fileName
                    ))
                    // Auto-Stop bei 3 Timeouts hintereinander
                    if (timeoutCount >= 3) {
                        _benchmarkSummary.value = _benchmarkSummary.value.copy(
                            isRunning = false,
                            stopReason = "Auto-Stop: Zu viele Timeouts hintereinander"
                        )
                        break
                    }
                } else if (metrics.finishReason == "NATIVE_ERROR") {
                    errorCount++
                    dao.insertRun(TestRunEntity(
                        testCategory = "BENCHMARK_STRESS",
                        testTitle = "Stresstest Durchlauf #$i / 20",
                        promptText = prompt,
                        responseText = collectedText,
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
                        finishReason = "NATIVE_ERROR",
                        isSuccess = false,
                        errorMessage = metrics.errorMessage,
                        modelName = readyState.fileName
                    ))
                    // Auto-Stop bei nicht behebbarem nativem Fehler
                    _benchmarkSummary.value = _benchmarkSummary.value.copy(
                        isRunning = false,
                        stopReason = "Auto-Stop: Nicht behebbarer nativer Engine-Fehler: ${metrics.errorMessage}"
                    )
                    break
                } else {
                    successCount++
                    durations.add(metrics.totalDurationMs)
                    tpsSum += metrics.tokensPerSecond

                    dao.insertRun(TestRunEntity(
                        testCategory = "BENCHMARK_STRESS",
                        testTitle = "Stresstest Durchlauf #$i / 20",
                        promptText = prompt,
                        responseText = collectedText,
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
                        isSuccess = true,
                        modelName = readyState.fileName
                    ))
                }

                // Statistiken berechnen
                durations.sort()
                val avgDuration = if (durations.isNotEmpty()) durations.average().toLong() else 0L
                val p50 = if (durations.isNotEmpty()) durations[durations.size / 2] else 0L
                val p95 = if (durations.isNotEmpty()) durations[minOf((durations.size * 0.95).toInt(), durations.size - 1)] else 0L
                val avgTps = if (successCount > 0) tpsSum / successCount else 0f

                _benchmarkSummary.value = _benchmarkSummary.value.copy(
                    successfulRuns = successCount,
                    failedRuns = errorCount,
                    timeoutRuns = timeoutCount,
                    avgDurationMs = avgDuration,
                    p50DurationMs = p50,
                    p95DurationMs = p95,
                    avgTps = avgTps
                )

                delay(50)
            }

            _benchmarkSummary.value = _benchmarkSummary.value.copy(isRunning = false)
        }
    }

    // ----------------- DIAGNOSE-EXPORT -----------------

    fun exportReport(asJson: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val runs = testRunHistory.value
            val engineVer = nativeBridge.getEngineVersion()
            val state = _modelState.value
            val modelName = if (state is ModelLifecycleState.Ready) state.fileName else "qwen2.5-1.5b-instruct-q4_k_m.gguf"
            val modelSize = if (state is ModelLifecycleState.Ready) "${state.fileSizeBytes / (1024 * 1024)} MB" else "~1.12 GB"

            if (asJson) {
                val json = ExportManager.generateJson(
                    context = context,
                    runs = runs,
                    config = _config.value,
                    telemetry = _telemetry.value,
                    engineVersion = engineVer,
                    modelName = modelName,
                    modelSizeFormatted = modelSize
                )
                withContext(Dispatchers.Main) {
                    ExportManager.shareExport(context, json, "llmlab_diagnose_${System.currentTimeMillis()}.json", "application/json")
                }
            } else {
                val txt = ExportManager.generateTxt(
                    context = context,
                    runs = runs,
                    config = _config.value,
                    telemetry = _telemetry.value,
                    engineVersion = engineVer,
                    modelName = modelName,
                    modelSizeFormatted = modelSize
                )
                withContext(Dispatchers.Main) {
                    ExportManager.shareExport(context, txt, "llmlab_diagnose_${System.currentTimeMillis()}.txt", "text/plain")
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
        super.onCleared()
        nativeBridge.close()
    }
}
