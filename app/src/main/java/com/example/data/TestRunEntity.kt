package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "test_runs")
data class TestRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val testCategory: String, // CHAT, LAB_SHORT_OK, LAB_GERMAN_DMS, LAB_JSON, LAB_EXTRACTION, LAB_HALLUCINATION, LAB_LONG_CONTEXT, LAB_REPEAT_10X, BENCHMARK_STRESS
    val testTitle: String,
    val promptText: String,
    val responseText: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptEvalDurationMs: Long,
    val generationDurationMs: Long,
    val tokensPerSecond: Float,
    val totalDurationMs: Long,
    val nCtx: Int,
    val nBatch: Int,
    val nUbatch: Int,
    val nThreads: Int,
    val finishReason: String, // STOP_TOKEN, MAX_TOKENS, ABORTED_BY_USER, TIMEOUT, NATIVE_ERROR
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val javaHeapBytes: Long = 0,
    val nativeHeapBytes: Long = 0,
    val modelName: String = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
)
