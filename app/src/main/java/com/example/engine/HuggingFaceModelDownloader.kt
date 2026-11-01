package com.example.engine

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** HTTP status failure that retains the response code and requested URL. */
class HttpStatusException(
    val code: Int,
    val url: String
) : IOException("Download-Server antwortet mit HTTP " + code + " für " + url)

private class TransientDownloadIOException(cause: IOException) :
    IOException("Vorübergehender Netzwerkfehler beim Modell-Download.", cause)

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
        val partial = File(modelDirectory, model.filename + ".part")
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

        val resumeOffset = partial.takeIf { it.isFile }?.length() ?: 0L

        val freeBytes = StatFs(modelDirectory.absolutePath).availableBytes
        val remainingEstimate = (model.estimatedSizeBytes - resumeOffset).coerceAtLeast(0L)
        if (freeBytes < remainingEstimate + 64L * 1024L * 1024L) {
            throw IOException(
                "Zu wenig freier Speicher. Benötigt werden noch ungefähr " +
                    (remainingEstimate / (1024L * 1024L)) + " MB plus 64 MB Reserve."
            )
        }

        downloadWithRetry(
            url = model.downloadUrl,
            dest = partial,
            expectedSize = model.estimatedSizeBytes,
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            onProgress = onProgress
        )

        currentCoroutineContext().ensureActive()
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

    /**
     * Retries transient HTTP/network failures. Resume position is recalculated from the file
     * after every failed attempt, so successfully written bytes are retained.
     *
     * expectedSize is currently a catalog estimate. Strict byte-count validation therefore
     * uses a server-reported Content-Length / Content-Range when available.
     */
    private suspend fun downloadWithRetry(
        url: String,
        dest: File,
        expectedSize: Long,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        require(maxAttempts >= 1) { "maxAttempts muss mindestens 1 sein." }

        var attempt = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val resumeFrom = dest.takeIf { it.isFile }?.length() ?: 0L
            try {
                downloadOnce(url, dest, expectedSize, resumeFrom, onProgress)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (status: HttpStatusException) {
                currentCoroutineContext().ensureActive()
                if (!status.isRetryable() || attempt >= maxAttempts) throw status
            } catch (io: TransientDownloadIOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= maxAttempts) throw io
            }

            val multiplier = (1 shl min(attempt - 1, 3)).toLong()
            val exponentialDelay = min(INITIAL_BACKOFF_MS * multiplier, MAX_BACKOFF_MS)
            val jitter = ThreadLocalRandom.current().nextLong(0L, MAX_JITTER_MS + 1L)
            delay(min(exponentialDelay + jitter, MAX_BACKOFF_MS))
            attempt++
        }
    }

    /**
     * Performs one HTTP transfer attempt.
     *
     * A 200 response after a Range request means the server ignored Range; the destination is
     * truncated and the full response is written from byte zero. A 206 response is appended
     * only after its Content-Range start has been verified.
     */
    private suspend fun downloadOnce(
        url: String,
        dest: File,
        expectedSize: Long,
        resumeFrom: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        currentCoroutineContext().ensureActive()

        var connection: HttpURLConnection? = null
        val connectionRef = AtomicReference<HttpURLConnection?>()
        val completionHandle: DisposableHandle? =
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connectionRef.get()?.disconnect()
            }

        try {
            val parsedUrl = URL(url)
            val openedConnection = try {
                parsedUrl.openConnection() as HttpURLConnection
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw TransientDownloadIOException(error)
            }
            val activeConnection = openedConnection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=" + resumeFrom + "-")
            }
            connection = activeConnection
            connectionRef.set(activeConnection)
            currentCoroutineContext().ensureActive()

            val response = try {
                activeConnection.responseCode
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw TransientDownloadIOException(error)
            }
            if (response == HTTP_RANGE_NOT_SATISFIABLE) {
                // A 416 is a permanent client error under the downloader retry policy.
                // Discard the unusable offset so a later manual attempt starts from zero.
                if (dest.exists() && !dest.delete()) {
                    throw IOException("Ungültiger Teil-Download konnte nach HTTP 416 nicht zurückgesetzt werden.")
                }
                throw HttpStatusException(response, url)
            }

            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                throw HttpStatusException(response, url)
            }

            val contentRange = activeConnection.getHeaderField("Content-Range")
            val rangeMatch = CONTENT_RANGE_REGEX.matchEntire(contentRange.orEmpty())
            if (response == HttpURLConnection.HTTP_PARTIAL) {
                val actualStart = rangeMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                val actualEnd = rangeMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
                if (actualStart == null || actualStart != resumeFrom ||
                    actualEnd == null || actualEnd < actualStart) {
                    throw IOException(
                        "Der Server lieferte einen unerwarteten Content-Range-Bereich; der vorhandene Teil-Download bleibt erhalten."
                    )
                }
            }

            // HTTP 200 ignores Range: restart from byte zero instead of appending duplicate bytes.
            val append = response == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L
            val startBytes = if (append) resumeFrom else 0L
            val contentLength = activeConnection.getHeaderFieldLong("Content-Length", -1L)
            val rangeTotal = rangeMatch?.groupValues?.getOrNull(3)
                ?.takeUnless { it == "*" }
                ?.toLongOrNull()
            val authoritativeTotalBytes = when {
                rangeTotal != null && rangeTotal > 0L -> rangeTotal
                contentLength >= 0L -> startBytes + contentLength
                else -> null
            }
            val progressTotal = authoritativeTotalBytes ?: expectedSize
            onProgress(startBytes, progressTotal)

            val responseInput = try {
                activeConnection.inputStream
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw TransientDownloadIOException(error)
            }
            responseInput.use { rawInput ->
                BufferedInputStream(rawInput, BUFFER_SIZE).use { input ->
                    FileOutputStream(dest, append).use { rawOutput ->
                        BufferedOutputStream(rawOutput, BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var downloaded = startBytes
                            var lastReported = startBytes
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = try {
                                    input.read(buffer)
                                } catch (error: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    throw TransientDownloadIOException(error)
                                }
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastReported >= PROGRESS_INTERVAL_BYTES ||
                                    (progressTotal > 0L && downloaded == progressTotal)) {
                                    onProgress(downloaded, progressTotal)
                                    lastReported = downloaded
                                }
                            }
                            output.flush()
                            rawOutput.fd.sync()
                        }
                    }
                }
            }

            // The catalog size is an estimate, not an exact value. When the server declares
            // its real total, validate the resulting file against that exact byte count.
            if (authoritativeTotalBytes != null && dest.length() != authoritativeTotalBytes) {
                val sizeError = IOException(
                    "Download unvollständig: " + dest.length() + " Bytes von " +
                        authoritativeTotalBytes + " Bytes. Der nächste Versuch setzt den Download fort."
                )
                throw TransientDownloadIOException(sizeError)
            }
        } finally {
            completionHandle?.dispose()
            connectionRef.set(null)
            connection?.disconnect()
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun HttpStatusException.isRetryable(): Boolean =
        code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR..599

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 4
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_JITTER_MS = 500L
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "LLM-Lab-Android/1.4"
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L

        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

    }
}
