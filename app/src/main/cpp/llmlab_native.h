#ifndef LLMLAB_NATIVE_H
#define LLMLAB_NATIVE_H

#include <jni.h>
#include <llama.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#define LLAMACPP_TARGET_VERSION "b3600"
#define LLAMACPP_COMMIT_HASH "2fb9267887d24a431892ce4dccc75c7095b0d54d"

struct NativeInferenceConfig {
    int n_ctx = 2048;
    int n_batch = 512;
    int n_ubatch = 8;
    int n_threads = 4;
    float temperature = 0.25f;
    int max_tokens = 512;
};

struct NativeGenerationMetrics {
    int prompt_tokens = 0;
    int generated_tokens = 0;
    int prompt_decode_calls = 0;
    int generation_decode_calls = 0;
    int decode_calls = 0;
    int64_t prompt_tokenize_time_us = 0;
    int64_t prompt_eval_time_us = 0;
    int64_t first_token_time_us = 0;
    int64_t generation_time_us = 0;
    int64_t total_time_us = 0;
    float tokens_per_second = 0.0f;
    std::string finish_reason;
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
    void resetAbort();
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
    static bool modelLoadProgress(float progress, void* userData);
    static bool abortCallback(void* userData);
    void clearModelLocked();

    std::atomic<bool> abort_flag{false};
    std::atomic<bool> is_model_loaded{false};
    mutable std::mutex engine_mutex;
    llama_model* llama_model_ptr = nullptr;
    llama_context* llama_ctx_ptr = nullptr;
    std::string loaded_model_path;
    std::string loaded_model_architecture;
    NativeInferenceConfig active_config;
};

#endif // LLMLAB_NATIVE_H
