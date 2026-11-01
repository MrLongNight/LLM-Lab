package com.example.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hosts the complete native inference API in the dedicated :inference process.
 * All model/JNI operations are serialized; abort deliberately bypasses that lock
 * so it can interrupt an operation currently executing on another Binder thread.
 */
class InferenceService : Service() {

    private val bridgeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LlamaNativeBridge(applicationInfo.nativeLibraryDir)
    }
    private val nativeBridge: LlamaNativeBridge by bridgeDelegate
    private val engineLock = ReentrantLock()

    private val binder = object : IInferenceService.Stub() {
        override fun prepareForOperation() {
            engineLock.withLock { nativeBridge.prepareForOperation() }
        }

        override fun loadModel(path: String, config: Bundle): Bundle = engineLock.withLock {
            val outError = arrayOfNulls<String>(1)
            val loaded = nativeBridge.loadModel(path, config.toInferenceConfig(), outError)
            InferenceWire.operationResult(loaded, outError[0])
        }

        override fun executeInference(
            prompt: String,
            config: Bundle,
            callback: IInferenceCallback
        ): Bundle = engineLock.withLock {
            val localCallback = object : InferenceCallback {
                override fun onToken(tokenText: String, tokenIndex: Int) {
                    try {
                        callback.onToken(tokenText, tokenIndex)
                    } catch (_: RemoteException) {
                        nativeBridge.requestAbort()
                    }
                }

                override fun onProgress(progressPercent: Float, step: Int) {
                    try {
                        callback.onProgress(progressPercent, step)
                    } catch (_: RemoteException) {
                        nativeBridge.requestAbort()
                    }
                }
            }
            nativeBridge.executeInference(prompt, config.toInferenceConfig(), localCallback)
                .toWireBundle()
        }

        override fun reconfigureContext(config: Bundle): Bundle = engineLock.withLock {
            val outError = arrayOfNulls<String>(1)
            val configured = nativeBridge.reconfigureContext(config.toInferenceConfig(), outError)
            InferenceWire.operationResult(configured, outError[0])
        }

        override fun unloadModel() {
            engineLock.withLock { nativeBridge.unloadModel() }
        }

        // Must not acquire engineLock: this method needs to run while JNI is busy.
        override fun abort() {
            if (bridgeDelegate.isInitialized()) nativeBridge.requestAbort()
        }

        override fun getDiagnostics(): Bundle = engineLock.withLock {
            Bundle().apply {
                putBoolean(InferenceWire.NATIVE_LIBRARY_LOADED, LlamaNativeBridge.isNativeLibraryLoaded)
                putString(InferenceWire.BACKEND_REPORT, nativeBridge.getBackendReportRaw())
                putString(InferenceWire.ENGINE_VERSION, nativeBridge.getEngineVersion())
                putString(InferenceWire.ACTIVE_BACKEND, nativeBridge.getActiveBackendName())
                putString(InferenceWire.MODEL_LOAD_LOG, nativeBridge.getModelLoadLog())
                putLong(InferenceWire.ALLOCATED_HEAP, nativeBridge.getAllocatedHeap())
            }
        }

        override fun shutdown() {
            nativeBridge.shutdownProcess()
        }

        override fun getProcessId(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (bridgeDelegate.isInitialized()) {
            engineLock.withLock { nativeBridge.close() }
        }
        super.onDestroy()
    }
}
