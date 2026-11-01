package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeoutConfigTest {
    @Test
    fun mobileProfileUsesStagedMobileDefaults() {
        assertEquals(60_000L, TimeoutConfig.MOBILE.modelInitMs)
        assertEquals(20_000L, TimeoutConfig.MOBILE.firstTokenMs)
        assertEquals(15_000L, TimeoutConfig.MOBILE.tokenStallMs)
        assertEquals(120_000L, TimeoutConfig.MOBILE.totalInferenceMs)
        assertEquals(90_000L, TimeoutConfig.MOBILE.dmsMs)
        assertEquals(120_000L, TimeoutConfig.MOBILE.extractionMs)
        assertEquals(90_000L, TimeoutConfig.MOBILE.performanceMs)
    }

    @Test
    fun legacyProfilePreservesRegressionTimeouts() {
        assertEquals(120_000L, TimeoutConfig.LEGACY.modelInitMs)
        assertEquals(120_000L, TimeoutConfig.LEGACY.firstTokenMs)
        assertEquals(120_000L, TimeoutConfig.LEGACY.tokenStallMs)
        assertEquals(120_000L, TimeoutConfig.LEGACY.totalInferenceMs)
        assertEquals(180_000L, TimeoutConfig.LEGACY.dmsMs)
        assertEquals(240_000L, TimeoutConfig.LEGACY.extractionMs)
        assertEquals(180_000L, TimeoutConfig.LEGACY.performanceMs)
    }
}
