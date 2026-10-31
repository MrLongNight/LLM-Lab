package com.example.engine

enum class ProfilePreset(val label: String, val description: String) {
    BALANCED("Ausgewogen", "n_ubatch = 32, 4 Threads (Qwen 1.5B)"),
    POWER_SAVER("Energiesparend", "n_ubatch = 4, 2 Threads (Minimaler Akkuverbrauch)"),
    MAX_THROUGHPUT("Maximaler Durchsatz", "n_ubatch = 32, maximale CPU-Kerne")
}

data class InferenceConfig(
    val selectedProfile: ProfilePreset = ProfilePreset.BALANCED,
    val nCtx: Int = 2048,
    val nBatch: Int = 512,
    val nUbatch: Int = 32,
    val nThreads: Int = 4,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 160,
    // Konfigurierbare Timeouts gemäß Spezifikation
    val timeoutInitSec: Int = 120,
    val timeoutShortTestSec: Int = 30,
    val timeoutNormalInferenceSec: Int = 90,
    val timeoutStressTestSec: Int = 120
) {
    /** Parameters fixed at native llama.cpp context creation time. */
    fun hasSameNativeContext(other: InferenceConfig): Boolean =
        nCtx == other.nCtx && nBatch == other.nBatch &&
            nUbatch == other.nUbatch && nThreads == other.nThreads

    companion object {
        fun fromPreset(preset: ProfilePreset): InferenceConfig {
            val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return when (preset) {
                ProfilePreset.BALANCED -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 512,
                    nUbatch = 32,
                    nThreads = 4.coerceAtMost(availableCores)
                )
                ProfilePreset.POWER_SAVER -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 256,
                    nUbatch = 4,
                    nThreads = 2.coerceAtMost(availableCores)
                )
                ProfilePreset.MAX_THROUGHPUT -> InferenceConfig(
                    selectedProfile = preset,
                    nCtx = 2048,
                    nBatch = 512,
                    nUbatch = 32,
                    nThreads = availableCores
                )
            }
        }
    }
}
