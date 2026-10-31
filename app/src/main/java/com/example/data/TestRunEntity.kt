package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "test_runs")
data class TestRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val testCategory: String, // CHAT, TEST_PERFORMANCE_*, TEST_DMS_*, TEST_EXTRACTION_*, BENCHMARK_STRESS
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
    val modelName: String = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
    @ColumnInfo(defaultValue = "0")
    val promptTokenizeDurationMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val firstTokenDurationMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val promptDecodeCalls: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val generationDecodeCalls: Int = 0,
    @ColumnInfo(defaultValue = "4")
    val nThreadsBatch: Int = 4,
    @ColumnInfo(defaultValue = "'AUTO'")
    val backendPreference: String = "AUTO",
    @ColumnInfo(defaultValue = "'CPU'")
    val activeBackend: String = "CPU",
    @ColumnInfo(defaultValue = "0.2")
    val temperature: Float = 0.2f,
    @ColumnInfo(defaultValue = "20")
    val topK: Int = 20,
    @ColumnInfo(defaultValue = "0.8")
    val topP: Float = 0.8f,
    @ColumnInfo(defaultValue = "160")
    val maxTokens: Int = 160,
    @ColumnInfo(defaultValue = "NULL")
    val appCpuUsagePercent: Float? = null
)
