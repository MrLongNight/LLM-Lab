package com.example.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GgufMetadataParser {

    private const val GGUF_MAGIC = "GGUF"

    fun parse(file: File): GgufInfo {
        if (!file.exists()) {
            return GgufInfo(
                isValid = false,
                magic = "",
                version = 0,
                tensorCount = 0,
                kvCount = 0,
                errorMessage = "Datei existiert nicht: ${file.absolutePath}"
            )
        }
        return file.inputStream().use { parse(it, file.length()) }
    }

    fun parse(context: Context, uri: Uri, fileSize: Long): GgufInfo {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return GgufInfo(
                    isValid = false,
                    magic = "",
                    version = 0,
                    tensorCount = 0,
                    kvCount = 0,
                    errorMessage = "InputStream konnte für URI nicht geöffnet werden: $uri"
                )
            inputStream.use { parse(it, fileSize) }
        } catch (e: Exception) {
            GgufInfo(
                isValid = false,
                magic = "",
                version = 0,
                tensorCount = 0,
                kvCount = 0,
                errorMessage = "Fehler beim Lesen der GGUF-Datei: ${e.message}"
            )
        }
    }

    fun parse(inputStream: InputStream, totalSize: Long): GgufInfo {
        try {
            val headerBytes = ByteArray(1024 * 64) // 64 KB Header-Puffer
            var bytesRead = 0
            while (bytesRead < headerBytes.size) {
                val r = inputStream.read(headerBytes, bytesRead, headerBytes.size - bytesRead)
                if (r <= 0) break
                bytesRead += r
            }

            if (bytesRead < 24) {
                return GgufInfo(
                    isValid = false,
                    magic = "",
                    version = 0,
                    tensorCount = 0,
                    kvCount = 0,
                    errorMessage = "Datei ist zu kurz für einen GGUF-Header ($bytesRead Bytes gelesen)."
                )
            }

            val buffer = ByteBuffer.wrap(headerBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)

            // Magic check (4 bytes)
            val magicBytes = ByteArray(4)
            buffer.get(magicBytes)
            val magicStr = String(magicBytes, Charsets.US_ASCII)

            if (magicStr != GGUF_MAGIC) {
                val hexString = magicBytes.joinToString("") { "%02X".format(it) }
                return GgufInfo(
                    isValid = false,
                    magic = magicStr,
                    version = 0,
                    tensorCount = 0,
                    kvCount = 0,
                    errorMessage = "Ungültige GGUF-Magicsignatur: '$magicStr' (0x$hexString). Erwartet: 'GGUF' (0x46554747)."
                )
            }

            val version = buffer.int
            if (version !in 2..3) {
                return GgufInfo(
                    isValid = false,
                    magic = magicStr,
                    version = version,
                    tensorCount = 0,
                    kvCount = 0,
                    errorMessage = "Nicht unterstützte GGUF-Version: $version. Unterstützt werden Version 2 und 3."
                )
            }
            val tensorCount = buffer.long
            val kvCount = buffer.long

            val metadata = mutableMapOf<String, String>()

            // Best-effort parsing of KV-pairs from the buffer
            try {
                for (i in 0 until minOf(kvCount, 60L)) {
                    if (buffer.remaining() < 12) break
                    val key = readGgufString(buffer) ?: break
                    val valueType = buffer.int
                    val value = readGgufValue(buffer, valueType)
                    if (value != null) {
                        metadata[key] = value
                    }
                }
            } catch (_: Exception) {
                // Header-Puffer begrenzt, Teilmetadaten genügen für Identifikation
            }

            val arch = metadata["general.architecture"]
            val modelName = metadata["general.name"]
                ?: metadata["${arch}.model_name"]
                ?: (arch?.let { "GGUF Model ($it)" } ?: "GGUF Model")

            val fileType = metadata["general.file_type"]?.toIntOrNull()
            val fileTypeDesc = describeFileType(fileType)

            val contextLength = arch?.let { metadata["$it.context_length"]?.toLongOrNull() }
            val embeddingLength = arch?.let { metadata["$it.embedding_length"]?.toLongOrNull() }
            val blockCount = arch?.let { metadata["$it.block_count"]?.toLongOrNull() }

            return GgufInfo(
                isValid = true,
                magic = magicStr,
                version = version,
                tensorCount = tensorCount,
                kvCount = kvCount,
                architecture = arch,
                modelName = modelName,
                fileTypeDescription = fileTypeDesc,
                contextLength = contextLength,
                embeddingLength = embeddingLength,
                blockCount = blockCount,
                rawMetadata = metadata
            )
        } catch (e: Exception) {
            return GgufInfo(
                isValid = false,
                magic = "",
                version = 0,
                tensorCount = 0,
                kvCount = 0,
                errorMessage = "GGUF Parser Ausnahme: ${e.message}"
            )
        }
    }

    private fun readGgufString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 8) return null
        val len = buffer.long.toInt()
        if (len < 0 || len > buffer.remaining() || len > 2048) return null
        val strBytes = ByteArray(len)
        buffer.get(strBytes)
        return String(strBytes, Charsets.UTF_8)
    }

    private fun readGgufValue(buffer: ByteBuffer, type: Int): String? {
        return try {
            when (type) {
                0 -> buffer.get().toString() // UINT8
                1 -> buffer.get().toString() // INT8
                2 -> buffer.short.toString() // UINT16
                3 -> buffer.short.toString() // INT16
                4 -> buffer.int.toLong().let { if (it < 0) it + 0x100000000L else it }.toString() // UINT32
                5 -> buffer.int.toString() // INT32
                6 -> buffer.float.toString() // FLOAT32
                7 -> (buffer.get().toInt() != 0).toString() // BOOL
                8 -> readGgufString(buffer) // STRING
                9 -> { // ARRAY
                    if (buffer.remaining() < 12) return null
                    val itemType = buffer.int
                    val count = buffer.long.toInt()
                    if (count < 0 || count > 100) return "Array(type=$itemType, count=$count)"
                    val items = mutableListOf<String>()
                    for (i in 0 until minOf(count, 5)) {
                        readGgufValue(buffer, itemType)?.let { items.add(it) }
                    }
                    if (count > 5) items.add("... +${count - 5}")
                    "[${items.joinToString(", ")}]"
                }
                10 -> buffer.long.toString() // UINT64
                11 -> buffer.long.toString() // INT64
                12 -> buffer.double.toString() // FLOAT64
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun describeFileType(fileType: Int?): String {
        return when (fileType) {
            0 -> "ALL_F32"
            1 -> "MOSTLY_F16"
            2 -> "MOSTLY_Q4_0"
            3 -> "MOSTLY_Q4_1"
            7 -> "MOSTLY_Q8_0"
            8 -> "MOSTLY_Q5_0"
            9 -> "MOSTLY_Q5_1"
            10 -> "MOSTLY_Q2_K"
            11 -> "MOSTLY_Q3_K_S"
            12 -> "MOSTLY_Q3_K_M"
            13 -> "MOSTLY_Q3_K_L"
            14 -> "MOSTLY_Q4_K_S"
            15 -> "MOSTLY_Q4_K_M (Q4_K_M empfohlen)"
            16 -> "MOSTLY_Q5_K_S"
            17 -> "MOSTLY_Q5_K_M"
            18 -> "MOSTLY_Q6_K"
            else -> if (fileType != null) "Typ ID $fileType" else "Unbekannt"
        }
    }
}
