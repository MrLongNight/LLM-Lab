package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessCpuUsageSamplerTest {

    @Test
    fun firstSampleIsUnavailableUntilAnIntervalExists() {
        val sampler = ProcessCpuUsageSampler()
        assertNull(sampler.sample(cpuTimeMs = 100, elapsedTimeMs = 1_000, logicalCpuCount = 4))
    }

    @Test
    fun calculatesProcessCpuPercentageAcrossLogicalCpus() {
        val sampler = ProcessCpuUsageSampler()
        sampler.sample(cpuTimeMs = 1_000, elapsedTimeMs = 1_000, logicalCpuCount = 4)
        val usage = sampler.sample(cpuTimeMs = 1_250, elapsedTimeMs = 2_000, logicalCpuCount = 4)
        assertEquals(6.25f, usage!!, 0.001f)
    }

    @Test
    fun clampsUsageAndRejectsInvalidIntervals() {
        val sampler = ProcessCpuUsageSampler()
        sampler.sample(cpuTimeMs = 0, elapsedTimeMs = 1_000, logicalCpuCount = 4)
        assertEquals(100f, sampler.sample(cpuTimeMs = 5_000, elapsedTimeMs = 2_000, logicalCpuCount = 4)!!, 0.001f)

        val invalid = ProcessCpuUsageSampler()
        invalid.sample(cpuTimeMs = 100, elapsedTimeMs = 1_000, logicalCpuCount = 4)
        assertNull(invalid.sample(cpuTimeMs = 90, elapsedTimeMs = 2_000, logicalCpuCount = 4))
    }
}
