package com.example.engine

data class GgufInfo(
    val isValid: Boolean,
    val magic: String,
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val architecture: String? = null,
    val modelName: String? = null,
    val fileTypeDescription: String? = null,
    val contextLength: Long? = null,
    val embeddingLength: Long? = null,
    val blockCount: Long? = null,
    val rawMetadata: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)
