package com.example.engine

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class HuggingFaceModelDownloader(context: Context) {
    private val modelDirectory = File(context.filesDir, "models")

    suspend fun download(
        model: HuggingFaceModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("Der interne Modellordner konnte nicht angelegt werden.")
        }

        val target = File(modelDirectory, model.filename)
        val partial = File(modelDirectory, "${model.filename}.part")
        if (target.isFile && target.length() > 0L) {
            val existingHash = sha256(target)
            if (existingHash.equals(model.sha256, ignoreCase = true) &&
                GgufMetadataParser.parse(target).let { it.isValid && it.tensorCount > 0L }) {
                onProgress(target.length(), target.length())
                return@withContext target
            }
            if (!target.delete()) {
                throw IOException("Eine vorhandene, aber ungültige Modell-Datei konnte nicht ersetzt werden.")
            }
        }

        var offset = partial.takeIf { it.isFile }?.length() ?: 0L
        if (offset > model.estimatedSizeBytes * 1.25) {
            partial.delete()
            offset = 0L
        }
        val freeBytes = StatFs(modelDirectory.absolutePath).availableBytes
        val remainingEstimate = (model.estimatedSizeBytes - offset).coerceAtLeast(0L)
        if (freeBytes < remainingEstimate + 64L * 1024L * 1024L) {
            throw IOException(
                "Zu wenig freier Speicher. Benötigt werden noch ungefähr ${remainingEstimate / (1024L * 1024L)} MB plus 64 MB Reserve."
            )
        }

        var connection: HttpURLConnection? = null
        val currentJob = currentCoroutineContext()[Job]
        currentJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection?.disconnect()
        }

        try {
            connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LLM-Lab-Android/1.4")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }

            val response = connection.responseCode
            if (response == 416 && partial.isFile) {
                val partialInfo = GgufMetadataParser.parse(partial)
                val partialHash = if (partialInfo.isValid && partialInfo.tensorCount > 0L) sha256(partial) else ""
                if (partialHash.equals(model.sha256, ignoreCase = true)) {
                    if (target.exists() && !target.delete()) {
                        throw IOException("Vorhandene Modell-Datei konnte nicht ersetzt werden.")
                    }
                    if (!partial.renameTo(target)) {
                        throw IOException("Abgeschlossener Teil-Download konnte nicht finalisiert werden.")
                    }
                    onProgress(target.length(), target.length())
                    return@withContext target
                }
                partial.delete()
                throw IOException("Der gespeicherte Teil-Download war unvollständig oder beschädigt und wurde verworfen. Bitte erneut starten.")
            }
            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Hugging Face antwortet mit HTTP $response. Bitte Netzwerkverbindung prüfen.")
            }

            val requestedOffset = offset
            val append = response == HttpURLConnection.HTTP_PARTIAL && requestedOffset > 0L
            val contentRange = connection.getHeaderField("Content-Range")
            val rangeMatch = contentRange?.let {
                Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE).matchEntire(it.trim())
            }
            if (response == HttpURLConnection.HTTP_PARTIAL) {
                val actualStart = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (actualStart == null || actualStart != requestedOffset) {
                    throw IOException("Der Hugging-Face-Server lieferte einen unerwarteten Range-Bereich; Teil-Download bleibt erhalten.")
                }
            }

            // Some mirrors ignore Range and return a full HTTP 200 response. In that case restart cleanly.
            val startBytes = if (append) requestedOffset else 0L
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val rangeTotal = rangeMatch?.groupValues?.getOrNull(3)?.takeUnless { it == "*" }?.toLongOrNull() ?: -1L
            val authoritativeTotalBytes = when {
                rangeTotal > 0L -> rangeTotal
                contentLength > 0L -> startBytes + contentLength
                else -> -1L
            }
            val totalBytes = authoritativeTotalBytes.takeIf { it > 0L } ?: model.estimatedSizeBytes

            onProgress(startBytes, totalBytes)
            connection.inputStream.use { rawInput ->
                BufferedInputStream(rawInput, 1024 * 1024).use { input ->
                    FileOutputStream(partial, append).use { rawOutput ->
                        BufferedOutputStream(rawOutput, 1024 * 1024).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var downloaded = startBytes
                            var lastReported = startBytes
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastReported >= 8L * 1024L * 1024L || downloaded == totalBytes) {
                                    onProgress(downloaded, totalBytes)
                                    lastReported = downloaded
                                }
                            }
                            output.flush()
                            rawOutput.fd.sync()
                            if (authoritativeTotalBytes > 0L && downloaded != authoritativeTotalBytes) {
                                throw IOException(
                                    "Download unvollständig: ${downloaded / (1024L * 1024L)} MB von ungefähr ${totalBytes / (1024L * 1024L)} MB. Erneuter Versuch setzt den Download fort."
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }

        currentCoroutineContext().ensureActive()
        if (partial.length() < model.estimatedSizeBytes * 0.85) {
            throw IOException("Die heruntergeladene Datei ist deutlich kleiner als erwartet. Der Teil-Download bleibt zum Fortsetzen erhalten.")
        }

        val gguf = GgufMetadataParser.parse(partial)
        if (!gguf.isValid || gguf.tensorCount <= 0L) {
            partial.delete()
            throw IOException("Der Download ist keine gültige GGUF-Modell-Datei. Die fehlerhafte Teil-Datei wurde entfernt.")
        }

        val actualHash = sha256(partial)
        if (!actualHash.equals(model.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException(
                "SHA-256-Prüfung fehlgeschlagen. Die Datei stimmt nicht mit dem offiziellen Hugging-Face-Artefakt überein und wurde verworfen."
            )
        }

        if (target.exists() && !target.delete()) {
            throw IOException("Alte Modell-Datei konnte vor dem Abschluss nicht entfernt werden.")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            if (!partial.delete()) {
                target.delete()
                throw IOException("Temporäre Download-Datei konnte nicht bereinigt werden.")
            }
        }
        onProgress(target.length(), target.length())
        target
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
