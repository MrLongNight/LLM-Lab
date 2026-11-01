package com.example.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

enum class SuiteMode { SINGLE, QUICK, FULL }

data class SuiteRequest(
    val mode: SuiteMode,
    val singleModel: String? = null,
    val timeout: TimeoutConfig = TimeoutConfig.MOBILE,
    val onProgress: (String) -> Unit = {}
)

data class AutomatedModelSuiteProgress(
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

data class AutomatedWorkflowResult(
    val actualRuns: Int,
    val succeeded: Boolean
)

/**
 * Owns the ordered Qwen test flow. Android/native operations are supplied by the
 * ViewModel, while the download -> quick gate -> full matrix state machine lives here.
 */
class AutomatedModelSuiteCoordinator {

    fun runQuick(): SuiteRequest = SuiteRequest(SuiteMode.QUICK)

    fun runSingle(modelId: String): SuiteRequest =
        SuiteRequest(SuiteMode.SINGLE, singleModel = modelId)

    fun runFull(): SuiteRequest =
        SuiteRequest(SuiteMode.FULL, timeout = TimeoutConfig.LEGACY)

    suspend fun <Ready : Any> run(
        request: SuiteRequest,
        models: List<HuggingFaceModel>,
        modelDirectory: File,
        config: InferenceConfig,
        fullRoutine: List<PromptTestCase>,
        isAborted: () -> Boolean,
        onProgress: (AutomatedModelSuiteProgress) -> Unit,
        onOutput: (String) -> Unit,
        downloadModel: suspend (HuggingFaceModel, Int, Int) -> Unit,
        loadModel: suspend (File, InferenceConfig, TimeoutConfig) -> Ready?,
        quickCheck: suspend (HuggingFaceModel, Ready, Int, Int, TimeoutConfig) -> Boolean,
        runWorkflow: suspend (PromptTestCase, Ready, InferenceConfig, TimeoutConfig) -> AutomatedWorkflowResult
    ) {
        val selectedModels = when (request.mode) {
            SuiteMode.SINGLE -> models.filter { it.id == request.singleModel }
            SuiteMode.QUICK, SuiteMode.FULL -> models
        }
        val timeout = if (request.mode == SuiteMode.FULL) TimeoutConfig.LEGACY else request.timeout

        if (selectedModels.isEmpty()) {
            val message = if (request.mode == SuiteMode.SINGLE) {
                "SINGLE fehlgeschlagen: Modell '${request.singleModel.orEmpty()}' ist nicht im Modellkatalog."
            } else {
                "Der Modellkatalog enthält keine Modelle."
            }
            request.onProgress(message)
            onProgress(AutomatedModelSuiteProgress(phase = "ERROR", error = message, message = message))
            onOutput(message)
            return
        }

        if (request.mode == SuiteMode.FULL) {
            request.onProgress("FULL startet mit unverändertem Vulkan-Preflight und bisheriger Testmatrix; Timeout=LEGACY.")
            executeFull(
                models = selectedModels,
                modelDirectory = modelDirectory,
                config = config,
                fullRoutine = fullRoutine,
                runFullAfterQuickCheck = true,
                timeout = timeout,
                isAborted = isAborted,
                onProgress = { progress ->
                    request.onProgress(progress.message)
                    onProgress(progress)
                },
                onOutput = onOutput,
                downloadModel = downloadModel,
                loadModel = loadModel,
                quickCheck = quickCheck,
                runWorkflow = runWorkflow
            )
            return
        }

        runQuickOrSingle(
            request = request,
            models = selectedModels,
            modelDirectory = modelDirectory,
            config = config.copy(backendPreference = BackendPreference.AUTO),
            timeout = timeout,
            isAborted = isAborted,
            onProgress = { progress ->
                request.onProgress(progress.message)
                onProgress(progress)
            },
            onOutput = onOutput,
            downloadModel = downloadModel,
            loadModel = loadModel,
            runWorkflow = runWorkflow
        )
    }

    private suspend fun <Ready : Any> runQuickOrSingle(
        request: SuiteRequest,
        models: List<HuggingFaceModel>,
        modelDirectory: File,
        config: InferenceConfig,
        timeout: TimeoutConfig,
        isAborted: () -> Boolean,
        onProgress: (AutomatedModelSuiteProgress) -> Unit,
        onOutput: (String) -> Unit,
        downloadModel: suspend (HuggingFaceModel, Int, Int) -> Unit,
        loadModel: suspend (File, InferenceConfig, TimeoutConfig) -> Ready?,
        runWorkflow: suspend (PromptTestCase, Ready, InferenceConfig, TimeoutConfig) -> AutomatedWorkflowResult
    ) {
        var passed = 0
        var failed = 0
        var completed = 0
        val workflows = AutomatedModelSuitePlan.quickRoutine()
        val total = models.size * workflows.size
        val summaries = mutableListOf<String>()

        fun publish(
            phase: String,
            message: String,
            running: Boolean = true,
            modelIndex: Int = 0,
            modelName: String = "",
            error: String? = null
        ) {
            onProgress(
                AutomatedModelSuiteProgress(
                    isRunning = running,
                    phase = phase,
                    currentModelIndex = modelIndex,
                    modelCount = models.size,
                    currentModelName = modelName,
                    quickPassed = passed,
                    quickFailed = failed,
                    fullTestsCompleted = completed,
                    fullTestsTotal = total,
                    message = message,
                    error = error
                )
            )
        }

        val workflowIds = workflows.joinToString(", ") { it.id }
        val modeLabel = if (request.mode == SuiteMode.SINGLE) "SINGLE" else "QUICK"
        try {
            publish(
                "DOWNLOAD",
                "$modeLabel lädt ${models.size} Modell(e); danach exakt 3 Workflows: $workflowIds. Accelerator=AUTO."
            )
            models.withIndex().toList().chunked(MAX_PARALLEL_DOWNLOADS).forEach { batch ->
                coroutineScope {
                    batch.map { (index, model) ->
                        async {
                            if (isAborted()) throw CancellationException("Download durch Nutzer abgebrochen.")
                            publish(
                                "DOWNLOAD",
                                "Download $modeLabel ${index + 1}/${models.size}: ${model.filename}",
                                modelIndex = index + 1,
                                modelName = model.title
                            )
                            downloadModel(model, index + 1, models.size)
                        }
                    }.awaitAll()
                }
            }

            publish(
                "QUICK_WORKFLOW",
                "$modeLabel startet nur 3 Workflows: $workflowIds; Accelerator=AUTO. Keine Vulkan-Voraussetzung."
            )
            for ((modelIndex, model) in models.withIndex()) {
                if (isAborted()) throw CancellationException("Suite vor Modellwechsel abgebrochen.")
                val displayName = "${model.title} · ${model.parameters} · ${model.quantization}"
                publish(
                    "MODEL_SWITCH",
                    "Lade Modell ${modelIndex + 1}/${models.size}: ${model.filename}",
                    modelIndex = modelIndex + 1,
                    modelName = displayName
                )
                val ready = loadModel(File(modelDirectory, model.filename), config, timeout)
                if (ready == null) {
                    failed++
                    summaries += "${model.filename}: MODELLLADEN FEHLGESCHLAGEN"
                    continue
                }

                var modelPassed = true
                for (testCase in workflows) {
                    if (isAborted()) throw CancellationException("Workflow durch Nutzer abgebrochen.")
                    publish(
                        "QUICK_WORKFLOW",
                        "${model.filename} · ${testCase.id} (${completed + 1}/$total) · Accelerator=AUTO",
                        modelIndex = modelIndex + 1,
                        modelName = displayName
                    )
                    val result = runWorkflow(testCase, ready, config, timeout)
                    completed += result.actualRuns.coerceAtLeast(0)
                    if (!result.succeeded) {
                        failed++
                        modelPassed = false
                    }
                    summaries += "${model.filename} · ${testCase.id}: ${if (result.succeeded) "BESTANDEN" else "FEHLGESCHLAGEN"}"
                }
                if (modelPassed) passed++
            }

            val aborted = isAborted()
            val allPassed = !aborted && failed == 0 && passed == models.size
            publish(
                phase = when {
                    aborted -> "ABORTED"
                    allPassed -> if (request.mode == SuiteMode.SINGLE) "SINGLE_PASSED" else "QUICK_PASSED"
                    else -> "QUICK_COMPLETED_WITH_FAILURES"
                },
                message = when {
                    aborted -> "$modeLabel abgebrochen."
                    allPassed -> "$modeLabel bestanden: $passed/${models.size} Modell(e), exakt 3 Workflows je Modell, Accelerator=AUTO."
                    else -> "$modeLabel beendet: $passed/${models.size} Modell(e) vollständig bestanden; $failed Workflow-/Modellfehler."
                },
                running = false,
                error = if (aborted || allPassed) null else "$failed Workflow-/Modellfehler."
            )
            onOutput(summaries.joinToString("\n"))
        } catch (error: Throwable) {
            val aborted = error is CancellationException || isAborted()
            val message = if (aborted) "$modeLabel abgebrochen." else "$modeLabel fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            publish(
                phase = if (aborted) "ABORTED" else "ERROR",
                message = message,
                running = false,
                error = if (aborted) null else error.message ?: error.javaClass.simpleName
            )
            onOutput(message)
        }
    }

    private suspend fun <Ready : Any> executeFull(
        models: List<HuggingFaceModel>,
        modelDirectory: File,
        config: InferenceConfig,
        fullRoutine: List<PromptTestCase>,
        runFullAfterQuickCheck: Boolean,
        timeout: TimeoutConfig,
        isAborted: () -> Boolean,
        onProgress: (AutomatedModelSuiteProgress) -> Unit,
        onOutput: (String) -> Unit,
        downloadModel: suspend (HuggingFaceModel, Int, Int) -> Unit,
        loadModel: suspend (File, InferenceConfig, TimeoutConfig) -> Ready?,
        quickCheck: suspend (HuggingFaceModel, Ready, Int, Int, TimeoutConfig) -> Boolean,
        runWorkflow: suspend (PromptTestCase, Ready, InferenceConfig, TimeoutConfig) -> AutomatedWorkflowResult
    ) {
        var quickPassed = 0
        var quickFailed = 0
        var fullTestsFailed = 0
        var fullTestsCompleted = 0
        var fullTestsTotal = 0
        val summaries = mutableListOf<String>()

        fun publish(
            phase: String,
            message: String,
            running: Boolean = true,
            modelIndex: Int = 0,
            modelName: String = "",
            error: String? = null
        ) {
            onProgress(
                AutomatedModelSuiteProgress(
                    isRunning = running,
                    phase = phase,
                    currentModelIndex = modelIndex,
                    modelCount = models.size,
                    currentModelName = modelName,
                    quickPassed = quickPassed,
                    quickFailed = quickFailed,
                    fullTestsCompleted = fullTestsCompleted,
                    fullTestsTotal = fullTestsTotal,
                    message = message,
                    error = error
                )
            )
        }

        try {
            require(models.isNotEmpty()) { "Der Modellkatalog enthält keine Modelle." }
            publish("DOWNLOAD", "Lade/prüfe ${models.size} Qwen-GGUF-Dateien in 2er-Gruppen (maximal $MAX_PARALLEL_DOWNLOADS parallele Downloads).")

            models.withIndex().toList().chunked(MAX_PARALLEL_DOWNLOADS).forEach { batch ->
                coroutineScope {
                    batch.map { (index, model) ->
                        async {
                            if (isAborted()) throw CancellationException("Die Testreihe wurde vor dem Download abgebrochen.")
                            publish(
                                "DOWNLOAD",
                                "Download ${index + 1}/${models.size}: ${model.filename}",
                                modelIndex = index + 1,
                                modelName = "${model.title} · ${model.parameters} · ${model.quantization}"
                            )
                            downloadModel(model, index + 1, models.size)
                        }
                    }.awaitAll()
                }
            }

            publish("QUICK_CHECK", "Alle Dateien sind geprüft. Starte den Quick-Preflight für jedes Modell.")
            for ((index, model) in models.withIndex()) {
                if (isAborted()) throw CancellationException("Quick-Check wurde abgebrochen.")
                val displayName = "${model.title} · ${model.parameters} · ${model.quantization}"
                publish(
                    "MODEL_SWITCH",
                    "Vorheriges Modell entladen; ${model.filename} wird geladen.",
                    modelIndex = index + 1,
                    modelName = displayName
                )
                val ready = loadModel(File(modelDirectory, model.filename), config, timeout)
                if (ready == null) {
                    quickFailed++
                    summaries += "Quick-Check ${index + 1}/${models.size} – ${model.filename}: MODELLLADEN FEHLGESCHLAGEN"
                    break
                }
                if (quickCheck(model, ready, index + 1, models.size, timeout)) {
                    quickPassed++
                    summaries += "Quick-Check ${index + 1}/${models.size} – ${model.filename}: BESTANDEN"
                } else {
                    quickFailed++
                    summaries += "Quick-Check ${index + 1}/${models.size} – ${model.filename}: FEHLGESCHLAGEN"
                    break
                }
            }

            val quickWasAborted = isAborted()
            if (quickWasAborted || quickFailed > 0 || quickPassed != models.size) {
                val message = if (quickWasAborted) {
                    "Quick-Preflight wurde abgebrochen. Die Full-Routine wurde NICHT gestartet."
                } else {
                    "Quick-Preflight nicht vollständig bestanden. Die Full-Routine wurde NICHT gestartet."
                }
                publish(
                    phase = if (quickWasAborted) "ABORTED" else "QUICK_FAILED",
                    message = message,
                    running = false,
                    error = if (quickWasAborted) null else
                        "Mindestens ein Modell scheiterte beim Laden, Vulkan-Offload oder Quick-Inferenztest."
                )
                onOutput(
                    summaries.joinToString("\n") + if (quickWasAborted) {
                        "\n\nABGEBROCHEN: Full-Routine wurde nicht gestartet."
                    } else {
                        "\n\nFULL-TEST ÜBERSPRUNGEN: Quick-Check-Fehler zuerst beheben."
                    }
                )
                return
            }

            if (!shouldRunFullRoutine(
                    requested = runFullAfterQuickCheck,
                    quickPassed = quickPassed,
                    quickFailed = quickFailed,
                    modelCount = models.size,
                    aborted = isAborted()
                )
            ) {
                publish(
                    phase = "QUICK_PASSED",
                    message = "Quick-Preflight für alle ${models.size} Modelle bestanden. Full-Routine freigegeben.",
                    running = false
                )
                onOutput(summaries.joinToString("\n") + "\n\nAlle Modelle haben den Quick-Preflight bestanden.")
                return
            }

            fullTestsTotal = models.size * fullRoutine.sumOf { AutomatedModelSuitePlan.variantsFor(it).size }
            publish(
                "FULL_TEST",
                "Quick-Preflight fehlerfrei. Starte die vollständige ${fullRoutine.size}-stufige Vergleichsmatrix."
            )

            for ((modelIndex, model) in models.withIndex()) {
                if (isAborted()) break
                val ready = loadModel(File(modelDirectory, model.filename), config, timeout)
                if (ready == null) {
                    fullTestsFailed++
                    summaries += "Full-Test: ${model.filename} konnte nicht geladen werden."
                    break
                }

                for (testCase in fullRoutine) {
                    if (isAborted()) break
                    publish(
                        "FULL_TEST",
                        "Full-Test: Modell ${modelIndex + 1}/${models.size} · ${testCase.title}",
                        modelIndex = modelIndex + 1,
                        modelName = model.filename
                    )
                    val result = runWorkflow(testCase, ready, config, timeout)
                    fullTestsCompleted += result.actualRuns.coerceAtLeast(0)
                    if (!result.succeeded) fullTestsFailed++
                    summaries += "${model.filename} · ${testCase.title}: ${if (result.succeeded) "BESTANDEN" else "MIT FEHLERN"}"
                }
                if (isAborted()) break
            }

            val aborted = isAborted()
            publish(
                phase = if (aborted) "ABORTED" else if (fullTestsFailed == 0) "FULL_PASSED" else "FULL_COMPLETED_WITH_FAILURES",
                message = if (aborted) {
                    "Full-Routine abgebrochen."
                } else {
                    "Full-Routine beendet: $fullTestsCompleted/$fullTestsTotal Einzelprofile; $fullTestsFailed Workflows mit Fehlern."
                },
                running = false
            )
            onOutput(summaries.joinToString("\n"))
        } catch (error: Throwable) {
            val aborted = error is CancellationException || isAborted()
            publish(
                phase = if (aborted) "ABORTED" else "ERROR",
                message = if (aborted) "Automatische Qwen-Testreihe abgebrochen." else "Automatische Qwen-Testreihe fehlgeschlagen.",
                running = false,
                error = if (aborted) null else error.message ?: error.javaClass.simpleName
            )
            onOutput(
                if (aborted) {
                    "Automatische Qwen-Testreihe abgebrochen: ${error.message.orEmpty()}"
                } else {
                    "Automatische Qwen-Testreihe fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                }
            )
        }
    }

    companion object {
        const val MAX_PARALLEL_DOWNLOADS = 2
        fun shouldRunFullRoutine(
            requested: Boolean,
            quickPassed: Int,
            quickFailed: Int,
            modelCount: Int,
            aborted: Boolean
        ): Boolean = requested && !aborted && modelCount > 0 &&
            quickFailed == 0 && quickPassed == modelCount
    }
}
