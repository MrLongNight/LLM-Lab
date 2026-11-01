package com.example.engine

object AutomatedModelSuitePlan {
    const val quickPrompt: String = "Antworte ausschließlich mit genau diesem Wort: BEREIT"

    fun quickCheckPassed(
        activeBackendName: String,
        gpuWeightLayerCount: Int,
        finishReason: String,
        generatedTokens: Int,
        response: String
    ): Boolean {
        val validFinish = finishReason == "STOP_TOKEN" || finishReason == "MAX_TOKENS"
        return activeBackendName.contains("Vulkan", ignoreCase = true) &&
            gpuWeightLayerCount > 0 &&
            validFinish &&
            generatedTokens > 0 &&
            response.trim().equals("BEREIT", ignoreCase = true)
    }

    fun variantsFor(testCase: PromptTestCase): List<InferenceTestVariant> = when (testCase.id) {
        "TEST_PERFORMANCE" -> PromptTestCatalog.PARAMETER_VARIANTS
        "TEST_TUNING" -> PromptTestCatalog.TUNING_VARIANTS
        "TEST_SAMPLING" -> PromptTestCatalog.SAMPLING_VARIANTS
        "TEST_PREFILL_256", "TEST_PREFILL_512" -> PromptTestCatalog.PREFILL_VARIANTS
        else -> PromptTestCatalog.VULKAN_ONLY_VARIANTS
    }

    fun fullRoutine(): List<PromptTestCase> = PromptTestCatalog.ALL_TESTS

    fun fullProfileCountPerModel(): Int =
        fullRoutine().sumOf { testCase -> variantsFor(testCase).size }
}
