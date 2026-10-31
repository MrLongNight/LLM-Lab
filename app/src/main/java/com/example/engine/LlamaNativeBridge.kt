package com.example.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class LlamaNativeBridge {

    companion object {
        private const val TAG = "LlamaNativeBridge"
        var isNativeLibraryLoaded = false
            private set

        init {
            try {
                System.loadLibrary("llmlab_native")
                isNativeLibraryLoaded = true
                Log.i(TAG, "Native llama.cpp Bibliothek erfolgreich geladen")
            } catch (t: Throwable) {
                isNativeLibraryLoaded = false
                Log.e(TAG, "Native llama.cpp Bibliothek nicht verfügbar: ${t.message}", t)
            }
        }
    }

    private var engineHandle: Long = 0L
    private val abortFlag = AtomicBoolean(false)

    init {
        if (isNativeLibraryLoaded) {
            try {
                engineHandle = nativeCreateEngine()
                if (engineHandle == 0L) {
                    Log.e(TAG, "nativeCreateEngine lieferte einen ungültigen Handle")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native Engine konnte nicht instanziiert werden: ${t.message}", t)
            }
        }
    }

    fun getBackendInfo(): List<NativeBackendInfo> {
        if (!isNativeLibraryLoaded || engineHandle == 0L) return emptyList()
        return runCatching { NativeBackendInfo.parseReport(nativeGetBackendReport()) }
            .onFailure { Log.w(TAG, "Backend-Erkennung fehlgeschlagen: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun getActiveBackendName(): String {
        if (!isNativeLibraryLoaded || engineHandle == 0L) return "Native Engine nicht verfügbar"
        return runCatching { nativeGetActiveBackendName(engineHandle) }
            .getOrDefault("Nicht verfügbar")
    }

    fun getEngineVersion(): String {
        if (!isNativeLibraryLoaded || engineHandle == 0L) {
            return "Nicht verfügbar: native llama.cpp Bibliothek wurde nicht geladen"
        }
        return try {
            nativeGetVersion()
        } catch (t: Throwable) {
            "Nicht verfügbar: ${t.message}"
        }
    }

    fun prepareForOperation() {
        abortFlag.set(false)
        if (isNativeLibraryLoaded && engineHandle != 0L) {
            try {
                nativeResetAbort(engineHandle)
                // Ein während des synchronisierten Resets eintreffender Nutzer- oder
                // Timeout-Abbruch wird direkt danach erneut an C++ weitergereicht.
                if (abortFlag.get()) nativeRequestAbort(engineHandle)
            } catch (t: Throwable) {
                Log.e(TAG, "Abort-Zustand konnte nicht zurückgesetzt werden: ${t.message}", t)
            }
        }
    }

    fun loadModel(
        modelPath: String,
        config: InferenceConfig,
        outError: Array<String?>
    ): Boolean {
        if (!isNativeLibraryLoaded || engineHandle == 0L) {
            outError[0] = "Native llama.cpp Engine nicht verfügbar. Es wird kein JVM-Fallback verwendet."
            return false
        }
        if (abortFlag.get()) {
            outError[0] = "Modellinitialisierung vor dem JNI-Aufruf abgebrochen."
            return false
        }
        return try {
            nativeLoadModel(
                engineHandle,
                modelPath,
                config.nCtx,
                config.nBatch,
                config.nUbatch,
                config.nThreads,
                config.nThreadsBatch,
                config.backendPreference.nativeCode,
                outError
            )
        } catch (t: Throwable) {
            outError[0] = "Native Ausnahme beim Laden: ${t.message}"
            false
        }
    }

    fun unloadModel() {
        requestAbort()
        if (isNativeLibraryLoaded && engineHandle != 0L) {
            try {
                nativeUnloadModel(engineHandle)
            } catch (t: Throwable) {
                Log.e(TAG, "Fehler beim nativen Entladen: ${t.message}", t)
            }
        }
    }

    fun requestAbort() {
        abortFlag.set(true)
        if (isNativeLibraryLoaded && engineHandle != 0L) {
            try {
                nativeRequestAbort(engineHandle)
            } catch (t: Throwable) {
                Log.e(TAG, "Fehler beim nativen Abbruchsignal: ${t.message}", t)
            }
        }
    }

    fun isAbortRequested(): Boolean = abortFlag.get()

    fun executeInference(
        prompt: String,
        config: InferenceConfig,
        callback: InferenceCallback
    ): NativeMetrics {
        if (!isNativeLibraryLoaded || engineHandle == 0L) {
            return unavailableMetrics("Native llama.cpp Engine nicht verfügbar. Keine Inferenz ausgeführt.")
        }
        if (abortFlag.get()) {
            return unavailableMetrics("Inference wurde vor dem JNI-Aufruf abgebrochen.")
                .copy(finishReason = "ABORTED_BY_USER")
        }
        return try {
            nativeExecuteInference(
                engineHandle,
                prompt,
                config.nCtx,
                config.nBatch,
                config.nUbatch,
                config.nThreads,
                config.nThreadsBatch,
                config.temperature,
                config.topK,
                config.topP,
                config.maxTokens,
                callback
            ) ?: unavailableMetrics("JNI lieferte kein NativeMetrics-Objekt")
        } catch (t: Throwable) {
            Log.e(TAG, "Native Inferenz fehlgeschlagen: ${t.message}", t)
            unavailableMetrics("JNI-Ausführungsfehler: ${t.message}")
        }
    }

    private fun unavailableMetrics(error: String) = NativeMetrics(
        promptTokens = 0,
        generatedTokens = 0,
        promptEvalDurationMs = 0,
        generationDurationMs = 0,
        totalDurationMs = 0,
        decodeCalls = 0,
        tokensPerSecond = 0f,
        finishReason = "NATIVE_ERROR",
        errorMessage = error
    )

    fun getAllocatedHeap(): Long {
        if (!isNativeLibraryLoaded || engineHandle == 0L) return 0L
        return try {
            nativeGetAllocatedHeap(engineHandle)
        } catch (t: Throwable) {
            Log.w(TAG, "Native Heap-Messung nicht verfügbar: ${t.message}")
            0L
        }
    }

    fun close() {
        if (isNativeLibraryLoaded && engineHandle != 0L) {
            try {
                nativeDestroyEngine(engineHandle)
            } catch (t: Throwable) {
                Log.e(TAG, "Fehler beim Zerstören der Engine: ${t.message}", t)
            } finally {
                engineHandle = 0L
            }
        }
    }

    private external fun nativeGetBackendReport(): String
    private external fun nativeGetActiveBackendName(handle: Long): String
    private external fun nativeGetVersion(): String
    private external fun nativeCreateEngine(): Long
    private external fun nativeDestroyEngine(handle: Long)
    private external fun nativeLoadModel(
        handle: Long,
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        backendPreference: Int,
        outError: Array<String?>
    ): Boolean
    private external fun nativeUnloadModel(handle: Long)
    private external fun nativeRequestAbort(handle: Long)
    private external fun nativeResetAbort(handle: Long)
    private external fun nativeExecuteInference(
        handle: Long,
        prompt: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        maxTokens: Int,
        callback: InferenceCallback
    ): NativeMetrics
    private external fun nativeGetAllocatedHeap(handle: Long): Long
}
