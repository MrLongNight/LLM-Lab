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
}
