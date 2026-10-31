package com.example.engine

data class PromptTestCase(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val expectedKeywords: List<String>,
    val minimumOutputWords: Int = 0,
    val maxTokens: Int = 96,
    val timeoutSec: Int = 180
)

data class InferenceTestVariant(
    val id: String,
    val label: String,
    val nBatch: Int,
    val nUbatch: Int,
    val nThreads: Int,
    val nThreadsBatch: Int,
    val backendPreference: BackendPreference
) {
    fun toConfig(base: InferenceConfig, maxTokens: Int): InferenceConfig {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return base.copy(
            selectedProfile = if (id == "CPU_REFERENCE") ProfilePreset.BALANCED else ProfilePreset.MAX_THROUGHPUT,
            nCtx = 2048,
            nBatch = nBatch,
            nUbatch = nUbatch,
            nThreads = nThreads.coerceAtMost(cores),
            nThreadsBatch = nThreadsBatch.coerceAtMost(cores),
            backendPreference = backendPreference,
            temperature = 0.05f,
            topK = 20,
            topP = 0.8f,
            maxTokens = maxTokens
        )
    }
}

object PromptTestCatalog {
    val sampleLetterText = """
        Praxis Dr. med. Martin Müller, Goethestraße 42, 80336 München
        Rechnung Nr. 2026-9812
        Rechnungsdatum: 15.03.2026
        Kontrolluntersuchung: 18,50 EUR
        Blutentnahme: 8,20 EUR
        Großes Blutbild: 122,80 EUR
        Gesamtbetrag: 149,50 EUR
        Fällig bis: 31.03.2026
    """.trimIndent()

    /** Sampling stays fixed so performance differences mainly reflect backend/batch/thread settings. */
    val PARAMETER_VARIANTS: List<InferenceTestVariant> = listOf(
        InferenceTestVariant(
            id = "CPU_REFERENCE",
            label = "CPU-Referenz · 4 Threads · ubatch 32",
            nBatch = 512,
            nUbatch = 32,
            nThreads = 4,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.AUTO
        ),
        InferenceTestVariant(
            id = "CPU_WIDEBATCH",
            label = "CPU-Optimierung · 8 Threads · ubatch 64",
            nBatch = 512,
            nUbatch = 64,
            nThreads = 8,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.CPU_ONLY
        ),
        InferenceTestVariant(
            id = "VULKAN_OFFLOAD",
            label = "Vulkan-Offload · 8 Threads · ubatch 64",
            nBatch = 512,
            nUbatch = 64,
            nThreads = 8,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.ACCELERATOR_REQUIRED
        )
    )

    val ALL_TESTS: List<PromptTestCase> = listOf(
        PromptTestCase(
            id = "TEST_PERFORMANCE",
            title = "1. Durchsatz & Antwortstabilität",
            description = "Misst First-Token-Latenz, Prompt-Prefill, Tokens/s und ob das Modell eine längere deutsche Antwort korrekt abschließt.",
            prompt = "Erkläre auf Deutsch in genau fünf kurzen vollständigen Sätzen und mit mindestens 45 Wörtern, wie ein lokales Dokumentenmanagementsystem Dokumente erfasst, per OCR durchsuchbar macht, Metadaten zuordnet und sicher archiviert. Keine Aufzählung.",
            expectedKeywords = listOf("Dokument", "Suche", "Archiv"),
            minimumOutputWords = 40,
            maxTokens = 112,
            timeoutSec = 180
        ),
        PromptTestCase(
            id = "TEST_DMS",
            title = "2. Deutsche Sprache & Instruktionstreue",
            description = "Prüft eine knappe, grammatikalisch verständliche Erklärung mit genau zwei Sätzen.",
            prompt = "Erkläre in genau zwei kurzen, grammatikalisch korrekten deutschen Sätzen, was ein Dokumentenmanagementsystem ist und welchen Nutzen es bietet.",
            expectedKeywords = listOf("Dokument", "System"),
            maxTokens = 64,
            timeoutSec = 180
        ),
        PromptTestCase(
            id = "TEST_EXTRACTION",
            title = "3. Dokumentextraktion & Halluzinationsschutz",
            description = "Prüft Absender, Dokumenttyp, Rechnungsdatum, Rechnungsnummer, Betrag und Pflicht-Nullwerte für fehlende IBAN/Bestellnummer in einem einzigen JSON-Lauf.",
            prompt = "Extrahiere ausschließlich ein valides JSON-Objekt mit exakt den Feldern sender, documentType, date, invoiceNumber, totalAmount, iban und poNumber. Verwende für date ISO yyyy-MM-dd, für totalAmount eine Zahl ohne Währungszeichen und für fehlende Werte exakt JSON-null. Eine Rechnungsnummer ist keine Bestellnummer. Dokument:\n$sampleLetterText",
            expectedKeywords = listOf("sender", "documentType", "date"),
            maxTokens = 112,
            timeoutSec = 240
        )
    )
}
