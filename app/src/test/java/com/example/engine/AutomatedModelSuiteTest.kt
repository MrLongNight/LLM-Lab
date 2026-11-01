package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

class AutomatedModelSuiteTest {
    @Test
    fun catalogHasFourOfficialModelsWithPinnedChecksums() {
        assertEquals(4, HuggingFaceModelCatalog.models.size)
        assertEquals(4, HuggingFaceModelCatalog.models.map { it.filename }.toSet().size)
        HuggingFaceModelCatalog.models.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://huggingface.co/Qwen/"))
            assertTrue(model.downloadUrl.endsWith("/resolve/main/${model.filename}"))
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.estimatedSizeBytes > 100_000_000L)
        }
    }

    @Test
    fun fullSuiteContainsSevenWorkflowsAndEightyProfiles() {
        assertEquals(7, AutomatedModelSuitePlan.fullRoutine().size)
        assertEquals(20, AutomatedModelSuitePlan.fullProfileCountPerModel())
        assertEquals(80, HuggingFaceModelCatalog.models.size * AutomatedModelSuitePlan.fullProfileCountPerModel())
    }

    @Test
    fun onlyThePerformanceWorkflowUsesOneCpuReference() {
        val variants = AutomatedModelSuitePlan.fullRoutine().flatMap { testCase ->
            AutomatedModelSuitePlan.variantsFor(testCase)
        }
        assertEquals(1, variants.count { it.backendPreference == BackendPreference.CPU_ONLY })
        assertEquals(1, PromptTestCatalog.PARAMETER_VARIANTS.count { it.backendPreference == BackendPreference.CPU_ONLY })
        assertTrue(variants.count { it.backendPreference == BackendPreference.ACCELERATOR_REQUIRED } >= 19)
    }

    @Test
    fun prefillInputsHaveDistinctMediumAndLongLengths() {
        val medium = PromptTestCatalog.ALL_TESTS.first { it.id == "TEST_PREFILL_256" }.prompt.length
        val long = PromptTestCatalog.ALL_TESTS.first { it.id == "TEST_PREFILL_512" }.prompt.length
        assertTrue(medium > 800)
        assertTrue(long > medium * 1.8)
    }

    @Test
    fun quickCheckRequiresVulkanGpuOffloadAndCleanResponse() {
        assertTrue(AutomatedModelSuitePlan.quickCheckPassed("Vulkan", 29, "STOP_TOKEN", 1, "BEREIT"))
        assertTrue(AutomatedModelSuitePlan.quickCheckPassed("Vulkan 0", 29, "MAX_TOKENS", 1, "bereit"))
        assertFalse(AutomatedModelSuitePlan.quickCheckPassed("CPU", 29, "STOP_TOKEN", 1, "BEREIT"))
        assertFalse(AutomatedModelSuitePlan.quickCheckPassed("Vulkan", 0, "STOP_TOKEN", 1, "BEREIT"))
        assertFalse(AutomatedModelSuitePlan.quickCheckPassed("Vulkan", 29, "TIMEOUT", 0, "BEREIT"))
        assertFalse(AutomatedModelSuitePlan.quickCheckPassed("Vulkan", 29, "STOP_TOKEN", 1, "BEREIT."))
    }

    @Test
    fun fullRoutineDoesNotStartWhenAnyQuickCheckFails() = kotlinx.coroutines.test.runTest {
        val models = HuggingFaceModelCatalog.models.take(2)
        val coordinator = AutomatedModelSuiteCoordinator()
        val quickChecks = mutableListOf<String>()
        val fullRuns = mutableListOf<String>()
        var finalProgress = AutomatedModelSuiteProgress()

        coordinator.run(SuiteRequest(SuiteMode.FULL),
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = listOf(PromptTestCase("TEST_DMS", "DMS", "", "Prompt", emptyList())),
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { _, _, _ -> },
            loadModel = { file, _, _ -> file.name },
            quickCheck = { model, _, _, _, _ ->
                quickChecks += model.id
                model.id == models.first().id
            },
            runWorkflow = { testCase, ready, _, _ ->
                fullRuns += "${ready}:${testCase.id}"
                AutomatedWorkflowResult(actualRuns = 1, succeeded = true)
            }
        )

        org.junit.Assert.assertEquals(2, quickChecks.size)
        org.junit.Assert.assertTrue(fullRuns.isEmpty())
        org.junit.Assert.assertEquals("QUICK_FAILED", finalProgress.phase)
        org.junit.Assert.assertFalse(finalProgress.isRunning)
    }

    @Test
    fun fullRoutineStartsOnlyAfterEveryQuickCheckPassesAndSwitchesModelsSerially() = kotlinx.coroutines.test.runTest {
        val models = HuggingFaceModelCatalog.models.take(2)
        val coordinator = AutomatedModelSuiteCoordinator()
        val loadedFiles = mutableListOf<String>()
        val fullRuns = mutableListOf<String>()
        var finalProgress = AutomatedModelSuiteProgress()

        coordinator.run(SuiteRequest(SuiteMode.FULL),
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = listOf(PromptTestCase("TEST_DMS", "DMS", "", "Prompt", emptyList())),
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { _, _, _ -> },
            loadModel = { file, _, timeout ->
                assertEquals(TimeoutConfig.LEGACY, timeout)
                loadedFiles += file.name
                file.name
            },
            quickCheck = { _, _, _, _, timeout ->
                assertEquals(TimeoutConfig.LEGACY, timeout)
                true
            },
            runWorkflow = { testCase, ready, _, timeout ->
                assertEquals(TimeoutConfig.LEGACY, timeout)
                fullRuns += "${ready}:${testCase.id}"
                AutomatedWorkflowResult(actualRuns = 1, succeeded = true)
            }
        )

        org.junit.Assert.assertEquals(
            listOf(models[0].filename, models[1].filename, models[0].filename, models[1].filename),
            loadedFiles
        )
        org.junit.Assert.assertEquals(2, fullRuns.size)
        org.junit.Assert.assertEquals("FULL_PASSED", finalProgress.phase)
        org.junit.Assert.assertEquals(2, finalProgress.fullTestsCompleted)
        org.junit.Assert.assertEquals(2, finalProgress.fullTestsTotal)
    }

    @Test
    fun quickModeRunsAtMostTwoDownloadsConcurrentlyButKeepsInferenceSerial() = kotlinx.coroutines.test.runTest {
        val coordinator = AutomatedModelSuiteCoordinator()
        val models = HuggingFaceModelCatalog.models
        val activeDownloads = AtomicInteger()
        val peakDownloads = AtomicInteger()
        val activeWorkflows = AtomicInteger()
        val peakWorkflows = AtomicInteger()

        fun recordPeak(peak: AtomicInteger, active: Int) {
            while (true) {
                val current = peak.get()
                if (active <= current || peak.compareAndSet(current, active)) return
            }
        }

        coordinator.run(
            request = coordinator.runQuick(),
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = AutomatedModelSuitePlan.fullRoutine(),
            isAborted = { false },
            onProgress = {},
            onOutput = {},
            downloadModel = { _, _, _ ->
                val active = activeDownloads.incrementAndGet()
                recordPeak(peakDownloads, active)
                delay(10)
                activeDownloads.decrementAndGet()
            },
            loadModel = { file, _, _ -> file.name },
            quickCheck = { _, _, _, _, _ -> throw AssertionError("QUICK darf keinen Vulkan-Preflight ausführen.") },
            runWorkflow = { _, _, _, _ ->
                val active = activeWorkflows.incrementAndGet()
                recordPeak(peakWorkflows, active)
                delay(10)
                activeWorkflows.decrementAndGet()
                AutomatedWorkflowResult(actualRuns = 1, succeeded = true)
            }
        )

        assertEquals(2, peakDownloads.get())
        assertEquals(1, peakWorkflows.get())
    }

    @Test
    fun fullRoutineGateAlsoRejectsAbortAndIncompleteQuickPasses() {
        org.junit.Assert.assertTrue(
            AutomatedModelSuiteCoordinator.shouldRunFullRoutine(
                requested = true, quickPassed = 4, quickFailed = 0, modelCount = 4, aborted = false
            )
        )
        org.junit.Assert.assertFalse(
            AutomatedModelSuiteCoordinator.shouldRunFullRoutine(
                requested = true, quickPassed = 3, quickFailed = 1, modelCount = 4, aborted = false
            )
        )
        org.junit.Assert.assertFalse(
            AutomatedModelSuiteCoordinator.shouldRunFullRoutine(
                requested = true, quickPassed = 4, quickFailed = 0, modelCount = 4, aborted = true
            )
        )
        org.junit.Assert.assertFalse(
            AutomatedModelSuiteCoordinator.shouldRunFullRoutine(
                requested = false, quickPassed = 4, quickFailed = 0, modelCount = 4, aborted = false
            )
        )
    }

    @Test
    fun singleModeRunsOnlyThreeAutoWorkflowsForTheRequestedModel() = kotlinx.coroutines.test.runTest {
        val models = HuggingFaceModelCatalog.models
        val selected = models[2]
        val coordinator = AutomatedModelSuiteCoordinator()
        val downloaded = mutableListOf<String>()
        val loaded = mutableListOf<String>()
        val workflowIds = mutableListOf<String>()
        var finalProgress = AutomatedModelSuiteProgress()

        coordinator.run(
            request = coordinator.runSingle(selected.id),
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = AutomatedModelSuitePlan.fullRoutine(),
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { model, _, _ -> downloaded += model.id },
            loadModel = { file, _, timeout ->
                assertEquals(TimeoutConfig.MOBILE, timeout)
                loaded += file.name
                file.name
            },
            quickCheck = { _, _, _, _, _ -> throw AssertionError("SINGLE darf keinen Vulkan-Preflight ausführen.") },
            runWorkflow = { testCase, _, config, timeout ->
                assertEquals(BackendPreference.AUTO, config.backendPreference)
                assertEquals(TimeoutConfig.MOBILE, timeout)
                workflowIds += testCase.id
                AutomatedWorkflowResult(actualRuns = 1, succeeded = true)
            }
        )

        assertEquals(listOf(selected.id), downloaded)
        assertEquals(listOf(selected.filename), loaded)
        assertEquals(listOf("chat-basic", "dms-smoke", "json-strict"), workflowIds)
        assertEquals("SINGLE_PASSED", finalProgress.phase)
        assertEquals(3, finalProgress.fullTestsCompleted)
    }

    @Test
    fun quickModeUsesExactlyThreeWorkflowsAndNoVulkanQuickCheck() = kotlinx.coroutines.test.runTest {
        val models = HuggingFaceModelCatalog.models.take(2)
        val coordinator = AutomatedModelSuiteCoordinator()
        val workflowIds = mutableListOf<String>()
        var finalProgress = AutomatedModelSuiteProgress()

        coordinator.run(
            request = coordinator.runQuick(),
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = AutomatedModelSuitePlan.fullRoutine(),
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { _, _, _ -> },
            loadModel = { file, _, _ -> file.name },
            quickCheck = { _, _, _, _, _ -> throw AssertionError("QUICK darf keinen Vulkan-Preflight ausführen.") },
            runWorkflow = { testCase, _, config, _ ->
                assertEquals(BackendPreference.AUTO, config.backendPreference)
                workflowIds += testCase.id
                AutomatedWorkflowResult(actualRuns = 1, succeeded = true)
            }
        )

        assertEquals(listOf("chat-basic", "dms-smoke", "json-strict", "chat-basic", "dms-smoke", "json-strict"), workflowIds)
        assertEquals("QUICK_PASSED", finalProgress.phase)
        assertEquals(6, finalProgress.fullTestsCompleted)
    }


    @Test
    fun suiteStopsBeforeNativeLoadWhenFreeMemoryIsBelowOneAndAHalfModelSizes() = kotlinx.coroutines.test.runTest {
        val model = HuggingFaceModelCatalog.models.first()
        val requiredBytes = model.estimatedSizeBytes + model.estimatedSizeBytes / 2L
        val coordinator = AutomatedModelSuiteCoordinator(
            memoryInfoProvider = { requiredBytes - 1L }
        )
        var loadCalls = 0
        var finalProgress = AutomatedModelSuiteProgress()
        var output = ""

        coordinator.run(
            request = coordinator.runSingle(model.id),
            models = listOf(model),
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = AutomatedModelSuitePlan.fullRoutine(),
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = { output = it },
            downloadModel = { _, _, _ -> },
            loadModel = { _, _, _ ->
                loadCalls++
                "should-not-load"
            },
            quickCheck = { _, _, _, _, _ -> throw AssertionError("SINGLE must not run the FULL quick check.") },
            runWorkflow = { _, _, _, _ -> throw AssertionError("No workflow may run after a memory rejection.") }
        )

        assertEquals(0, loadCalls)
        assertTrue(output.contains("Insufficient memory for model ${model.filename}"))
        assertTrue(output.contains("need ${requiredBytes} bytes"))
        assertTrue(output.contains("have ${requiredBytes - 1L} bytes"))
        assertTrue(finalProgress.error.orEmpty().contains("Insufficient memory"))
        assertEquals("ERROR", finalProgress.phase)
    }

}
