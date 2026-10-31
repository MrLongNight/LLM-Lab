package com.example.engine

data class NativeMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptEvalDurationMs: Long,
    val generationDurationMs: Long,
    val totalDurationMs: Long,
    val decodeCalls: Int,
    val tokensPerSecond: Float,
    val finishReason: String, // STOP_TOKEN, MAX_TOKENS, ABORTED_BY_USER, TIMEOUT, NATIVE_ERROR
    val errorMessage: String? = null,
    val promptTokenizeDurationMs: Long = 0L,
    val firstTokenDurationMs: Long = 0L,
    val promptDecodeCalls: Int = 0,
    val generationDecodeCalls: Int = 0
)
