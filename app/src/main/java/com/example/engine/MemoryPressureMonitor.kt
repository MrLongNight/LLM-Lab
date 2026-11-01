package com.example.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes Android memory pressure while an automated model suite is running.
 * The callback is delivered once per monitor registration.
 */
class MemoryPressureMonitor(
    context: Context,
    private val onCritical: () -> Unit
) : ComponentCallbacks2 {

    private val applicationContext = context.applicationContext ?: context
    private val registered = AtomicBoolean(false)
    private val criticalDelivered = AtomicBoolean(false)

    fun register() {
        if (registered.compareAndSet(false, true)) {
            try {
                applicationContext.registerComponentCallbacks(this)
            } catch (error: Throwable) {
                registered.set(false)
                throw error
            }
        }
    }

    fun unregister() {
        if (registered.compareAndSet(true, false)) {
            applicationContext.unregisterComponentCallbacks(this)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            notifyCritical()
        }
    }

    override fun onLowMemory() {
        notifyCritical()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun notifyCritical() {
        if (criticalDelivered.compareAndSet(false, true)) {
            onCritical()
        }
    }
}
