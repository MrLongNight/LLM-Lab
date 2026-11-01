package com.example.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

enum class InferenceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Binder client for the isolated native inference process.
 *
 * The actual Binder calls remain synchronous, but run in a detached IO scope.
 * A timeout or caller cancellation kills the remote PID rather than relying on
 * JNI to return from a potentially non-cooperative CPU/Vulkan call.
 */
class InferenceClient(context: Context) {

    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Any()

    private val _connectionState = MutableStateFlow(InferenceConnectionState.DISCONNECTED)
    val connectionState: StateFlow<InferenceConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var service: IInferenceService? = null

    @Volatile
    private var servicePid: Int = 0

    @Volatile
    var connectionGeneration: Long = 0L
        private set

    @Volatile
    private var hasConnectedSuccessfully: Boolean = false

    private var serviceConnection: ServiceConnection? = null
    private var isBound: Boolean = false

    private suspend fun existingLiveService(): IInferenceService? {
        val candidate = service ?: return null
        return try {
            val pid = withContext(Dispatchers.IO) { candidate.processId }
            check(pid > 0) { "InferenceService lieferte eine ungültige Prozess-ID." }
            servicePid = pid
            candidate
        } catch (_: Throwable) {
            detachConnection()
            _connectionState.value = InferenceConnectionState.RECONNECTING
            null
        }
    }

    suspend fun connect(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): IInferenceService {
        require(timeoutMs > 0L) { "timeoutMs muss größer als 0 sein." }
        existingLiveService()?.let { return it }

        return connectMutex.withLock {
            existingLiveService()?.let { return@withLock it }

            _connectionState.value = if (hasConnectedSuccessfully) {
                InferenceConnectionState.RECONNECTING
            } else {
                InferenceConnectionState.CONNECTING
            }

            val connected = CompletableDeferred<IInferenceService>()
            val newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        connected.completeExceptionally(
                            IllegalStateException("InferenceService lieferte keinen Binder.")
                        )
                        return
                    }
                    val remote = IInferenceService.Stub.asInterface(binder)
                    service = remote
                    _connectionState.value = InferenceConnectionState.CONNECTED
                    connectionGeneration += 1L
                    connected.complete(remote)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    servicePid = 0
                    _connectionState.value = InferenceConnectionState.RECONNECTING
                    connected.completeExceptionally(
                        IllegalStateException("Verbindung zum Inference-Prozess wurde getrennt.")
                    )
                }

                override fun onBindingDied(name: ComponentName?) {
                    service = null
                    servicePid = 0
                    _connectionState.value = InferenceConnectionState.RECONNECTING
                    connected.completeExceptionally(
                        IllegalStateException("Bindung zum Inference-Prozess ist beendet.")
                    )
                }

                override fun onNullBinding(name: ComponentName?) {
                    _connectionState.value = InferenceConnectionState.FAILED
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
                _connectionState.value = InferenceConnectionState.FAILED
                throw IllegalStateException("InferenceService konnte nicht gestartet werden.")
            }
            synchronized(connectionLock) {
                if (serviceConnection === newConnection) isBound = true
            }

            try {
                val remote = withTimeout(timeoutMs) { connected.await() }
                servicePid = withContext(Dispatchers.IO) { remote.processId }
                check(servicePid > 0) { "InferenceService lieferte eine ungültige Prozess-ID." }
                hasConnectedSuccessfully = true
                _connectionState.value = InferenceConnectionState.CONNECTED
                remote
            } catch (failure: Throwable) {
                connected.cancel()
                detachConnection(newConnection)
                _connectionState.value = if (hasConnectedSuccessfully) {
                    InferenceConnectionState.RECONNECTING
                } else {
                    InferenceConnectionState.FAILED
                }
                throw failure
            }
        }
    }

    /**
     * Best-effort abort transaction. It is intentionally dispatched separately
     * so the caller never waits behind a currently blocked Binder/JNI request.
     */
    fun requestAbort() {
        val remote = service ?: return
        Thread(
            { runCatching { remote.abort() } },
            "inference-abort-request"
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Requests shutdown, then independently kills the remote PID. This is the
     * hard-abort guarantee and does not depend on a Binder reply or JNI abort.
     */
    fun killInferenceProcess() {
        val remote = service
        val pid = servicePid
        _connectionState.value = InferenceConnectionState.RECONNECTING

        if (remote != null && pid > 0 && pid != Process.myPid()) {
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
        _connectionState.value = InferenceConnectionState.RECONNECTING
    }

    /**
     * Runs a Binder operation on IO and enforces a hard deadline by killing the
     * whole remote process when the operation does not return.
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
                "Inferenz nach ${timeoutMs}ms abgebrochen (Inference-Prozess beendet)"
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                killInferenceProcess()
            }
            operation.cancel()
            throw cancelled
        } catch (failure: Throwable) {
            operation.cancel()
            if (failure is DeadObjectException || failure is RemoteException) {
                detachConnection()
                _connectionState.value = InferenceConnectionState.RECONNECTING
            }
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

    fun close() {
        killInferenceProcess()
        _connectionState.value = InferenceConnectionState.DISCONNECTED
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    }
}
