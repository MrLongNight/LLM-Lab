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

    fun quickRoutine(): List<PromptTestCase> = listOf(
        PromptTestCase(
            id = "chat-basic",
            title = "Chat Basic",
            description = "Kleine deutschsprachige Chat-Funktionsprobe ohne Accelerator-Voraussetzung.",
            prompt = "Antworte auf Deutsch in genau einem kurzen Satz: Wozu dient ein Dokumentenmanagementsystem?",
            expectedKeywords = listOf("Dokument"),
            minimumOutputWords = 5,
            maxTokens = 48,
            timeoutSec = 90
        ),
        PromptTestCase(
            id = "dms-smoke",
            title = "DMS Smoke",
            description = "Prüft eine einfache DMS-Aufgabe mit einer klaren, knappen Zusammenfassung.",
            prompt = "Ein Dokument wird gescannt und per OCR durchsuchbar gemacht. Erkläre in genau zwei kurzen deutschen Sätzen, warum das für die Ablage hilfreich ist.",
            expectedKeywords = listOf("Dokument", "Suche"),
            minimumOutputWords = 10,
            maxTokens = 64,
            timeoutSec = 90,
            timeoutKind = InferenceTimeoutKind.DMS
        ),
        PromptTestCase(
            id = "json-strict",
            title = "JSON Strict",
            description = "Prüft strikt formatiertes JSON einschließlich null für nicht vorhandene Felder.",
            prompt = "Gib ausschließlich ein valides JSON-Objekt mit exakt den Feldern sender, totalAmount und iban aus. Extrahiere sender und totalAmount aus dem Dokument. Setze iban auf JSON-null, falls keine IBAN vorhanden ist. Dokument:\\n${PromptTestCatalog.sampleLetterText}",
            expectedKeywords = listOf("sender", "totalAmount", "iban"),
            maxTokens = 96,
            timeoutSec = 120,
            timeoutKind = InferenceTimeoutKind.EXTRACTION
        )
    )

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
