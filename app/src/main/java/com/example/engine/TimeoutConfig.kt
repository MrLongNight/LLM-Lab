package com.example.engine

/**
 * Central timeout profile for model initialization and streamed inference.
 * All values are expressed in milliseconds.
 */
data class TimeoutConfig(
    val modelInitMs: Long,
    val firstTokenMs: Long,
    val tokenStallMs: Long,
    val totalInferenceMs: Long,
    val dmsMs: Long,
    val extractionMs: Long,
    val performanceMs: Long
) {
    fun modelInitLimitMs(requestedLimitMs: Long): Long =
        minOf(modelInitMs, requestedLimitMs).coerceAtLeast(1L)

    /** The overall inference deadline is always the hard upper bound. */
    fun inferenceLimitMs(kind: InferenceTimeoutKind, requestedLimitMs: Long): Long {
        val taskLimitMs = when (kind) {
            InferenceTimeoutKind.GENERAL -> totalInferenceMs
            InferenceTimeoutKind.DMS -> dmsMs
            InferenceTimeoutKind.EXTRACTION -> extractionMs
            InferenceTimeoutKind.PERFORMANCE -> performanceMs
        }
        return minOf(totalInferenceMs, taskLimitMs, requestedLimitMs).coerceAtLeast(1L)
    }

    companion object {
        val MOBILE = TimeoutConfig(60_000L, 20_000L, 15_000L, 120_000L, 90_000L, 120_000L, 90_000L)
        val LEGACY = TimeoutConfig(120_000L, 120_000L, 120_000L, 120_000L, 180_000L, 240_000L, 180_000L)
    }
}

/** Semantic timeout group; decouples timeout policy from UI labels and test IDs. */
enum class InferenceTimeoutKind { GENERAL, DMS, EXTRACTION, PERFORMANCE }
