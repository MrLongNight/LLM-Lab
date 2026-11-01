package com.example.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns timeout policy and watchdog scheduling for native inference operations.
 * Callers supply abort behaviour; this component owns deadlines and token timing.
 */
class TimeoutController(
    private val config: TimeoutConfig,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    fun startModelInitializationWatchdog(
        scope: CoroutineScope,
        requestedLimitMs: Long,
        onTimeout: () -> Unit
    ): TimeoutHandle {
        val effectiveLimitMs = config.modelInitLimitMs(requestedLimitMs)
        val job = scope.launch(dispatcher) {
            delay(effectiveLimitMs)
            onTimeout()
        }
        return TimeoutHandle(effectiveLimitMs, job)
    }

    fun startInferenceWatchdog(
        scope: CoroutineScope,
        kind: InferenceTimeoutKind,
        requestedLimitMs: Long,
        onTimeout: (String) -> Unit
    ): InferenceTimeoutSession {
        val session = InferenceTimeoutSession(
            startedAtMs = clockMs(),
            firstTokenLimitMs = config.firstTokenMs,
            tokenStallLimitMs = config.tokenStallMs,
            totalLimitMs = config.inferenceLimitMs(kind, requestedLimitMs),
            clockMs = clockMs
        )
        val job = scope.launch(dispatcher) {
            while (isActive && !session.isTimedOut) {
                val reason = session.timeoutReasonAt(clockMs())
                if (reason != null && session.markTimedOut(reason)) {
                    onTimeout(reason)
                    break
                }
                delay(pollIntervalMs.coerceAtLeast(1L))
            }
        }
        session.attach(job)
        return session
    }

    companion object { private const val DEFAULT_POLL_INTERVAL_MS = 100L }
}

class TimeoutHandle internal constructor(val timeoutMs: Long, private val job: Job) {
    fun cancel() = job.cancel()
}

/** Per-inference timeout state shared safely with native token callbacks. */
class InferenceTimeoutSession internal constructor(
    private val startedAtMs: Long,
    private val firstTokenLimitMs: Long,
    private val tokenStallLimitMs: Long,
    private val totalLimitMs: Long,
    private val clockMs: () -> Long
) {
    private val firstTokenReceived = AtomicBoolean(false)
    private val lastTokenAtMs = AtomicLong(startedAtMs)
    private val timedOut = AtomicBoolean(false)
    private val timeoutReason = AtomicReference<String?>(null)
    private var watchdogJob: Job? = null

    val isTimedOut: Boolean get() = timedOut.get()
    val reason: String? get() = timeoutReason.get()

    internal fun attach(job: Job) { watchdogJob = job }

    internal fun timeoutReasonAt(nowMs: Long): String? {
        val elapsedMs = nowMs - startedAtMs
        val sinceLastTokenMs = nowMs - lastTokenAtMs.get()
        return when {
            elapsedMs >= totalLimitMs -> "Gesamtlimit nach ${totalLimitMs} ms erreicht."
            !firstTokenReceived.get() && elapsedMs >= firstTokenLimitMs ->
                "Kein erstes Token innerhalb von ${firstTokenLimitMs} ms."
            firstTokenReceived.get() && sinceLastTokenMs >= tokenStallLimitMs ->
                "Token-Stillstand für ${tokenStallLimitMs} ms."
            else -> null
        }
    }

    internal fun markTimedOut(reason: String): Boolean {
        if (!timedOut.compareAndSet(false, true)) return false
        timeoutReason.set(reason)
        return true
    }

    fun wrap(callback: InferenceCallback): InferenceCallback = object : InferenceCallback {
        override fun onToken(tokenText: String, tokenIndex: Int) {
            lastTokenAtMs.set(clockMs())
            firstTokenReceived.set(true)
            callback.onToken(tokenText, tokenIndex)
        }
        override fun onProgress(progressPercent: Float, step: Int) {
            callback.onProgress(progressPercent, step)
        }
    }

    fun cancel() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
