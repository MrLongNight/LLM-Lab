package com.example.engine

import kotlinx.coroutines.CancellationException
import java.io.File

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

    suspend fun <Ready : Any> execute(
        models: List<HuggingFaceModel>,
        modelDirectory: File,
        config: InferenceConfig,
        fullRoutine: List<PromptTestCase>,
        runFullAfterQuickCheck: Boolean,
        isAborted: () -> Boolean,
        onProgress: (AutomatedModelSuiteProgress) -> Unit,
        onOutput: (String) -> Unit,
        downloadModel: suspend (HuggingFaceModel, Int, Int) -> Unit,
        loadModel: suspend (File, InferenceConfig) -> Ready?,
        quickCheck: suspend (HuggingFaceModel, Ready, Int, Int) -> Boolean,
        runWorkflow: suspend (PromptTestCase, Ready, InferenceConfig) -> AutomatedWorkflowResult
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
            publish("DOWNLOAD", "Lade/prüfe nacheinander alle ${models.size} Qwen-GGUF-Dateien.")

            for ((index, model) in models.withIndex()) {
                if (isAborted()) throw CancellationException("Die Testreihe wurde vor dem Download abgebrochen.")
                publish(
                    "DOWNLOAD",
                    "Download ${index + 1}/${models.size}: ${model.filename}",
                    modelIndex = index + 1,
                    modelName = "${model.title} · ${model.parameters} · ${model.quantization}"
                )
                downloadModel(model, index + 1, models.size)
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
                val ready = loadModel(File(modelDirectory, model.filename), config)
                if (ready == null) {
                    quickFailed++
                    summaries += "Quick-Check ${index + 1}/${models.size} – ${model.filename}: MODELLLADEN FEHLGESCHLAGEN"
                    break
                }
                if (quickCheck(model, ready, index + 1, models.size)) {
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
                val ready = loadModel(File(modelDirectory, model.filename), config)
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
                    val result = runWorkflow(testCase, ready, config)
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
