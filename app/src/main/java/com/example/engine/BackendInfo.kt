package com.example.engine

import org.json.JSONArray

enum class BackendPreference(val nativeCode: Int, val label: String, val description: String) {
    AUTO(0, "Automatisch", "Nutzt einen verfügbaren Beschleuniger, sonst CPU."),
    CPU_ONLY(1, "Nur CPU", "Erzwingt den portablen CPU-Fallback."),
    ACCELERATOR_REQUIRED(2, "Beschleuniger erforderlich", "Startet nur, wenn ein GPU-/Accelerator-Backend tatsächlich initialisiert werden kann.")
}

data class NativeBackendInfo(
    val registryName: String,
    val runtimeName: String,
    val kind: String,
    val bufferType: String,
    val available: Boolean
) {
    companion object {
        fun parseReport(raw: String): List<NativeBackendInfo> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        NativeBackendInfo(
                            registryName = item.optString("registryName", "Unbekannt"),
                            runtimeName = item.optString("runtimeName", ""),
                            kind = item.optString("kind", "UNBEKANNT"),
                            bufferType = item.optString("bufferType", ""),
                            available = item.optBoolean("available", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
