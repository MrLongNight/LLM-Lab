package com.example.engine

enum class ProfilePreset(val label: String, val description: String) {
    BALANCED("Ausgewogen", "Generation 4 Threads, Prompt-Prefill bis 8 Threads"),
    POWER_SAVER("Energiesparend", "2 CPU-Threads für Generation und Prompt"),
    MAX_THROUGHPUT("Maximaler Durchsatz", "Nutzt die verfügbaren CPU-Threads")
}

data class InferenceConfig(
    val selectedProfile: ProfilePreset = ProfilePreset.BALANCED,
    val nCtx: Int = 2048,
    val nBatch: Int = 512,
    val nUbatch: Int = 32,
    val nThreads: Int = 4,
    val nThreadsBatch: Int = 8,
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
    val maxTokens: Int = 160,
    val timeoutInitSec: Int = 120,
    val timeoutShortTestSec: Int = 30,
    val timeoutNormalInferenceSec: Int = 90,
    val timeoutStressTestSec: Int = 120
) {
    fun hasSameNativeContext(other: InferenceConfig): Boolean =
        nCtx == other.nCtx &&
            nBatch == other.nBatch &&
            nUbatch == other.nUbatch &&
            nThreads == other.nThreads &&
            nThreadsBatch == other.nThreadsBatch &&
            backendPreference == other.backendPreference

    companion object {
        fun fromPreset(preset: ProfilePreset): InferenceConfig {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return when (preset) {
                ProfilePreset.BALANCED -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 512,
                    nUbatch = 32,
                    nThreads = 4.coerceAtMost(cores),
                    nThreadsBatch = cores.coerceAtMost(8)
                )
                ProfilePreset.POWER_SAVER -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 256,
                    nUbatch = 8,
                    nThreads = 2.coerceAtMost(cores),
                    nThreadsBatch = 2.coerceAtMost(cores)
                )
                ProfilePreset.MAX_THROUGHPUT -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 512,
                    nUbatch = 32,
                    nThreads = cores,
                    nThreadsBatch = cores
                )
            }
        }
    }
}
