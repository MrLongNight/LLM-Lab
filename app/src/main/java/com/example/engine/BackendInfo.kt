package com.example.engine

import org.json.JSONArray

enum class BackendPreference(val nativeCode: Int, val label: String, val description: String) {
    AUTO(0, "Automatisch (sicher abbrechbar)", "Verwendet auf llama.cpp b11998 CPU, solange GPU-Abbruch nicht zuverlässig unterstützt wird."),
    CPU_ONLY(1, "Nur CPU", "Erzwingt den portablen CPU-Fallback."),
    ACCELERATOR_REQUIRED(2, "Beschleuniger-Test (experimentell)", "Erzwingt GPU-/Accelerator-Offload. Abbruch/Timeout ist in llama.cpp b11998 dafür nicht garantiert.")
}

data class NativeBackendInfo(
    val registryName: String,
    val runtimeName: String,
    val kind: String,
    val bufferType: String,
    val available: Boolean,
    val supportsAbort: Boolean,
    val description: String = "",
    val memoryFreeBytes: Long = 0L,
    val memoryTotalBytes: Long = 0L
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
                            available = item.optBoolean("available", false),
                            supportsAbort = item.optBoolean("supportsAbort", false),
                            description = item.optString("description", ""),
                            memoryFreeBytes = item.optLong("memoryFreeBytes", 0L),
                            memoryTotalBytes = item.optLong("memoryTotalBytes", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
