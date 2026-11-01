package com.example.engine

import android.content.Context
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Public engine facade used by the ViewModel. Native work never runs in the
 * application process; this class preserves the former bridge-style API while
 * delegating every operation to InferenceClient and the :inference service.
 */
class NativeInferenceEngine(context: Context) {

    private data class LoadedModel(
        val path: String,
        val config: InferenceConfig
    )

    private val client = InferenceClient(context)
    private val abortRequested = AtomicBoolean(false)
    private val loadedModel = AtomicReference<LoadedModel?>(null)
    private val loadedProcessGeneration = AtomicLong(-1L)
    private val cachedDiagnostics = AtomicReference(Bundle())

    val connectionState: StateFlow<InferenceConnectionState> = client.connectionState

    fun prepareForOperation() {
        abortRequested.set(false)
        try {
            remote(CONNECT_TIMEOUT_MS) { service ->
                service.prepareForOperation()
                Bundle()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Inference-Prozess konnte nicht vorbereitet werden: ${error.message}", error)
        }
    }

    fun requestAbort() {
        abortRequested.set(true)
        client.requestAbort()
    }

    fun isAbortRequested(): Boolean = abortRequested.get()

    fun killInferenceProcess() {
        client.killInferenceProcess()
        loadedProcessGeneration.set(-1L)
    }

    fun loadModel(
        modelPath: String,
        config: InferenceConfig,
        outError: Array<String?>,
        timeoutMs: Long = config.timeoutInitSec.coerceAtLeast(1).toLong() * 1_000L
    ): Boolean {
        if (modelPath.isBlank()) {
            outError[0] = "Modelpfad darf nicht leer sein."
            return false
        }
        if (abortRequested.get()) {
            outError[0] = "Modellinitialisierung wurde vor dem IPC-Aufruf abgebrochen."
            return false
        }

        return try {
            val result = remote(timeoutMs) { service ->
                service.loadModel(modelPath, config.toWireBundle())
            }
            val loaded = result.success()
            if (loaded) {
                loadedModel.set(LoadedModel(modelPath, config))
                loadedProcessGeneration.set(client.connectionGeneration)
            } else {
                outError[0] = result.errorMessage() ?: "Native Modellinitialisierung fehlgeschlagen."
                loadedModel.set(null)
                loadedProcessGeneration.set(-1L)
            }
            loaded
        } catch (error: Throwable) {
            outError[0] = failureMessage("Modellinitialisierung", error)
            loadedModel.set(null)
            loadedProcessGeneration.set(-1L)
            false
        }
    }

    fun executeInference(
        prompt: String,
        config: InferenceConfig,
        callback: InferenceCallback,
        timeoutMs: Long = config.timeoutNormalInferenceSec.coerceAtLeast(1).toLong() * 1_000L
    ): NativeMetrics {
        if (abortRequested.get()) return failureMetrics(
            finishReason = "ABORTED_BY_USER",
            message = "Inferenz wurde vor dem IPC-Aufruf abgebrochen."
        )

        return try {
            ensureModelLoadedInCurrentProcess(timeoutMs)
            val remoteCallback = object : IInferenceCallback.Stub() {
                override fun onToken(tokenText: String, tokenIndex: Int) {
                    runCatching { callback.onToken(tokenText, tokenIndex) }
                        .onFailure { requestAbort() }
                }

                override fun onProgress(progressPercent: Float, step: Int) {
                    runCatching { callback.onProgress(progressPercent, step) }
                        .onFailure { requestAbort() }
                }
            }
            remote(timeoutMs) { service ->
                service.executeInference(prompt, config.toWireBundle(), remoteCallback)
            }.toNativeMetrics()
        } catch (error: Throwable) {
            val finishReason = when {
                error is TimeoutException -> "TIMEOUT"
                error is DeadObjectException || error is RemoteException ->
                    if (abortRequested.get()) "ABORTED_BY_USER" else "NATIVE_ERROR"
                abortRequested.get() -> "ABORTED_BY_USER"
                else -> "NATIVE_ERROR"
            }
            failureMetrics(finishReason, failureMessage("Inferenz", error))
        }
    }

    fun reconfigureContext(
        config: InferenceConfig,
        outError: Array<String?>,
        timeoutMs: Long = config.timeoutInitSec.coerceAtLeast(1).toLong() * 1_000L
    ): Boolean {
        return try {
            ensureModelLoadedInCurrentProcess(timeoutMs)
            val result = remote(timeoutMs) { service ->
                service.reconfigureContext(config.toWireBundle())
            }
            val configured = result.success()
            if (configured) {
                loadedModel.get()?.let { loadedModel.set(it.copy(config = config)) }
                loadedProcessGeneration.set(client.connectionGeneration)
            } else {
                outError[0] = result.errorMessage() ?: "Context-Neukonfiguration fehlgeschlagen."
            }
            configured
        } catch (error: Throwable) {
            outError[0] = failureMessage("Context-Neukonfiguration", error)
            false
        }
    }

    fun unloadModel() {
        try {
            remote(15_000L) { service ->
                service.unloadModel()
                Bundle()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Modell konnte im Inference-Prozess nicht regulär entladen werden: ${error.message}")
        } finally {
            loadedModel.set(null)
            loadedProcessGeneration.set(-1L)
        }
    }

    fun getBackendInfo(): List<NativeBackendInfo> =
        diagnostics().getString(InferenceWire.BACKEND_REPORT)
            ?.let(NativeBackendInfo::parseReport)
            ?: emptyList()

    fun getActiveBackendName(): String =
        diagnostics().getString(InferenceWire.ACTIVE_BACKEND) ?: "Nicht verfügbar"

    fun getModelLoadLog(): String =
        diagnostics().getString(InferenceWire.MODEL_LOAD_LOG) ?: ""

    fun getEngineVersion(): String =
        diagnostics().getString(InferenceWire.ENGINE_VERSION) ?: "Nicht verfügbar"

    fun getAllocatedHeap(): Long =
        diagnostics().getLong(InferenceWire.ALLOCATED_HEAP, 0L)

    fun close() {
        loadedModel.set(null)
        loadedProcessGeneration.set(-1L)
        client.close()
    }

    private fun ensureModelLoadedInCurrentProcess(timeoutMs: Long) {
        val model = checkNotNull(loadedModel.get()) {
            "Es wurde noch kein Modell über NativeInferenceEngine geladen."
        }
        runBlocking { client.connect() }
        val generation = client.connectionGeneration
        if (loadedProcessGeneration.get() == generation) return

        val result = remote(timeoutMs) { service ->
            service.loadModel(model.path, model.config.toWireBundle())
        }
        if (!result.success()) {
            throw IllegalStateException(
                result.errorMessage() ?: "Modell konnte nach Prozessneustart nicht erneut geladen werden."
            )
        }
        loadedProcessGeneration.set(generation)
    }

    private fun diagnostics(): Bundle {
        return try {
            remote(DIAGNOSTICS_TIMEOUT_MS) { it.getDiagnostics() }
                .also(cachedDiagnostics::set)
        } catch (error: Throwable) {
            Log.w(TAG, "Engine-Diagnose über Binder nicht verfügbar: ${error.message}")
            cachedDiagnostics.get()
        }
    }

    private fun <T : Any> remote(
        timeoutMs: Long,
        block: suspend (IInferenceService) -> T
    ): T = runBlocking {
        client.withGuaranteedAbort(timeoutMs.coerceAtLeast(1L), block)
    }

    private fun failureMessage(operation: String, error: Throwable): String =
        when (error) {
            is TimeoutException -> "$operation: Zeitlimit erreicht; der isolierte Inference-Prozess wurde beendet."
            is DeadObjectException, is RemoteException ->
                "$operation: Verbindung zum Inference-Prozess verloren. Eine neue Verbindung wird beim nächsten Vorgang aufgebaut."
            else -> "$operation fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
        }

    private fun failureMetrics(finishReason: String, message: String) = NativeMetrics(
        promptTokens = 0,
        generatedTokens = 0,
        promptEvalDurationMs = 0L,
        generationDurationMs = 0L,
        totalDurationMs = 0L,
        decodeCalls = 0,
        tokensPerSecond = 0f,
        finishReason = finishReason,
        errorMessage = message
    )

    companion object {
        private const val TAG = "NativeInferenceEngine"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val DIAGNOSTICS_TIMEOUT_MS = 5_000L
    }
}
