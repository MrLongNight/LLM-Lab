package com.example.engine

import java.util.Locale

data class HuggingFaceModel(
    val id: String,
    val title: String,
    val parameters: String,
    val quantization: String,
    val filename: String,
    val downloadUrl: String,
    val sha256: String,
    val estimatedSizeBytes: Long
) {
    val sizeLabel: String
        get() = String.format(Locale.US, "%.2f GB", estimatedSizeBytes / 1_000_000_000.0)
}

object HuggingFaceModelCatalog {
    /** Single reference model for automated Quick/Full acceptance runs. */
    const val REFERENCE_MODEL_ID = "qwen25_15b_q4_k_m"

    // Official Qwen GGUF repositories and SHA-256 values published by Hugging Face Xet pointers.
    val models: List<HuggingFaceModel> = listOf(
        HuggingFaceModel(
            id = "qwen25_05b_q4_0",
            title = "Qwen2.5 0.5B Instruct",
            parameters = "0.5B",
            quantization = "Q4_0",
            filename = "qwen2.5-0.5b-instruct-q4_0.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
            sha256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed",
            estimatedSizeBytes = 429_000_000L
        ),
        HuggingFaceModel(
            id = "qwen25_05b_q4_k_m",
            title = "Qwen2.5 0.5B Instruct",
            parameters = "0.5B",
            quantization = "Q4_K_M",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            estimatedSizeBytes = 491_000_000L
        ),
        HuggingFaceModel(
            id = "qwen25_15b_q4_0",
            title = "Qwen2.5 1.5B Instruct",
            parameters = "1.5B",
            quantization = "Q4_0",
            filename = "qwen2.5-1.5b-instruct-q4_0.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf",
            sha256 = "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb",
            estimatedSizeBytes = 1_070_000_000L
        ),
        HuggingFaceModel(
            id = "qwen25_15b_q4_k_m",
            title = "Qwen2.5 1.5B Instruct",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            estimatedSizeBytes = 1_120_000_000L
        )
    )

    fun find(id: String): HuggingFaceModel? = models.firstOrNull { it.id == id }
}
