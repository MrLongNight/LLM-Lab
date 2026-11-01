package com.example.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/**
 * Hosts the native bridge in the dedicated :inference process.
 * All engine operations are delegated to the single bridge instance.
 */
class InferenceService : Service() {

    private val bridgeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LlamaNativeBridge(applicationInfo.nativeLibraryDir)
    }
    private val nativeBridge: LlamaNativeBridge by bridgeDelegate

    private val binder = object : IInferenceService.Stub() {
        override fun loadModel(path: String, contextSize: Int, requireVulkan: Boolean): Boolean =
            nativeBridge.loadModel(path, contextSize, requireVulkan)

        override fun generate(prompt: String, maxTokens: Int, timeoutMs: Int): String =
            nativeBridge.generate(prompt, maxTokens, timeoutMs)

        override fun abort() {
            nativeBridge.requestAbort()
        }

        override fun shutdown() {
            nativeBridge.shutdownProcess()
        }

        override fun getProcessId(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (bridgeDelegate.isInitialized()) {
            nativeBridge.close()
        }
        super.onDestroy()
    }
}
