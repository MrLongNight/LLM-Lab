package com.example.engine

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.TestRunEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        modelStatus: String = "UNKNOWN"
    ): String {
        val root = JSONObject()
        val nowStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.GERMANY).format(Date())

        root.put("exportTimestamp", nowStr)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("modelStatus", modelStatus)
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
        params.put("temperature", config.temperature)
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
        root.put("systemTelemetry", memory)

        val runsArray = JSONArray()
        for (r in runs) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date(r.timestamp)))
            obj.put("category", r.testCategory)
            obj.put("title", r.testTitle)
            obj.put("promptLength", r.promptText.length)
            obj.put("responseSample", r.responseText.take(200))
            obj.put("promptTokens", r.promptTokens)
            obj.put("generatedTokens", r.generatedTokens)
            obj.put("promptTokenizeDurationMs", r.promptTokenizeDurationMs)
            obj.put("firstTokenDurationMs", r.firstTokenDurationMs)
            obj.put("promptDecodeCalls", r.promptDecodeCalls)
            obj.put("generationDecodeCalls", r.generationDecodeCalls)
            obj.put("promptEvalDurationMs", r.promptEvalDurationMs)
            obj.put("generationDurationMs", r.generationDurationMs)
            obj.put("totalDurationMs", r.totalDurationMs)
            obj.put("tokensPerSecond", r.tokensPerSecond)
            obj.put("finishReason", r.finishReason)
            obj.put("isSuccess", r.isSuccess)
            obj.put("errorMessage", r.errorMessage ?: JSONObject.NULL)
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
        modelStatus: String = "UNKNOWN"
    ): String {
        val sb = StringBuilder()
        val nowStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())

        sb.appendLine("================================================================================")
        sb.appendLine("        LLM LAB - MOBILE INFERENCE TESTBENCH : DIAGNOSEBERICHT")
        sb.appendLine("================================================================================")
        sb.appendLine("Erstellungszeitpunkt : $nowStr")
        sb.appendLine("App-Version          : ${BuildConfig.VERSION_NAME} (Mobile Inference Testbench)")
        sb.appendLine("Modellstatus         : $modelStatus")
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
        sb.appendLine("  Threads            : ${config.nThreads}")
        sb.appendLine("  Temperatur         : ${config.temperature}")
        sb.appendLine("  Max Tokens         : ${config.maxTokens}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("SPEICHER-TELEMETRIE:")
        sb.appendLine("  Java Heap Belegt   : ${"%.1f".format(telemetry.javaHeapUsedMb)} MB / Max: ${"%.1f".format(telemetry.javaHeapMaxMb)} MB")
        sb.appendLine("  Native Heap Belegt : ${"%.1f".format(telemetry.nativeHeapAllocatedMb)} MB")
        sb.appendLine("  System-RAM         : Verfügbar ${"%.0f".format(telemetry.systemAvailableRamMb)} MB von ${"%.0f".format(telemetry.systemTotalRamMb)} MB")
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
                sb.appendLine("  Prompt Decode     : ${r.promptDecodeCalls} Aufrufe")
                sb.appendLine("  Generation Decode : ${r.generationDecodeCalls} Aufrufe")
                sb.appendLine("  Prompt-Eval Dauer : ${r.promptEvalDurationMs} ms")
                sb.appendLine("  Generierungsdauer : ${r.generationDurationMs} ms")
                sb.appendLine("  Durchsatz         : ${"%.2f".format(r.tokensPerSecond)} Tokens/s")
                sb.appendLine("  Gesamtdauer       : ${r.totalDurationMs} ms")
                if (r.errorMessage != null) {
                    sb.appendLine("  Fehler            : ${r.errorMessage}")
                }
                sb.appendLine("  Antwort-Auszug    : ${r.responseText.take(120).replace("\n", " ")}")
                sb.appendLine("--------------------------------------------------------------------------------")
            }
        }
        sb.appendLine("================================================================================")
        sb.appendLine("ENDE DES DIAGNOSEBERICHTS")
        return sb.toString()
    }

    internal fun createChooserIntent(sendIntent: Intent, title: String): Intent =
        Intent.createChooser(sendIntent, title).apply {
            // The ViewModel uses Application context; NEW_TASK is required for startActivity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun shareExport(context: Context, content: String, fileName: String, mimeType: String) {
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw java.io.IOException("Exportverzeichnis konnte nicht erstellt werden: ${exportDir.absolutePath}")
        }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        require(safeName.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Ungültiger Exportdateiname" }
        val file = File(exportDir, safeName)
        file.writeText(content, Charsets.UTF_8)
        if (!file.isFile || file.length() == 0L) {
            throw java.io.IOException("Exportdatei wurde nicht korrekt geschrieben.")
        }

        exportDir.listFiles()
            ?.filter { it != file && System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L }
            ?.forEach { it.delete() }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, safeName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = createChooserIntent(intent, "Diagnosebericht exportieren").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
