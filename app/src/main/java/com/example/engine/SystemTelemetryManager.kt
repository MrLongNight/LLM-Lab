package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.io.File

data class SystemTelemetry(
    val javaHeapUsedMb: Float,
    val javaHeapTotalMb: Float,
    val javaHeapMaxMb: Float,
    val nativeHeapAllocatedMb: Float,
    val nativeHeapSizeMb: Float,
    val systemAvailableRamMb: Float,
    val systemTotalRamMb: Float,
    val systemRamPercentUsed: Float,
    val cpuArch: String,
    val cpuCores: Int,
    val thermalStatus: String,
    val isLowMemory: Boolean
)

object SystemTelemetryManager {

    fun capture(context: Context): SystemTelemetry {
        // Java Heap
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)
        val javaTotal = rt.totalMemory() / (1024f * 1024f)
        val javaMax = rt.maxMemory() / (1024f * 1024f)

        // Native Heap
        val nativeAllocated = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
        val nativeTotal = Debug.getNativeHeapSize() / (1024f * 1024f)

        // System RAM
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        var availRamMb = 0f
        var totalRamMb = 0f
        var isLowMem = false
        var ramPercent = 0f

        if (am != null) {
            am.getMemoryInfo(memInfo)
            availRamMb = memInfo.availMem / (1024f * 1024f)
            totalRamMb = memInfo.totalMem / (1024f * 1024f)
            isLowMem = memInfo.lowMemory
            if (totalRamMb > 0) {
                ramPercent = ((totalRamMb - availRamMb) / totalRamMb) * 100f
            }
        }

        // Thermal Status
        val thermalStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Normal (Kein Drosseln)"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Leicht erhöht (Light)"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderat (Moderate)"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Kritisch erhöht (Severe Drosselung!)"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Sehr kritisch (Shutting down!)"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Notabschaltung aktiv (Emergency!)"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Geräte-Shutdown eingeleitet"
                    else -> "Nicht verfügbar"
                }
            } catch (_: Throwable) {
                "Nicht verfügbar"
            }
        } else {
            "Nicht verfügbar (Erfordert Android 10+)"
        }

        val arch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Nicht verfügbar"
        val cores = Runtime.getRuntime().availableProcessors()

        return SystemTelemetry(
            javaHeapUsedMb = javaUsed,
            javaHeapTotalMb = javaTotal,
            javaHeapMaxMb = javaMax,
            nativeHeapAllocatedMb = nativeAllocated,
            nativeHeapSizeMb = nativeTotal,
            systemAvailableRamMb = availRamMb,
            systemTotalRamMb = totalRamMb,
            systemRamPercentUsed = ramPercent,
            cpuArch = arch,
            cpuCores = cores,
            thermalStatus = thermalStr,
            isLowMemory = isLowMem
        )
    }
}
