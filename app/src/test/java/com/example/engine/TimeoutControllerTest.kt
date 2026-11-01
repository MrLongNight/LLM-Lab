package com.example.engine

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutControllerTest {
    @Test
    fun firstTokenDeadlineAbortsWhenNoTokenArrives() = runTest {
        var nowMs = 0L
        var reason: String? = null
        val controller = TimeoutController(TimeoutConfig.MOBILE, { nowMs }, StandardTestDispatcher(testScheduler))
        val session = controller.startInferenceWatchdog(
            backgroundScope, InferenceTimeoutKind.GENERAL,
            TimeoutConfig.MOBILE.totalInferenceMs, { reason = it }
        )
        runCurrent()
        nowMs = 20_000L
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(session.isTimedOut)
        assertTrue(reason.orEmpty().contains("erstes Token"))
        session.cancel()
    }

    @Test
    fun tokenArrivalRestartsStallDeadline() = runTest {
        var nowMs = 0L
        var reason: String? = null
        val controller = TimeoutController(TimeoutConfig.MOBILE, { nowMs }, StandardTestDispatcher(testScheduler))
        val session = controller.startInferenceWatchdog(
            backgroundScope, InferenceTimeoutKind.GENERAL,
            TimeoutConfig.MOBILE.totalInferenceMs, { reason = it }
        )
        val callback = session.wrap(object : InferenceCallback {
            override fun onToken(tokenText: String, tokenIndex: Int) = Unit
            override fun onProgress(progressPercent: Float, step: Int) = Unit
        })
        runCurrent()
        callback.onToken("A", 0)
        nowMs = 14_000L
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(session.isTimedOut)
        callback.onToken("B", 1)
        nowMs = 28_000L
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(session.isTimedOut)
        nowMs = 43_000L
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(session.isTimedOut)
        assertTrue(reason.orEmpty().contains("Token-Stillstand"))
        session.cancel()
    }

    @Test
    fun totalInferenceDeadlineIsAnAbsoluteUpperBound() = runTest {
        var nowMs = 0L
        var reason: String? = null
        val controller = TimeoutController(TimeoutConfig.MOBILE, { nowMs }, StandardTestDispatcher(testScheduler))
        val session = controller.startInferenceWatchdog(
            backgroundScope, InferenceTimeoutKind.GENERAL,
            300_000L, { reason = it }
        )
        runCurrent()
        nowMs = 120_000L
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(session.isTimedOut)
        assertTrue(reason.orEmpty().contains("Gesamtlimit"))
        session.cancel()
    }
}
