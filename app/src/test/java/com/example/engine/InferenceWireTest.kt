package com.example.engine

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InferenceWireTest {

    @Test
    fun `all inference configuration fields survive the process wire format`() {
        val original = InferenceConfig(
            selectedProfile = ProfilePreset.MAX_THROUGHPUT,
            nCtx = 4096,
            nBatch = 384,
            nUbatch = 64,
            nThreads = 6,
            nThreadsBatch = 8,
            backendPreference = BackendPreference.ACCELERATOR_REQUIRED,
            temperature = 0.15f,
            topK = 37,
            topP = 0.91f,
            maxTokens = 192,
            timeoutInitSec = 150,
            timeoutShortTestSec = 45,
            timeoutNormalInferenceSec = 95,
            timeoutStressTestSec = 180
        )

        val restored = original.toWireBundle().toInferenceConfig()
        assertEquals(original, restored)
    }

    @Test
    fun `all native metrics survive the process wire format`() {
        val original = NativeMetrics(
            promptTokens = 321,
            generatedTokens = 47,
            promptEvalDurationMs = 1_234L,
            generationDurationMs = 5_678L,
            totalDurationMs = 6_912L,
            decodeCalls = 55,
            tokensPerSecond = 8.28f,
            finishReason = "MAX_TOKENS",
            errorMessage = null,
            promptTokenizeDurationMs = 111L,
            firstTokenDurationMs = 222L,
            promptDecodeCalls = 8,
            generationDecodeCalls = 47
        )

        assertEquals(original, original.toWireBundle().toNativeMetrics())
    }
}
