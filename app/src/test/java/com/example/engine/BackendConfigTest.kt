package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendConfigTest {

    @Test
    fun parsesRuntimeAvailabilityAndAbortCapability() {
        val backends = NativeBackendInfo.parseReport(
            """[
                {"registryName":"CPU","runtimeName":"CPU","kind":"CPU","bufferType":"CPU","available":true,"supportsAbort":true},
                {"registryName":"Vulkan","runtimeName":"Vulkan0","kind":"GPU/ACCELERATOR","bufferType":"Vulkan0","available":true,"supportsAbort":false}
            ]"""
        )

        assertEquals(2, backends.size)
        assertTrue(backends[0].available)
        assertTrue(backends[0].supportsAbort)
        assertEquals("Vulkan0", backends[1].runtimeName)
        assertTrue(backends[1].available)
        assertFalse(backends[1].supportsAbort)
    }

    @Test
    fun malformedBackendRegistryReturnsNoFalseCapabilities() {
        assertTrue(NativeBackendInfo.parseReport("not-json").isEmpty())
        assertTrue(NativeBackendInfo.parseReport("[]").isEmpty())
    }

    @Test
    fun autoModeIsDefaultAndAcceleratorModeIsExplicit() {
        val defaults = InferenceConfig()
        assertEquals(BackendPreference.AUTO, defaults.backendPreference)
        assertEquals(0, BackendPreference.AUTO.nativeCode)
        assertEquals(1, BackendPreference.CPU_ONLY.nativeCode)
        assertEquals(2, BackendPreference.ACCELERATOR_REQUIRED.nativeCode)
    }

    @Test
    fun samplingCanChangeWithoutReloadingNativeContext() {
        val base = InferenceConfig()
        val samplingChanged = base.copy(temperature = 0.6f, topK = 40, topP = 0.9f, maxTokens = 256)
        assertTrue(base.hasSameNativeContext(samplingChanged))
    }

    @Test
    fun prefillThreadsAndBackendRequireNativeContextReload() {
        val base = InferenceConfig()
        assertFalse(base.hasSameNativeContext(base.copy(nThreadsBatch = base.nThreadsBatch - 1)))
        assertFalse(base.hasSameNativeContext(base.copy(backendPreference = BackendPreference.CPU_ONLY)))
        assertFalse(base.hasSameNativeContext(base.copy(nUbatch = 64)))
    }
}
