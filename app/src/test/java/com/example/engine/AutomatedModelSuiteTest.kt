package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        coordinator.execute(
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = listOf(PromptTestCase("TEST_DMS", "DMS", "", "Prompt", emptyList())),
            runFullAfterQuickCheck = true,
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { _, _, _ -> },
            loadModel = { file, _ -> file.name },
            quickCheck = { model, _, _, _ ->
                quickChecks += model.id
                model.id == models.first().id
            },
            runWorkflow = { testCase, ready, _ ->
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

        coordinator.execute(
            models = models,
            modelDirectory = java.io.File("unused-model-directory"),
            config = InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            fullRoutine = listOf(PromptTestCase("TEST_DMS", "DMS", "", "Prompt", emptyList())),
            runFullAfterQuickCheck = true,
            isAborted = { false },
            onProgress = { finalProgress = it },
            onOutput = {},
            downloadModel = { _, _, _ -> },
            loadModel = { file, _ ->
                loadedFiles += file.name
                file.name
            },
            quickCheck = { _, _, _, _ -> true },
            runWorkflow = { testCase, ready, _ ->
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

}
