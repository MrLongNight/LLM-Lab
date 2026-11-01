package com.example.engine

import android.os.Bundle

/**
 * Stable primitive-only wire format for the isolated inference process.
 * Keeping the transport independent of Parcelable app models avoids leaking
 * UI/database types across the process boundary.
 */
internal object InferenceWire {
    const val SUCCESS = "success"
    const val ERROR = "error"
    const val BACKEND_REPORT = "backend_report"
    const val ENGINE_VERSION = "engine_version"
    const val ACTIVE_BACKEND = "active_backend"
    const val MODEL_LOAD_LOG = "model_load_log"
    const val ALLOCATED_HEAP = "allocated_heap"
    const val NATIVE_LIBRARY_LOADED = "native_library_loaded"

    private const val SELECTED_PROFILE = "selected_profile"
    private const val N_CTX = "n_ctx"
    private const val N_BATCH = "n_batch"
    private const val N_UBATCH = "n_ubatch"
    private const val N_THREADS = "n_threads"
    private const val N_THREADS_BATCH = "n_threads_batch"
    private const val BACKEND_PREFERENCE = "backend_preference"
    private const val TEMPERATURE = "temperature"
    private const val TOP_K = "top_k"
    private const val TOP_P = "top_p"
    private const val MAX_TOKENS = "max_tokens"
    private const val TIMEOUT_INIT = "timeout_init"
    private const val TIMEOUT_SHORT = "timeout_short"
    private const val TIMEOUT_NORMAL = "timeout_normal"
    private const val TIMEOUT_STRESS = "timeout_stress"

    private const val PROMPT_TOKENS = "prompt_tokens"
    private const val GENERATED_TOKENS = "generated_tokens"
    private const val PROMPT_EVAL_MS = "prompt_eval_ms"
    private const val GENERATION_MS = "generation_ms"
    private const val TOTAL_MS = "total_ms"
    private const val DECODE_CALLS = "decode_calls"
    private const val TOKENS_PER_SECOND = "tokens_per_second"
    private const val FINISH_REASON = "finish_reason"
    private const val ERROR_MESSAGE = "error_message"
    private const val PROMPT_TOKENIZE_MS = "prompt_tokenize_ms"
    private const val FIRST_TOKEN_MS = "first_token_ms"
    private const val PROMPT_DECODE_CALLS = "prompt_decode_calls"
    private const val GENERATION_DECODE_CALLS = "generation_decode_calls"

    fun InferenceConfig.toWireBundle(): Bundle = Bundle().apply {
        putString(SELECTED_PROFILE, selectedProfile.name)
        putInt(N_CTX, nCtx)
        putInt(N_BATCH, nBatch)
        putInt(N_UBATCH, nUbatch)
        putInt(N_THREADS, nThreads)
        putInt(N_THREADS_BATCH, nThreadsBatch)
        putInt(BACKEND_PREFERENCE, backendPreference.nativeCode)
        putFloat(TEMPERATURE, temperature)
        putInt(TOP_K, topK)
        putFloat(TOP_P, topP)
        putInt(MAX_TOKENS, maxTokens)
        putInt(TIMEOUT_INIT, timeoutInitSec)
        putInt(TIMEOUT_SHORT, timeoutShortTestSec)
        putInt(TIMEOUT_NORMAL, timeoutNormalInferenceSec)
        putInt(TIMEOUT_STRESS, timeoutStressTestSec)
    }

    fun Bundle.toInferenceConfig(): InferenceConfig {
        val defaults = InferenceConfig()
        val profile = getString(SELECTED_PROFILE)?.let { name ->
            ProfilePreset.entries.firstOrNull { it.name == name }
        } ?: defaults.selectedProfile
        val backendCode = getInt(BACKEND_PREFERENCE, defaults.backendPreference.nativeCode)
        val backend = BackendPreference.entries.firstOrNull { it.nativeCode == backendCode }
            ?: defaults.backendPreference
        return InferenceConfig(
            selectedProfile = profile,
            nCtx = getInt(N_CTX, defaults.nCtx),
            nBatch = getInt(N_BATCH, defaults.nBatch),
            nUbatch = getInt(N_UBATCH, defaults.nUbatch),
            nThreads = getInt(N_THREADS, defaults.nThreads),
            nThreadsBatch = getInt(N_THREADS_BATCH, defaults.nThreadsBatch),
            backendPreference = backend,
            temperature = getFloat(TEMPERATURE, defaults.temperature),
            topK = getInt(TOP_K, defaults.topK),
            topP = getFloat(TOP_P, defaults.topP),
            maxTokens = getInt(MAX_TOKENS, defaults.maxTokens),
            timeoutInitSec = getInt(TIMEOUT_INIT, defaults.timeoutInitSec),
            timeoutShortTestSec = getInt(TIMEOUT_SHORT, defaults.timeoutShortTestSec),
            timeoutNormalInferenceSec = getInt(TIMEOUT_NORMAL, defaults.timeoutNormalInferenceSec),
            timeoutStressTestSec = getInt(TIMEOUT_STRESS, defaults.timeoutStressTestSec)
        )
    }

    fun NativeMetrics.toWireBundle(): Bundle = Bundle().apply {
        putInt(PROMPT_TOKENS, promptTokens)
        putInt(GENERATED_TOKENS, generatedTokens)
        putLong(PROMPT_EVAL_MS, promptEvalDurationMs)
        putLong(GENERATION_MS, generationDurationMs)
        putLong(TOTAL_MS, totalDurationMs)
        putInt(DECODE_CALLS, decodeCalls)
        putFloat(TOKENS_PER_SECOND, tokensPerSecond)
        putString(FINISH_REASON, finishReason)
        putString(ERROR_MESSAGE, errorMessage)
        putLong(PROMPT_TOKENIZE_MS, promptTokenizeDurationMs)
        putLong(FIRST_TOKEN_MS, firstTokenDurationMs)
        putInt(PROMPT_DECODE_CALLS, promptDecodeCalls)
        putInt(GENERATION_DECODE_CALLS, generationDecodeCalls)
    }

    fun Bundle.toNativeMetrics(): NativeMetrics = NativeMetrics(
        promptTokens = getInt(PROMPT_TOKENS, 0),
        generatedTokens = getInt(GENERATED_TOKENS, 0),
        promptEvalDurationMs = getLong(PROMPT_EVAL_MS, 0L),
        generationDurationMs = getLong(GENERATION_MS, 0L),
        totalDurationMs = getLong(TOTAL_MS, 0L),
        decodeCalls = getInt(DECODE_CALLS, 0),
        tokensPerSecond = getFloat(TOKENS_PER_SECOND, 0f),
        finishReason = getString(FINISH_REASON) ?: "NATIVE_ERROR",
        errorMessage = getString(ERROR_MESSAGE),
        promptTokenizeDurationMs = getLong(PROMPT_TOKENIZE_MS, 0L),
        firstTokenDurationMs = getLong(FIRST_TOKEN_MS, 0L),
        promptDecodeCalls = getInt(PROMPT_DECODE_CALLS, 0),
        generationDecodeCalls = getInt(GENERATION_DECODE_CALLS, 0)
    )

    fun operationResult(success: Boolean, error: String? = null): Bundle =
        Bundle().apply {
            putBoolean(SUCCESS, success)
            putString(ERROR, error)
        }

    fun Bundle.success(): Boolean = getBoolean(SUCCESS, false)
    fun Bundle.errorMessage(): String? = getString(ERROR)
}
