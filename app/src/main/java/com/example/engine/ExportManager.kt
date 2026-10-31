package com.example.engine

import android.content.Context
import android.os.Build
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import java.io.IOException
import com.example.BuildConfig
import com.example.data.TestRunEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportManager {

    fun generateJson(
        context: Context,
        runs: List<TestRunEntity>,
        config: InferenceConfig,
        telemetry: SystemTelemetry,
        engineVersion: String,
        modelName: String,
        modelSizeFormatted: String,
        modelInfo: GgufInfo? = null,
        modelStatus: String = "UNKNOWN",
        backendInfo: List<NativeBackendInfo> = emptyList(),
        activeBackendName: String = "Nicht verfügbar",
        modelLoadLog: String = "",
        workflowBackendReport: String = ""
    ): String {
        val root = JSONObject()
        val nowStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.GERMANY).format(Date())

        root.put("exportTimestamp", nowStr)
        val installedPackageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        root.put("packageName", context.packageName)
        root.put("installedPackageVersion", installedPackageInfo?.versionName ?: JSONObject.NULL)
        root.put("installedVersionCode", installedPackageInfo?.longVersionCode ?: JSONObject.NULL)
        root.put("buildConfigVersion", BuildConfig.VERSION_NAME)
        root.put("buildCommit", BuildConfig.GIT_COMMIT)
        root.put("modelStatus", modelStatus)
        root.put("activeBackendSelection", activeBackendName)
        root.put("nativeModelLoadLog", modelLoadLog.take(65536))
        root.put("workflowBackendOffloadEvidence", workflowBackendReport.take(16384))
        val backendArray = JSONArray()
        backendInfo.forEach { backend ->
            backendArray.put(JSONObject().apply {
                put("registryName", backend.registryName)
                put("runtimeName", backend.runtimeName)
                put("kind", backend.kind)
                put("bufferType", backend.bufferType)
                put("available", backend.available)
            })
        }
        root.put("backendRegistry", backendArray)
        root.put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        root.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("cpuArch", telemetry.cpuArch)
        root.put("cpuCores", telemetry.cpuCores)
        root.put("thermalStatus", telemetry.thermalStatus)
        root.put("engineVersion", engineVersion)
        root.put("modelName", modelName)
        root.put("modelSize", modelSizeFormatted)
        val modelMetadata = JSONObject()
        modelMetadata.put("available", modelInfo != null)
        modelMetadata.put("ggufVersion", modelInfo?.version ?: JSONObject.NULL)
        modelMetadata.put("tensorCount", modelInfo?.tensorCount ?: JSONObject.NULL)
        modelMetadata.put("metadataEntryCount", modelInfo?.kvCount ?: JSONObject.NULL)
        modelMetadata.put("architecture", modelInfo?.architecture ?: JSONObject.NULL)
        modelMetadata.put("fileType", modelInfo?.fileTypeDescription ?: JSONObject.NULL)
        modelMetadata.put("contextLength", modelInfo?.contextLength ?: JSONObject.NULL)
        modelMetadata.put("embeddingLength", modelInfo?.embeddingLength ?: JSONObject.NULL)
        modelMetadata.put("blockCount", modelInfo?.blockCount ?: JSONObject.NULL)
        root.put("modelMetadata", modelMetadata)

        val params = JSONObject()
        params.put("profile", config.selectedProfile.name)
        params.put("n_ctx", config.nCtx)
        params.put("n_batch", config.nBatch)
        params.put("n_ubatch", config.nUbatch)
        params.put("n_threads", config.nThreads)
        params.put("n_threads_batch", config.nThreadsBatch)
        params.put("backend_preference", config.backendPreference.name)
        params.put("temperature", config.temperature)
        params.put("top_k", config.topK)
        params.put("top_p", config.topP)
        params.put("max_tokens", config.maxTokens)
        params.put("timeout_init_sec", config.timeoutInitSec)
        params.put("timeout_short_sec", config.timeoutShortTestSec)
        params.put("timeout_normal_sec", config.timeoutNormalInferenceSec)
        params.put("timeout_stress_sec", config.timeoutStressTestSec)
        root.put("inferenceParameters", params)

        val memory = JSONObject()
        memory.put("javaHeapUsedMb", telemetry.javaHeapUsedMb)
        memory.put("javaHeapMaxMb", telemetry.javaHeapMaxMb)
        memory.put("nativeHeapAllocatedMb", telemetry.nativeHeapAllocatedMb)
        memory.put("nativeHeapScope", "Android native allocator heap; mmap-gemappte GGUF-Modellgewichte sind darin nicht zwingend vollständig enthalten.")
        memory.put("systemAvailableRamMb", telemetry.systemAvailableRamMb)
        memory.put("systemTotalRamMb", telemetry.systemTotalRamMb)
        memory.put("appCpuUsagePercent", telemetry.appCpuUsagePercent ?: JSONObject.NULL)
        memory.put("appCpuUsageScope", "Process CPU time normalized by available logical CPU count; not whole-device CPU usage.")
        root.put("systemTelemetry", memory)

        val runsArray = JSONArray()
        for (r in runs) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("profileSetupDurationMs", r.profileSetupDurationMs)
            obj.put("profileSetupMode", r.profileSetupMode)
            obj.put("processPssBytes", r.processPssBytes)
            obj.put("postPrefillToFirstTokenMs", maxOf(0L, r.firstTokenDurationMs - r.promptEvalDurationMs - r.promptTokenizeDurationMs))
            obj.put("nCtx", r.nCtx)
            obj.put("nBatch", r.nBatch)
            obj.put("nUbatch", r.nUbatch)
            obj.put("nThreads", r.nThreads)
            obj.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date(r.timestamp)))
            obj.put("category", r.testCategory)
            obj.put("title", r.testTitle)
            obj.put("promptLength", r.promptText.length)
            obj.put("promptText", if (r.testCategory == "CHAT") "[CHAT_PROMPT_REDACTED]" else r.promptText.take(2000))
            obj.put("responseText", if (r.testCategory == "CHAT") "[CHAT_RESPONSE_REDACTED]" else r.responseText.take(4000))
            obj.put("modelName", r.modelName)
            obj.put("javaHeapBytes", r.javaHeapBytes)
            obj.put("nativeHeapBytes", r.nativeHeapBytes)
            obj.put("promptTokens", r.promptTokens)
            obj.put("generatedTokens", r.generatedTokens)
            obj.put("promptTokenizeDurationMs", r.promptTokenizeDurationMs)
            obj.put("firstTokenDurationMs", r.firstTokenDurationMs)
            obj.put("promptDecodeCalls", r.promptDecodeCalls)
            obj.put("generationDecodeCalls", r.generationDecodeCalls)
            obj.put("promptEvalDurationMs", r.promptEvalDurationMs)
            obj.put("promptTokensPerSecond", if (r.promptEvalDurationMs > 0L) {
                r.promptTokens * 1000.0 / r.promptEvalDurationMs.toDouble()
            } else JSONObject.NULL)
            obj.put("generationDurationMs", r.generationDurationMs)
            obj.put("totalDurationMs", r.totalDurationMs)
            obj.put("tokensPerSecond", r.tokensPerSecond)
            obj.put("finishReason", r.finishReason)
            obj.put("isSuccess", r.isSuccess)
            obj.put("errorMessage", r.errorMessage ?: JSONObject.NULL)
            obj.put("nThreadsBatch", r.nThreadsBatch)
            obj.put("backendPreference", r.backendPreference)
            obj.put("activeBackend", r.activeBackend)
            obj.put("temperature", r.temperature)
            obj.put("topK", r.topK)
            obj.put("topP", r.topP)
            obj.put("maxTokens", r.maxTokens)
            obj.put("appCpuUsagePercent", r.appCpuUsagePercent ?: JSONObject.NULL)
            runsArray.put(obj)
        }
        root.put("testResults", runsArray)

        return root.toString(2)
    }

    fun generateTxt(
        context: Context,
        runs: List<TestRunEntity>,
        config: InferenceConfig,
        telemetry: SystemTelemetry,
        engineVersion: String,
        modelName: String,
        modelSizeFormatted: String,
        modelInfo: GgufInfo? = null,
        modelStatus: String = "UNKNOWN",
        backendInfo: List<NativeBackendInfo> = emptyList(),
        activeBackendName: String = "Nicht verfügbar",
        modelLoadLog: String = "",
        workflowBackendReport: String = ""
    ): String {
        val sb = StringBuilder()
        val nowStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

        sb.appendLine("================================================================================")
        sb.appendLine("        LLM LAB - MOBILE INFERENCE TESTBENCH : DIAGNOSEBERICHT")
        sb.appendLine("================================================================================")
        sb.appendLine("Erstellungszeitpunkt : $nowStr")
        val installedPackageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        sb.appendLine("Paket-ID             : ${context.packageName}")
        sb.appendLine("Installierte Version : ${installedPackageInfo?.versionName ?: "nicht verfügbar"} (Code: ${installedPackageInfo?.longVersionCode ?: "nicht verfügbar"})")
        sb.appendLine("BuildConfig-Version  : ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Build-Commit         : ${BuildConfig.GIT_COMMIT}")
        sb.appendLine("Modellstatus         : $modelStatus")
        sb.appendLine("Backend-Auswahl      : $activeBackendName")
        sb.appendLine("GPU-Offload-Nachweis : siehe nativen Modelllade-Log; Backendname allein beweist keinen Layer-Offload.")
        sb.appendLine("Backend-Registry     : ${backendInfo.joinToString { "${it.registryName}=${if (it.available) "available" else "unavailable"}" }.ifBlank { "nicht verfügbar" }}")
        sb.appendLine("Android-Version      : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Gerät / Hardware     : ${Build.MANUFACTURER} ${Build.MODEL} (${telemetry.cpuArch}, ${telemetry.cpuCores} Kerne)")
        sb.appendLine("Thermal Status       : ${telemetry.thermalStatus}")
        sb.appendLine("Engine / Commit      : $engineVersion")
        sb.appendLine("GGUF Modell          : $modelName ($modelSizeFormatted)")
        sb.appendLine("GGUF-Version         : ${modelInfo?.version ?: "nicht verfügbar"}")
        sb.appendLine("Tensoren             : ${modelInfo?.tensorCount ?: "nicht verfügbar"}")
        sb.appendLine("Metadaten-Einträge   : ${modelInfo?.kvCount ?: "nicht verfügbar"}")
        sb.appendLine("Architektur          : ${modelInfo?.architecture ?: "nicht verfügbar"}")
        sb.appendLine("Quantisierung        : ${modelInfo?.fileTypeDescription ?: "nicht verfügbar"}")
        sb.appendLine("Context (Modelldaten): ${modelInfo?.contextLength ?: "nicht verfügbar"}")
        sb.appendLine("Native-Heap-Hinweis  : mmap-gemappte GGUF-Gewichte sind darin nicht zwingend enthalten.")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("INFERENZ-PARAMETER:")
        sb.appendLine("  Profil             : ${config.selectedProfile.label}")
        sb.appendLine("  Context (n_ctx)    : ${config.nCtx}")
        sb.appendLine("  Batch (n_batch)    : ${config.nBatch}")
        sb.appendLine("  Micro-Batch (n_ub) : ${config.nUbatch}")
        sb.appendLine("  Threads Generation : ${config.nThreads}")
        sb.appendLine("  Threads Prefill    : ${config.nThreadsBatch}")
        sb.appendLine("  Backend Preference : ${config.backendPreference.name}")
        sb.appendLine("  Temperatur         : ${config.temperature}")
        sb.appendLine("  Top-K              : ${config.topK}")
        sb.appendLine("  Top-P              : ${config.topP}")
        sb.appendLine("  Max Tokens         : ${config.maxTokens}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("SPEICHER-TELEMETRIE:")
        sb.appendLine("  Java Heap Belegt   : ${"%.1f".format(telemetry.javaHeapUsedMb)} MB / Max: ${"%.1f".format(telemetry.javaHeapMaxMb)} MB")
        sb.appendLine("  Native Heap Belegt : ${"%.1f".format(telemetry.nativeHeapAllocatedMb)} MB")
        sb.appendLine("  System-RAM         : Verfügbar ${"%.0f".format(telemetry.systemAvailableRamMb)} MB von ${"%.0f".format(telemetry.systemTotalRamMb)} MB")
        sb.appendLine("  App-CPU-Anteil     : ${telemetry.appCpuUsagePercent?.let { "${"%.1f".format(it)}% (prozessbezogen)" } ?: "nicht verfügbar"}")
        sb.appendLine("================================================================================")
        sb.appendLine("DOKUMENTIERTE TESTLÄUFE (${runs.size} Durchläufe):")
        sb.appendLine("--------------------------------------------------------------------------------")

        if (runs.isEmpty()) {
            sb.appendLine("  (Keine Testläufe in der Datenbank vorhanden)")
        } else {
            runs.forEachIndexed { index, r ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(r.timestamp))
                sb.appendLine("Test #${index + 1} [$timeStr] : ${r.testTitle} [${r.testCategory}]")
                sb.appendLine("  Status            : ${if (r.isSuccess) "ERFOLGREICH" else "FEHLGESCHLAGEN"} (Grund: ${r.finishReason})")
                sb.appendLine("  Prompt Tokens     : ${r.promptTokens} | Generierte Tokens: ${r.generatedTokens}")
                sb.appendLine("  Tokenisierung     : ${r.promptTokenizeDurationMs} ms")
                sb.appendLine("  First Token       : ${r.firstTokenDurationMs} ms")
                sb.appendLine("  Nach Prefill bis 1. Token: ${maxOf(0L, r.firstTokenDurationMs - r.promptEvalDurationMs - r.promptTokenizeDurationMs)} ms (inkl. Formatter-/Sampler-/Backend-Sync)")
                sb.appendLine("  Prompt Decode     : ${r.promptDecodeCalls} Aufrufe")
                sb.appendLine("  Generation Decode : ${r.generationDecodeCalls} Aufrufe")
                sb.appendLine("  Threads Batch     : ${r.nThreadsBatch}")
                sb.appendLine("  Native-Kontext    : n_ctx=${r.nCtx}, n_batch=${r.nBatch}, n_ubatch=${r.nUbatch}")
                sb.appendLine("  Profilvorbereitung: ${r.profileSetupMode} · ${r.profileSetupDurationMs} ms")
                sb.appendLine("  Prozess-PSS       : ${r.processPssBytes} Bytes (inkl. anteilig gemappter Modellseiten)")
                sb.appendLine("  CPU-Threads       : Generation=${r.nThreads}, Prefill=${r.nThreadsBatch}")
                sb.appendLine("  Backend           : ${r.activeBackend} (Policy: ${r.backendPreference})")
                sb.appendLine("  Sampling          : temp=${r.temperature}, topK=${r.topK}, topP=${r.topP}, maxTokens=${r.maxTokens}")
                sb.appendLine("  App-CPU-Anteil    : ${r.appCpuUsagePercent?.let { "${"%.1f".format(it)}% (prozessbezogen)" } ?: "nicht verfügbar"}")
                sb.appendLine("  Prompt-Eval Dauer : ${r.promptEvalDurationMs} ms")
                val promptTps = if (r.promptEvalDurationMs > 0L) {
                    r.promptTokens * 1000.0 / r.promptEvalDurationMs.toDouble()
                } else 0.0
                sb.appendLine("  Prefill-Durchsatz : ${"%.2f".format(promptTps)} Tokens/s")
                sb.appendLine("  Generierungsdauer : ${r.generationDurationMs} ms")
                sb.appendLine("  Durchsatz         : ${"%.2f".format(r.tokensPerSecond)} Tokens/s")
                sb.appendLine("  Gesamtdauer       : ${r.totalDurationMs} ms")
                if (r.errorMessage != null) {
                    sb.appendLine("  Fehler            : ${r.errorMessage}")
                }
                if (r.testCategory != "CHAT") sb.appendLine("  Test-Prompt       : ${r.promptText.take(1000).replace("\n", " ")}")
                sb.appendLine("  Modell            : ${r.modelName}")
                sb.appendLine("  Java-/Native-Heap : ${r.javaHeapBytes} / ${r.nativeHeapBytes} Bytes")
                sb.appendLine("  Antwort           : ${if (r.testCategory == "CHAT") "[CHAT_RESPONSE_REDACTED]" else r.responseText.take(4000).replace("\n", " ")}")
                sb.appendLine("--------------------------------------------------------------------------------")
            }
        }
        sb.appendLine("================================================================================")
        if (workflowBackendReport.isNotBlank()) {
            sb.appendLine("AUTOMATISCHE PROFILVERGLEICHE – GPU-OFFLOAD-NACHWEIS:")
            sb.appendLine(workflowBackendReport.take(16384))
            sb.appendLine("--------------------------------------------------------------------------------")
        }
        if (modelLoadLog.isNotBlank()) {
            sb.appendLine("NATIVER MODELL-LADELOG (llama.cpp):")
            sb.appendLine(modelLoadLog.take(65536))
            sb.appendLine("--------------------------------------------------------------------------------")
        }
        sb.appendLine("ENDE DES DIAGNOSEBERICHTS")
        return sb.toString()
    }

    internal fun sanitizeExportFileName(fileName: String): String {
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        require(safeName.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Ungültiger Exportdateiname" }
        return safeName
    }

    /**
     * Saves directly to public Downloads through MediaStore.
     * No FileProvider, chooser or external activity is needed.
     * minSdk 29 guarantees scoped-storage support.
     */
    fun saveExportToDownloads(context: Context, content: String, fileName: String, mimeType: String): String {
        val safeName = sanitizeExportFileName(fileName)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LLM Lab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android konnte keinen Eintrag im Downloads-Ordner anlegen.")

        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) throw IOException("Der Diagnosebericht ist leer.")
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Android konnte den Diagnosebericht nicht zum Schreiben öffnen.")
            output.use { stream ->
                stream.write(bytes)
                stream.flush()
            }

            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw IOException("Android konnte den Downloads-Eintrag nicht abschließen.")
            }
            return "Downloads/LLM Lab/$safeName"
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Durable emergency copy if public Downloads cannot be written. */
    fun saveInternalFallback(context: Context, content: String, fileName: String): String {
        val safeName = sanitizeExportFileName(fileName)
        val directory = java.io.File(context.filesDir, "exports")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Interner Exportordner konnte nicht erstellt werden.")
        }
        val target = java.io.File(directory, safeName)
        val part = java.io.File(directory, "$safeName.part")
        java.io.FileOutputStream(part).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            part.delete()
            throw IOException("Vorhandene interne Exportdatei konnte nicht ersetzt werden.")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Interne Exportdatei konnte nicht finalisiert werden.")
        }
        return target.absolutePath
    }
}