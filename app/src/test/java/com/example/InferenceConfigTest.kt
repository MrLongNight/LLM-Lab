package com.example

import com.example.engine.InferenceConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies which settings require rebuilding the native llama.cpp context. */
class InferenceConfigTest {
    @Test
    fun runtimeSettingsDoNotRequireContextReloadButNativeContextSettingsDo() {
        val loaded = InferenceConfig()
        assertTrue(loaded.hasSameNativeContext(loaded.copy(temperature = 0.5f, maxTokens = 128)))
        assertFalse(loaded.hasSameNativeContext(loaded.copy(nUbatch = 16)))
        assertFalse(loaded.hasSameNativeContext(loaded.copy(nThreads = 2)))
        assertFalse(loaded.hasSameNativeContext(loaded.copy(nCtx = loaded.nCtx * 2)))
    }
}
