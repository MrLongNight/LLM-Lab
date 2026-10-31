#ifndef LLMLAB_NATIVE_H
#define LLMLAB_NATIVE_H

#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#define LLAMACPP_TARGET_VERSION "b11998"
#define LLAMACPP_COMMIT_HASH "78feec062d50ec9e8940244cac918ac4480b60cb"

struct NativeInferenceConfig {
    int n_ctx = 2048;
    int n_batch = 512;
    int n_ubatch = 8;
    int n_threads = 4;
    int n_threads_batch = 8;
    int backend_preference = 0;
    float temperature = 0.2f;
    int top_k = 20;
    float top_p = 0.8f;
    int max_tokens = 160;
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
    bool reconfigureContext(const NativeInferenceConfig& config, std::string& outError);
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
    std::string getActiveBackendName() const;
    std::string getModelLoadLog() const;

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
    std::string active_backend_name = "CPU";
    std::string model_load_log;
    std::string context_reconfigure_log;
    int active_gpu_layers_requested = 0;
    ggml_backend_dev_t active_backend_device = nullptr;
    NativeInferenceConfig active_config;
};

#endif // LLMLAB_NATIVE_H
