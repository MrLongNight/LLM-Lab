#ifndef LLMLAB_NATIVE_H
#define LLMLAB_NATIVE_H

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <memory>

// Festgelegte und dokumentierte llama.cpp Referenzversion
#define LLAMACPP_TARGET_VERSION "b3600-android-arm64"
#define LLAMACPP_COMMIT_HASH "7e2a91df"

struct NativeInferenceConfig {
    int n_ctx = 2048;
    int n_batch = 512;
    int n_ubatch = 8;
    int n_threads = 4;
    float temperature = 0.7f;
    int max_tokens = 512;
};

struct NativeGenerationMetrics {
    int prompt_tokens = 0;
    int generated_tokens = 0;
    int64_t prompt_eval_time_us = 0;
    int64_t generation_time_us = 0;
    int64_t total_time_us = 0;
    int decode_calls = 0;
    int64_t first_progress_time_us = 0;
    float tokens_per_second = 0.0f;
    std::string finish_reason; // "STOP_TOKEN", "MAX_TOKENS", "ABORTED_BY_USER", "TIMEOUT", "NATIVE_ERROR"
    std::string error_message;
};

class NativeLlamaEngine {
public:
    NativeLlamaEngine();
    ~NativeLlamaEngine();

    bool loadModel(const std::string& path, const NativeInferenceConfig& config, std::string& outError);
    void unloadModel();
    bool isLoaded() const;

    void requestAbort();
    bool isAbortRequested() const;

    NativeGenerationMetrics generate(
        const std::string& prompt,
        const NativeInferenceConfig& config,
        jobject callbackObj,
        JNIEnv* env,
        jmethodID onTokenMethod,
        jmethodID onProgressMethod
    );

    int64_t getNativeAllocatedHeap() const;

private:
    std::atomic<bool> abort_flag{false};
    std::atomic<bool> is_model_loaded{false};
    void* llama_model_ptr = nullptr;
    void* llama_ctx_ptr = nullptr;
    std::string loaded_model_path;
    NativeInferenceConfig active_config;
};

#endif // LLMLAB_NATIVE_H
