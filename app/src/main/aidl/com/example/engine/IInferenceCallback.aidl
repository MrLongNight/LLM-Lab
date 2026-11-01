package com.example.engine;

interface IInferenceCallback {
    void onToken(String tokenText, int tokenIndex);
    void onProgress(float progressPercent, int step);
}
