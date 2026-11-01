package com.example.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/**
 * Binder client for the isolated native inference process.
 *
 * Native Binder calls are synchronous. The operation coroutine is deliberately
 * detached from the caller's Job so a timeout can run the process-kill path
 * instead of waiting for a non-cooperative JNI call to return.
 */
class InferenceClient(context: Context) {

    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Any()

    @Volatile
    private var service: IInferenceService? = null

    @Volatile
    private var servicePid: Int = 0

    private var serviceConnection: ServiceConnection? = null
    private var isBound: Boolean = false

    suspend fun connect(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): IInferenceService {
        require(timeoutMs > 0L) { "timeoutMs muss größer als 0 sein." }

        service?.let { return it }

        return connectMutex.withLock {
            service?.let { return@withLock it }

            val connected = CompletableDeferred<IInferenceService>()
            lateinit var newConnection: ServiceConnection

            newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        connected.completeExceptionally(
                            IllegalStateException("InferenceService lieferte keinen Binder.")
                        )
                        return
                    }
                    val remote = IInferenceService.Stub.asInterface(binder)
                    service = remote
                    connected.complete(remote)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    servicePid = 0
                    connected.completeExceptionally(
                        IllegalStateException("Verbindung zum Inference-Prozess wurde getrennt.")
                    )
                }

                override fun onBindingDied(name: ComponentName?) {
                    service = null
                    servicePid = 0
                    connected.completeExceptionally(
                        IllegalStateException("Bindung zum Inference-Prozess ist beendet.")
                    )
                }

                override fun onNullBinding(name: ComponentName?) {
                    connected.completeExceptionally(
                        IllegalStateException("InferenceService lieferte eine leere Bindung.")
                    )
                }
            }

            synchronized(connectionLock) {
                serviceConnection = newConnection
            }

            val intent = Intent(appContext, InferenceService::class.java)
            val bound = appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                detachConnection(newConnection)
                throw IllegalStateException("InferenceService konnte nicht gestartet werden.")
            }
            synchronized(connectionLock) {
                if (serviceConnection === newConnection) isBound = true
            }

            try {
                val remote = withTimeout(timeoutMs) { connected.await() }
                servicePid = withContext(Dispatchers.IO) { remote.processId }
                check(servicePid > 0) { "InferenceService lieferte eine ungültige Prozess-ID." }
                remote
            } catch (failure: Throwable) {
                connected.cancel()
                detachConnection(newConnection)
                throw failure
            }
        }
    }

    /**
     * Requests service shutdown, then independently kills its PID. The direct
     * kill is the guarantee: it does not depend on a Binder reply or JNI abort.
     */
    fun killInferenceProcess() {
        val remote = service
        val pid = servicePid

        if (remote != null) {
            runCatching {
                Thread(
                    { runCatching { remote.shutdown() } },
                    "inference-shutdown-request"
                ).apply {
                    isDaemon = true
                    start()
                }
            }
        }

        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid)
        }

        runCatching {
            appContext.stopService(Intent(appContext, InferenceService::class.java))
        }
        detachConnection()
    }

    /**
     * Runs a blocking Binder operation on IO and enforces a hard deadline by
     * killing the whole remote process when the operation does not return.
     */
    suspend fun <T : Any> withGuaranteedAbort(
        timeoutMs: Long,
        block: suspend (IInferenceService) -> T
    ): T {
        require(timeoutMs > 0L) { "timeoutMs muss größer als 0 sein." }
        val remote = service ?: connect()
        val operation = ioScope.async { block(remote) }

        try {
            val result = withTimeoutOrNull(timeoutMs) { operation.await() }
            if (result != null) return result

            withContext(NonCancellable + Dispatchers.IO) {
                killInferenceProcess()
            }
            operation.cancel()
            throw TimeoutException(
                "Inferenz nach ${timeoutMs}ms abgebrochen (Prozess neu gestartet)"
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                killInferenceProcess()
            }
            operation.cancel()
            throw cancelled
        } catch (failure: Throwable) {
            operation.cancel()
            throw failure
        }
    }

    private fun detachConnection(expected: ServiceConnection? = null) {
        val connectionToUnbind: ServiceConnection?
        val shouldUnbind: Boolean

        synchronized(connectionLock) {
            if (expected != null && serviceConnection !== expected) return
            connectionToUnbind = serviceConnection
            shouldUnbind = isBound
            serviceConnection = null
            isBound = false
            service = null
            servicePid = 0
        }

        if (shouldUnbind && connectionToUnbind != null) {
            runCatching { appContext.unbindService(connectionToUnbind) }
        }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    }
}
