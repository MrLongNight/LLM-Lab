package com.example.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import android.os.StatFs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


/**
 * Robolectric does not model the host filesystem's free space consistently.
 * Keep downloader tests focused on HTTP behavior while preserving the production
 * low-storage guard unchanged.
 */
@Implements(StatFs::class)
class DownloaderTestStatFsShadow {
    @Implementation
    fun getAvailableBytes(): Long = 1024L * 1024L * 1024L
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DownloaderTestStatFsShadow::class])
class HuggingFaceModelDownloaderTest {

    @Test
    fun findVerifiedLocalModelAcceptsMatchingShaAndValidGguf() = runBlocking {
        val modelBytes = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(1L)
            .putLong(0L)
            .array()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = HuggingFaceModel(
            id = "verified-local-test",
            title = "Verified local test",
            parameters = "test",
            quantization = "test",
            filename = "verified-local-test.gguf",
            downloadUrl = "http://127.0.0.1/unrequested",
            sha256 = sha256(modelBytes),
            estimatedSizeBytes = modelBytes.size.toLong()
        )
        val directory = java.io.File(context.filesDir, "models")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val target = java.io.File(directory, model.filename)
        try {
            target.writeBytes(modelBytes)
            val verified = HuggingFaceModelDownloader(context).findVerifiedLocalModel(model)
            assertEquals(target.canonicalFile, verified?.canonicalFile)
        } finally {
            target.delete()
        }
    }

    @Test
    fun findVerifiedLocalModelRejectsValidGgufWithWrongSha256() = runBlocking {
        val modelBytes = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(1L)
            .putLong(0L)
            .array()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = HuggingFaceModel(
            id = "wrong-sha-local-test",
            title = "Wrong SHA local test",
            parameters = "test",
            quantization = "test",
            filename = "wrong-sha-local-test.gguf",
            downloadUrl = "http://127.0.0.1/unrequested",
            sha256 = "0".repeat(64),
            estimatedSizeBytes = modelBytes.size.toLong()
        )
        val directory = java.io.File(context.filesDir, "models")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val target = java.io.File(directory, model.filename)
        try {
            target.writeBytes(modelBytes)
            val verified = HuggingFaceModelDownloader(context).findVerifiedLocalModel(model)
            assertEquals(null, verified)
        } finally {
            target.delete()
        }
    }

    @Test
    fun legacyLocationCanBeVerifiedOnlyWithMatchingShaAndValidGguf() = runBlocking {
        val modelBytes = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(1L)
            .putLong(0L)
            .array()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = HuggingFaceModel(
            id = "legacy-path-test",
            title = "Legacy path test",
            parameters = "test",
            quantization = "test",
            filename = "legacy-path-test.gguf",
            downloadUrl = "http://127.0.0.1/unrequested",
            sha256 = sha256(modelBytes),
            estimatedSizeBytes = modelBytes.size.toLong()
        )
        val legacyDirectory = java.io.File(context.filesDir, "legacy-model-location")
        assertTrue(legacyDirectory.mkdirs() || legacyDirectory.isDirectory)
        val target = java.io.File(legacyDirectory, model.filename)
        try {
            target.writeBytes(modelBytes)
            assertTrue(HuggingFaceModelDownloader(context).isVerifiedLocalModelFile(target, model))
            val wrongHash = model.copy(sha256 = "0".repeat(64))
            assertFalse(HuggingFaceModelDownloader(context).isVerifiedLocalModelFile(target, wrongHash))
        } finally {
            target.delete()
        }
    }

    @Test
    fun retriesInterruptedTransferAndResumesWithRequestedByteRange() = runBlocking {
        val modelBytes = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(1L)
            .putLong(0L)
            .array()
        val server = InterruptOnceRangeServer(modelBytes)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = HuggingFaceModel(
            id = "range-resume-test",
            title = "Range resume test",
            parameters = "test",
            quantization = "test",
            filename = "range-resume-test.gguf",
            downloadUrl = server.url,
            sha256 = sha256(modelBytes),
            estimatedSizeBytes = modelBytes.size.toLong()
        )
        val modelDirectory = java.io.File(context.filesDir, "models")
        java.io.File(modelDirectory, model.filename).delete()
        java.io.File(modelDirectory, model.filename + ".part").delete()

        try {
            val downloaded = HuggingFaceModelDownloader(context).download(model) { _, _ -> }

            assertTrue(downloaded.isFile)
            assertEquals(modelBytes.size.toLong(), downloaded.length())
            assertEquals(model.sha256, sha256(downloaded.readBytes()))
            assertEquals(2, server.requests.size)
            assertTrue(server.requests[0].none { it.startsWith("range:") })
            assertTrue(server.requests[1].contains("range: bytes=12-"))
            assertEquals(null, server.failure.get())
        } finally {
            server.close()
            java.io.File(modelDirectory, model.filename).delete()
            java.io.File(modelDirectory, model.filename + ".part").delete()
        }
    }


    @Test
    fun permanentClientErrorsAreNotRetried() = runBlocking {
        val modelBytes = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray(Charsets.US_ASCII))
            .putInt(3)
            .putLong(1L)
            .putLong(0L)
            .array()
        val server = SingleStatusServer(404)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = HuggingFaceModel(
            id = "permanent-status-test",
            title = "HTTP status test",
            parameters = "test",
            quantization = "test",
            filename = "permanent-status-test.gguf",
            downloadUrl = server.url,
            sha256 = sha256(modelBytes),
            estimatedSizeBytes = modelBytes.size.toLong()
        )
        val modelDirectory = java.io.File(context.filesDir, "models")
        java.io.File(modelDirectory, model.filename).delete()
        java.io.File(modelDirectory, model.filename + ".part").delete()

        try {
            var statusError: HttpStatusException? = null
            try {
                HuggingFaceModelDownloader(context).download(model) { _, _ -> }
            } catch (error: HttpStatusException) {
                statusError = error
            }

            assertEquals(404, statusError?.code)
            assertEquals(1, server.requestCount.get())
            assertEquals(null, server.failure.get())
        } finally {
            server.close()
            java.io.File(modelDirectory, model.filename).delete()
            java.io.File(modelDirectory, model.filename + ".part").delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * First response advertises the full length but closes after half of the body.
     * The second response succeeds only when the client resumes with Range: bytes=12-.
     */

    private class SingleStatusServer(
        private val statusCode: Int
    ) : AutoCloseable {
        private val listener = ServerSocket(0)
        val requestCount = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>()
        val url: String = "http://127.0.0.1:" + listener.localPort + "/status"
        private val worker = Thread {
            try {
                listener.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    requestCount.incrementAndGet()
                    val reason = if (statusCode == 404) "Not Found" else "Error"
                    val response = ("HTTP/1.1 " + statusCode + " " + reason + "\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                    client.getOutputStream().write(response)
                    client.getOutputStream().flush()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            name = "hf-status-test-server"
            isDaemon = true
            start()
        }

        override fun close() {
            listener.close()
            worker.join(5_000)
        }
    }

    private class InterruptOnceRangeServer(
        private val body: ByteArray
    ) : AutoCloseable {
        private val socket = ServerSocket(0)
        val requests = CopyOnWriteArrayList<List<String>>()
        val failure = AtomicReference<Throwable?>()
        val url: String = "http://127.0.0.1:" + socket.localPort + "/model.gguf"
        private val worker = Thread {
            try {
                repeat(2) { requestIndex ->
                    socket.accept().use { client ->
                        client.soTimeout = 5_000
                        val headers = readHeaders(client)
                        requests += headers

                        if (requestIndex == 0) {
                            client.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Length: " + body.size + "\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                                )
                                write(body, 0, body.size / 2)
                                flush()
                            }
                            client.shutdownOutput()
                        } else {
                            val range = headers.firstOrNull { it.startsWith("range:") }
                            check(range == "range: bytes=" + (body.size / 2) + "-") {
                                "Expected Range request at " + (body.size / 2) + ", got " + range
                            }
                            val start = body.size / 2
                            client.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 206 Partial Content\r\n" +
                                        "Content-Length: " + (body.size - start) + "\r\n" +
                                        "Content-Range: bytes " + start + "-" + body.lastIndex + "/" + body.size + "\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                                )
                                write(body, start, body.size - start)
                                flush()
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            name = "hf-range-resume-test-server"
            isDaemon = true
            start()
        }

        private fun readHeaders(client: Socket): List<String> {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val lines = mutableListOf<String>()
            val requestLine = reader.readLine() ?: error("Missing request line")
            lines += requestLine.lowercase()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                lines += line.lowercase()
            }
            return lines
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }
    }
}
