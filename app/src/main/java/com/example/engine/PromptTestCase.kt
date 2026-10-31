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
    val backendPreference: BackendPreference,
    val temperature: Float = 0.05f,
    val topK: Int = 20,
    val topP: Float = 0.8f
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
            temperature = temperature,
            topK = topK,
            topP = topP,
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
            label = "CPU-Optimierung · 4 Threads · ubatch 64",
            nBatch = 512,
            nUbatch = 64,
            nThreads = 4,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.CPU_ONLY
        ),
        InferenceTestVariant(
            id = "VULKAN_OFFLOAD",
            label = "Vulkan-Offload · 4 Threads · ubatch 64",
            nBatch = 512,
            nUbatch = 64,
            nThreads = 4,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.ACCELERATOR_REQUIRED
        )
    )

    /**
     * Short hardware sweep. Each configuration keeps sampling fixed.
     * The target phone has eight logical CPU cores.
     */
    val TUNING_VARIANTS: List<InferenceTestVariant> = listOf(
        InferenceTestVariant("CPU_GEN_2_8", "CPU 2/8 Threads · ubatch 64", 512, 64, 2, 8, BackendPreference.CPU_ONLY),
        InferenceTestVariant("CPU_GEN_4_8", "CPU 4/8 Threads · ubatch 64", 512, 64, 4, 8, BackendPreference.CPU_ONLY),
        InferenceTestVariant("CPU_GEN_6_8", "CPU 6/8 Threads · ubatch 64", 512, 64, 6, 8, BackendPreference.CPU_ONLY),
        InferenceTestVariant("CPU_GEN_8_8", "CPU 8/8 Threads · ubatch 64", 512, 64, 8, 8, BackendPreference.CPU_ONLY),
        InferenceTestVariant("CPU_GEN_8_8_UB128", "CPU 8/8 Threads · ubatch 128", 512, 128, 8, 8, BackendPreference.CPU_ONLY),
        InferenceTestVariant("VULKAN_GEN_4_8", "Vulkan 4/8 Threads · ubatch 64", 512, 64, 4, 8, BackendPreference.ACCELERATOR_REQUIRED),
        InferenceTestVariant("VULKAN_GEN_8_8", "Vulkan 8/8 Threads · ubatch 64", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED),
        InferenceTestVariant("VULKAN_GEN_8_8_UB128", "Vulkan 8/8 Threads · ubatch 128", 512, 128, 8, 8, BackendPreference.ACCELERATOR_REQUIRED)
    )

    /**
     * Sampling sweep: one parameter changes from the baseline per variant.
     * Same Vulkan/threads/context/prompt keep hardware effects out of this test.
     */
    val SAMPLING_VARIANTS: List<InferenceTestVariant> = listOf(
        InferenceTestVariant("SAMPLE_BASELINE", "Baseline · temp 0.05 · topK 20 · topP 0.80", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED),
        InferenceTestVariant("SAMPLE_GREEDY", "Greedy · temp 0.00", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED, 0.0f, 20, 0.8f),
        InferenceTestVariant("SAMPLE_TEMP_020", "Temperatur 0.20", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED, 0.2f, 20, 0.8f),
        InferenceTestVariant("SAMPLE_TOPK_40", "Top-K 40", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED, 0.05f, 40, 0.8f),
        InferenceTestVariant("SAMPLE_TOPP_095", "Top-P 0.95", 512, 64, 8, 8, BackendPreference.ACCELERATOR_REQUIRED, 0.05f, 20, 0.95f)
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
            maxTokens = 96,
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
        ),
        PromptTestCase(
            id = "TEST_TUNING",
            title = "4. Hardware-Tuning · CPU/GPU/8 Kerne",
            description = "Vergleicht CPU-Generierung mit 2, 4, 6 und 8 Threads, 8 Prefill-Threads, ubatch 64/128 sowie Vulkan mit 4/8 Threads. Bewertet Messwerte statt Satzanzahl.",
            prompt = "Ein Dokument wird lokal als PDF gespeichert. OCR wandelt den sichtbaren Inhalt in maschinenlesbaren Text um. Dieser Text wird für die Volltextsuche indexiert. Metadaten wie Absender und Datum werden nur übernommen, wenn sie im Dokument stehen. Erkläre in einem kurzen deutschen Satz den Zweck von OCR.",
            expectedKeywords = listOf("OCR"),
            maxTokens = 40,
            timeoutSec = 180
        ),
        PromptTestCase(
            id = "TEST_SAMPLING",
            title = "5. Sampling-Tuning · Temperatur/Top-K/Top-P",
            description = "Vergleicht fünf Sampling-Konfigurationen mit jeweils nur einer Parameteränderung gegenüber der Baseline. Prüft, ob kurze deutsche Antworten verständlich und thematisch passend bleiben.",
            prompt = "Erkläre in genau einem kurzen deutschen Satz, wozu OCR bei der Dokumentenerfassung dient.",
            expectedKeywords = listOf("OCR"),
            maxTokens = 40,
            timeoutSec = 120
        )
    )
}
