package com.example.engine

interface InferenceCallback {
    fun onToken(tokenText: String, tokenIndex: Int)
    fun onProgress(progressPercent: Float, step: Int)
}
