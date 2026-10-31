#include "llmlab_native.h"

#include <android/log.h>
#include <malloc.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define TAG "LLMLabNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
std::once_flag g_backend_init_once;

class ScopedBatch {
public:
    explicit ScopedBatch(int32_t capacity)
        : batch_(llama_batch_init(std::max<int32_t>(1, capacity), 0, 1)) {}

    ~ScopedBatch() {
        llama_batch_free(batch_);
    }

    llama_batch& get() { return batch_; }

    bool valid() const {
        return batch_.token != nullptr &&
               batch_.pos != nullptr &&
               batch_.n_seq_id != nullptr &&
               batch_.seq_id != nullptr &&
               batch_.logits != nullptr;
    }

private:
    llama_batch batch_{};
};

void setNativeError(NativeGenerationMetrics& metrics, const std::string& message) {
    metrics.finish_reason = "NATIVE_ERROR";
    metrics.error_message = message;
}
} // namespace

NativeLlamaEngine::NativeLlamaEngine() {
    std::call_once(g_backend_init_once, []() {
        llama_backend_init();
        LOGI("llama.cpp Backend initialisiert");
    });
    abort_flag.store(false);
    is_model_loaded.store(false);
}

NativeLlamaEngine::~NativeLlamaEngine() {
    unloadModel();
}

bool NativeLlamaEngine::modelLoadProgress(float progress, void* userData) {
    (void)progress;
    auto* engine = static_cast<NativeLlamaEngine*>(userData);
    return engine != nullptr && !engine->isAbortRequested();
}

bool NativeLlamaEngine::abortCallback(void* userData) {
    auto* engine = static_cast<NativeLlamaEngine*>(userData);
    return engine == nullptr || engine->isAbortRequested();
}

void NativeLlamaEngine::clearModelLocked() {
    is_model_loaded.store(false);
    if (llama_ctx_ptr != nullptr) {
        llama_free(llama_ctx_ptr);
        llama_ctx_ptr = nullptr;
    }
    if (llama_model_ptr != nullptr) {
        llama_free_model(llama_model_ptr);
        llama_model_ptr = nullptr;
    }
    loaded_model_path.clear();
}

bool NativeLlamaEngine::loadModel(
    const std::string& path,
    const NativeInferenceConfig& config,
    std::string& outError
) {
    std::lock_guard<std::mutex> lock(engine_mutex);
    clearModelLocked();

    if (isAbortRequested()) {
        outError = "Modellinitialisierung vor dem Start abgebrochen";
        return false;
    }

    active_config = config;

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0; // Explizit CPU-only für reproduzierbare Gerätetests.
    modelParams.progress_callback = &NativeLlamaEngine::modelLoadProgress;
    modelParams.progress_callback_user_data = this;

    LOGI("Lade GGUF mit llama.cpp %s (%s), n_ctx=%d, n_batch=%d, n_ubatch=%d, threads=%d",
         LLAMACPP_TARGET_VERSION,
         LLAMACPP_COMMIT_HASH,
         config.n_ctx,
         config.n_batch,
         config.n_ubatch,
         config.n_threads);

    llama_model* model = llama_load_model_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        outError = isAbortRequested()
            ? "Modellladen durch Nutzer oder Timeout abgebrochen"
            : "llama_load_model_from_file fehlgeschlagen. GGUF-Datei, Modellkompatibilität und verfügbaren RAM prüfen.";
        LOGE("%s", outError.c_str());
        return false;
    }

    if (isAbortRequested()) {
        llama_free_model(model);
        outError = "Modellladen abgebrochen";
        return false;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(std::max(128, config.n_ctx));
    contextParams.n_batch = static_cast<uint32_t>(std::max(1, config.n_batch));
    contextParams.n_ubatch = static_cast<uint32_t>(std::max(1, std::min(config.n_ubatch, config.n_batch)));
    contextParams.n_threads = static_cast<uint32_t>(std::max(1, config.n_threads));
    contextParams.n_threads_batch = static_cast<uint32_t>(std::max(1, config.n_threads));
    contextParams.n_seq_max = 1;
    contextParams.seed = 42;
    contextParams.offload_kqv = false;
    contextParams.abort_callback = &NativeLlamaEngine::abortCallback;
    contextParams.abort_callback_data = this;

    llama_context* context = llama_new_context_with_model(model, contextParams);
    if (context == nullptr) {
        llama_free_model(model);
        outError = isAbortRequested()
            ? "Context-Initialisierung abgebrochen"
            : "llama_new_context_with_model fehlgeschlagen. Context-Größe oder Arbeitsspeicher prüfen.";
        LOGE("%s", outError.c_str());
        return false;
    }

    if (isAbortRequested()) {
        llama_free(context);
        llama_free_model(model);
        outError = "Modellinitialisierung nach Context-Erstellung abgebrochen";
        return false;
    }

    llama_model_ptr = model;
    llama_ctx_ptr = context;
    loaded_model_path = path;
    is_model_loaded.store(true);

    char modelDescription[256] = {};
    llama_model_desc(model, modelDescription, sizeof(modelDescription));
    LOGI("Modell vollständig geladen: %s | params=%llu | weights=%llu bytes | n_ctx=%u",
         modelDescription,
         static_cast<unsigned long long>(llama_model_n_params(model)),
         static_cast<unsigned long long>(llama_model_size(model)),
         llama_n_ctx(context));
    return true;
}

void NativeLlamaEngine::unloadModel() {
    requestAbort();
    std::lock_guard<std::mutex> lock(engine_mutex);
    clearModelLocked();
}

bool NativeLlamaEngine::isLoaded() const {
    return is_model_loaded.load();
}

void NativeLlamaEngine::requestAbort() {
    abort_flag.store(true);
    LOGW("Native Abbruch angefordert");
}

bool NativeLlamaEngine::isAbortRequested() const {
    return abort_flag.load();
}

void NativeLlamaEngine::resetAbort() {
    // Serialize abort reset against model load / inference. A new operation must
    // never clear the abort signal while a previous native call is still running.
    std::lock_guard<std::mutex> lock(engine_mutex);
    abort_flag.store(false);
}

int64_t NativeLlamaEngine::getNativeAllocatedHeap() const {
    std::lock_guard<std::mutex> lock(engine_mutex);
    const struct mallinfo info = mallinfo();
    return static_cast<int64_t>(info.uordblks);
}

NativeGenerationMetrics NativeLlamaEngine::generate(
    const std::string& prompt,
    const NativeInferenceConfig& config,
    jobject callbackObj,
    JNIEnv* env,
    jmethodID onTokenMethod,
    jmethodID onProgressMethod
) {
    std::lock_guard<std::mutex> lock(engine_mutex);

    NativeGenerationMetrics metrics;
    const int64_t totalStartUs = llama_time_us();
    int64_t generationStartUs = 0;

    const auto finish = [&]() {
        const int64_t nowUs = llama_time_us();
        metrics.total_time_us = std::max<int64_t>(0, nowUs - totalStartUs);
        if (generationStartUs > 0 && metrics.generation_time_us == 0) {
            metrics.generation_time_us = std::max<int64_t>(0, nowUs - generationStartUs);
        }
        if (metrics.generation_time_us > 0) {
            metrics.tokens_per_second =
                static_cast<float>(metrics.generated_tokens) * 1000000.0f /
                static_cast<float>(metrics.generation_time_us);
        }
        if (metrics.finish_reason.empty()) {
            metrics.finish_reason = "NATIVE_ERROR";
            metrics.error_message = "Inferenz wurde ohne Finish Reason beendet";
        }
        return metrics;
    };

    if (!is_model_loaded.load() || llama_model_ptr == nullptr || llama_ctx_ptr == nullptr) {
        setNativeError(metrics, "Keine vollständig initialisierte llama.cpp Model-/Context-Instanz");
        return finish();
    }
    if (isAbortRequested()) {
        metrics.finish_reason = "ABORTED_BY_USER";
        metrics.error_message = "Abbruch vor Inferenzstart";
        return finish();
    }
    if (prompt.empty()) {
        setNativeError(metrics, "Prompt ist leer");
        return finish();
    }

    llama_kv_cache_clear(llama_ctx_ptr);

    // Give instruction-tuned models a concise, explicit system message.
    const std::string systemPrompt =
        "Du bist ein hilfreicher, sachlicher Assistent. Antworte in der Sprache der Nutzerfrage. "
        "Beantworte einfache Fragen direkt und knapp in ein bis vier kurzen Saetzen. "
        "Gehe auf die konkrete Frage ein und stelle dich nicht unaufgefordert vor. "
        "Behaupte nicht, ein Mensch oder ein Bewusstsein zu sein. "
        "Erfinde keine Fakten; kennzeichne Unsicherheit klar. "
        "Befolge angeforderte Ausgabeformate exakt.";

    std::string formattedPrompt = prompt;
    bool addBos = true;
    bool parseSpecial = false;
    llama_chat_message messages[2];
    messages[0].role = "system";
    messages[0].content = systemPrompt.c_str();
    messages[1].role = "user";
    messages[1].content = prompt.c_str();

    std::vector<char> templateBuffer(
        std::max<size_t>(1024, (prompt.size() + systemPrompt.size()) * 2 + 512));
    int32_t templateLength = llama_chat_apply_template(
        llama_model_ptr, nullptr, messages, 2, true,
        templateBuffer.data(), static_cast<int32_t>(templateBuffer.size()));

    if (templateLength > static_cast<int32_t>(templateBuffer.size())) {
        templateBuffer.resize(static_cast<size_t>(templateLength) + 1);
        templateLength = llama_chat_apply_template(
            llama_model_ptr, nullptr, messages, 2, true,
            templateBuffer.data(), static_cast<int32_t>(templateBuffer.size()));
    }

    if (templateLength >= 0 &&
        templateLength <= static_cast<int32_t>(templateBuffer.size())) {
        formattedPrompt.assign(templateBuffer.data(), static_cast<size_t>(templateLength));
        addBos = false;
        parseSpecial = true;
    } else {
        // Retain the instruction prefix for custom templates without system-role support.
        LOGW("GGUF Chat-Template unterstuetzt keine Systemrolle; verwende Text-Prefix");
        formattedPrompt = systemPrompt + "\n\nNutzerfrage:\n" + prompt + "\n\nAntwort:";
    }

    const int64_t tokenizeStartUs = llama_time_us();
    int32_t requiredTokens = llama_tokenize(
        llama_model_ptr,
        formattedPrompt.c_str(),
        static_cast<int32_t>(formattedPrompt.size()),
        nullptr,
        0,
        addBos,
        parseSpecial);

    if (requiredTokens < 0) {
        requiredTokens = -requiredTokens;
    }
    if (requiredTokens <= 0) {
        setNativeError(metrics, "llama_tokenize lieferte keine Prompt-Tokens");
        return finish();
    }

    std::vector<llama_token> promptTokens(static_cast<size_t>(requiredTokens));
    int32_t tokenizedCount = llama_tokenize(
        llama_model_ptr,
        formattedPrompt.c_str(),
        static_cast<int32_t>(formattedPrompt.size()),
        promptTokens.data(),
        static_cast<int32_t>(promptTokens.size()),
        addBos,
        parseSpecial);

    if (tokenizedCount < 0) {
        promptTokens.resize(static_cast<size_t>(-tokenizedCount));
        tokenizedCount = llama_tokenize(
            llama_model_ptr,
            formattedPrompt.c_str(),
            static_cast<int32_t>(formattedPrompt.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            addBos,
            parseSpecial);
    }

    metrics.prompt_tokenize_time_us = std::max<int64_t>(0, llama_time_us() - tokenizeStartUs);
    if (tokenizedCount <= 0) {
        setNativeError(metrics, "llama_tokenize konnte den Prompt nicht vollständig tokenisieren");
        return finish();
    }
    promptTokens.resize(static_cast<size_t>(tokenizedCount));
    metrics.prompt_tokens = tokenizedCount;

    const uint32_t contextSize = llama_n_ctx(llama_ctx_ptr);
    if (promptTokens.size() >= contextSize) {
        setNativeError(metrics, "Prompt belegt den gesamten Context oder überschreitet n_ctx");
        return finish();
    }

    const int32_t batchCapacity = static_cast<int32_t>(
        std::max<uint32_t>(1, llama_n_batch(llama_ctx_ptr)));
    ScopedBatch batchHolder(batchCapacity);
    if (!batchHolder.valid()) {
        setNativeError(metrics, "llama_batch_init konnte keinen Prompt-Batch reservieren");
        return finish();
    }
    llama_batch& batch = batchHolder.get();

    // Prompt-Evaluierung in logischen n_batch-Blöcken; llama.cpp teilt intern in n_ubatch auf.
    const int64_t promptEvalStartUs = llama_time_us();
    size_t evaluated = 0;
    while (evaluated < promptTokens.size()) {
        if (isAbortRequested()) {
            metrics.finish_reason = "ABORTED_BY_USER";
            metrics.error_message = "Prompt-Evaluierung abgebrochen";
            metrics.prompt_eval_time_us = std::max<int64_t>(0, llama_time_us() - promptEvalStartUs);
            return finish();
        }

        const size_t count = std::min(
            static_cast<size_t>(batchCapacity),
            promptTokens.size() - evaluated);
        batch.n_tokens = static_cast<int32_t>(count);

        for (size_t i = 0; i < count; ++i) {
            const size_t absolutePosition = evaluated + i;
            batch.token[i] = promptTokens[absolutePosition];
            batch.pos[i] = static_cast<llama_pos>(absolutePosition);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i + 1 == count) ? 1 : 0;
        }

        const int decodeResult = llama_decode(llama_ctx_ptr, batch);
        metrics.decode_calls++;
        metrics.prompt_decode_calls++;
        if (decodeResult != 0) {
            metrics.prompt_eval_time_us = std::max<int64_t>(0, llama_time_us() - promptEvalStartUs);
            if (isAbortRequested()) {
                metrics.finish_reason = "ABORTED_BY_USER";
                metrics.error_message = "llama_decode während Prompt-Evaluierung abgebrochen";
            } else {
                setNativeError(metrics, "llama_decode fehlgeschlagen bei Prompt-Evaluierung, Code=" +
                                        std::to_string(decodeResult));
            }
            return finish();
        }

        evaluated += count;
        if (callbackObj != nullptr && onProgressMethod != nullptr) {
            const float progress = static_cast<float>(evaluated) /
                                   static_cast<float>(promptTokens.size()) * 100.0f;
            env->CallVoidMethod(callbackObj, onProgressMethod, static_cast<jfloat>(progress),
                                static_cast<jint>(metrics.prompt_decode_calls));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                setNativeError(metrics, "Kotlin-Callback für Prompt-Fortschritt hat eine Exception ausgelöst");
                return finish();
            }
        }
    }
    metrics.prompt_eval_time_us = std::max<int64_t>(0, llama_time_us() - promptEvalStartUs);

    generationStartUs = llama_time_us();
    llama_set_rng_seed(llama_ctx_ptr, 42);

    const int32_t vocabSize = llama_n_vocab(llama_model_ptr);
    if (vocabSize <= 0) {
        setNativeError(metrics, "Modell meldet eine ungültige Vokabulargröße");
        return finish();
    }

    const int32_t maxTokens = std::max(1, config.max_tokens);
    // Allocate generation buffers once instead of rebuilding a full-vocabulary vector per token.
    std::vector<llama_token_data> candidates(static_cast<size_t>(vocabSize));
    std::vector<llama_token> recentTokens;
    const size_t repeatWindow = 64;
    const size_t initialRecent = std::min(repeatWindow, promptTokens.size());
    recentTokens.insert(promptTokens.end() - static_cast<std::ptrdiff_t>(initialRecent), promptTokens.end());
    std::vector<char> pieceBuffer(256);

    LOGI("Sampler: temperature=%.2f top_k=40 top_p=0.90 repeat_penalty=1.08", config.temperature);
    for (int32_t generated = 0; generated < maxTokens; ++generated) {
        if (isAbortRequested()) {
            metrics.finish_reason = "ABORTED_BY_USER";
            metrics.error_message = "Token-Generierung durch Nutzer abgebrochen";
            return finish();
        }

        float* logits = llama_get_logits_ith(llama_ctx_ptr, -1);
        if (logits == nullptr) {
            setNativeError(metrics, "llama_get_logits_ith lieferte keine Logits");
            return finish();
        }

        // Samplers may reorder candidates; restore IDs and refresh logit/probability values.
        for (int32_t token = 0; token < vocabSize; ++token) {
            candidates[static_cast<size_t>(token)].id = token;
            candidates[static_cast<size_t>(token)].logit = logits[token];
            candidates[static_cast<size_t>(token)].p = 0.0f;
        }
        llama_token_data_array candidatesArray{candidates.data(), candidates.size(), false};
        llama_sample_repetition_penalties(
            llama_ctx_ptr, &candidatesArray, recentTokens.data(), recentTokens.size(),
            1.08f, 0.0f, 0.0f);

        llama_token sampledToken;
        if (config.temperature <= 0.0f) {
            sampledToken = llama_sample_token_greedy(llama_ctx_ptr, &candidatesArray);
        } else {
            llama_sample_top_k(llama_ctx_ptr, &candidatesArray, 40, 1);
            llama_sample_top_p(llama_ctx_ptr, &candidatesArray, 0.90f, 1);
            llama_sample_temp(llama_ctx_ptr, &candidatesArray, config.temperature);
            llama_sample_softmax(llama_ctx_ptr, &candidatesArray);
            sampledToken = llama_sample_token(llama_ctx_ptr, &candidatesArray);
        }

        if (llama_token_is_eog(llama_model_ptr, sampledToken)) {
            metrics.finish_reason = "STOP_TOKEN";
            break;
        }

        int32_t pieceSize = llama_token_to_piece(
            llama_model_ptr, sampledToken, pieceBuffer.data(),
            static_cast<int32_t>(pieceBuffer.size()), 0, false);
        if (pieceSize < 0) {
            pieceBuffer.resize(static_cast<size_t>(-pieceSize));
            pieceSize = llama_token_to_piece(
                llama_model_ptr, sampledToken, pieceBuffer.data(),
                static_cast<int32_t>(pieceBuffer.size()), 0, false);
        }
        if (pieceSize < 0) {
            setNativeError(metrics, "llama_token_to_piece konnte einen Token nicht dekodieren");
            return finish();
        }

        const std::string tokenText(
            pieceBuffer.data(),
            static_cast<size_t>(std::max<int32_t>(0, pieceSize)));

        metrics.generated_tokens++;
        recentTokens.push_back(sampledToken);
        if (recentTokens.size() > repeatWindow) recentTokens.erase(recentTokens.begin());
        if (metrics.first_token_time_us == 0) {
            metrics.first_token_time_us = std::max<int64_t>(0, llama_time_us() - totalStartUs);
        }

        if (callbackObj != nullptr && onTokenMethod != nullptr) {
            jstring jToken = env->NewStringUTF(tokenText.c_str());
            if (jToken == nullptr) {
                setNativeError(metrics, "JNI konnte Token-Text nicht als Java-String bereitstellen");
                return finish();
            }
            env->CallVoidMethod(callbackObj, onTokenMethod, jToken,
                                static_cast<jint>(metrics.generated_tokens - 1));
            env->DeleteLocalRef(jToken);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                setNativeError(metrics, "Kotlin-Callback für generierte Tokens hat eine Exception ausgelöst");
                return finish();
            }
        }

        if (metrics.generated_tokens >= maxTokens) {
            metrics.finish_reason = "MAX_TOKENS";
            break;
        }

        // Den gerade gesampelten Token für die nächste Logit-Berechnung dekodieren.
        batch.n_tokens = 1;
        batch.token[0] = sampledToken;
        batch.pos[0] = static_cast<llama_pos>(promptTokens.size() +
                                               static_cast<size_t>(metrics.generated_tokens - 1));
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        const int decodeResult = llama_decode(llama_ctx_ptr, batch);
        metrics.decode_calls++;
        metrics.generation_decode_calls++;
        if (decodeResult != 0) {
            if (isAbortRequested()) {
                metrics.finish_reason = "ABORTED_BY_USER";
                metrics.error_message = "llama_decode während Token-Generierung abgebrochen";
            } else {
                setNativeError(metrics, "llama_decode fehlgeschlagen bei Token-Generierung, Code=" +
                                        std::to_string(decodeResult));
            }
            return finish();
        }
    }

    if (metrics.finish_reason.empty()) {
        metrics.finish_reason = "MAX_TOKENS";
    }
    return finish();
}

// ----------------- JNI BINDINGS -----------------

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeGetVersion(JNIEnv* env, jobject /* this */) {
    const std::string version = std::string("llama.cpp ") + LLAMACPP_TARGET_VERSION +
                                " (" + LLAMACPP_COMMIT_HASH + "), CPU backend, optimized -O3";
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeCreateEngine(JNIEnv* /* env */, jobject /* this */) {
    NativeLlamaEngine* engine = new NativeLlamaEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeDestroyEngine(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<NativeLlamaEngine*>(handle);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jstring jPath,
    jint nCtx,
    jint nBatch,
    jint nUbatch,
    jint nThreads,
    jobjectArray outErrorArray
) {
    if (handle == 0 || jPath == nullptr) return JNI_FALSE;
    auto* engine = reinterpret_cast<NativeLlamaEngine*>(handle);

    const char* pathStr = env->GetStringUTFChars(jPath, nullptr);
    if (pathStr == nullptr) return JNI_FALSE;
    const std::string path(pathStr);
    env->ReleaseStringUTFChars(jPath, pathStr);

    NativeInferenceConfig config;
    config.n_ctx = nCtx;
    config.n_batch = nBatch;
    config.n_ubatch = nUbatch;
    config.n_threads = nThreads;

    std::string error;
    const bool ok = engine->loadModel(path, config, error);
    if (!ok && outErrorArray != nullptr && env->GetArrayLength(outErrorArray) > 0) {
        jstring jError = env->NewStringUTF(error.c_str());
        env->SetObjectArrayElement(outErrorArray, 0, jError);
        env->DeleteLocalRef(jError);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeUnloadModel(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle != 0) {
        reinterpret_cast<NativeLlamaEngine*>(handle)->unloadModel();
    }
}

JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeRequestAbort(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle != 0) {
        reinterpret_cast<NativeLlamaEngine*>(handle)->requestAbort();
    }
}

JNIEXPORT void JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeResetAbort(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle != 0) {
        // Reset is only called immediately before a new serialized native operation.
        // It must not be called from inside the inference loop.
        reinterpret_cast<NativeLlamaEngine*>(handle)->resetAbort();
    }
}

JNIEXPORT jobject JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeExecuteInference(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jstring jPrompt,
    jint nCtx,
    jint nBatch,
    jint nUbatch,
    jint nThreads,
    jfloat temperature,
    jint maxTokens,
    jobject callbackObj
) {
    if (handle == 0 || jPrompt == nullptr) return nullptr;
    auto* engine = reinterpret_cast<NativeLlamaEngine*>(handle);

    const char* promptStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (promptStr == nullptr) return nullptr;
    const std::string prompt(promptStr);
    env->ReleaseStringUTFChars(jPrompt, promptStr);

    NativeInferenceConfig config;
    config.n_ctx = nCtx;
    config.n_batch = nBatch;
    config.n_ubatch = nUbatch;
    config.n_threads = nThreads;
    config.temperature = temperature;
    config.max_tokens = maxTokens;

    jclass callbackClass = callbackObj != nullptr ? env->GetObjectClass(callbackObj) : nullptr;
    jmethodID onTokenMethod = callbackClass != nullptr
        ? env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;I)V") : nullptr;
    jmethodID onProgressMethod = callbackClass != nullptr
        ? env->GetMethodID(callbackClass, "onProgress", "(FI)V") : nullptr;

    NativeGenerationMetrics metrics = engine->generate(
        prompt, config, callbackObj, env, onTokenMethod, onProgressMethod);

    jclass metricsClass = env->FindClass("com/example/engine/NativeMetrics");
    if (metricsClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(
        metricsClass,
        "<init>",
        "(IIJJJIFLjava/lang/String;Ljava/lang/String;JJII)V");
    if (constructor == nullptr) return nullptr;

    jstring jFinishReason = env->NewStringUTF(metrics.finish_reason.c_str());
    jstring jErrorMessage = env->NewStringUTF(metrics.error_message.c_str());

    jobject result = env->NewObject(
        metricsClass,
        constructor,
        static_cast<jint>(metrics.prompt_tokens),
        static_cast<jint>(metrics.generated_tokens),
        static_cast<jlong>(metrics.prompt_eval_time_us / 1000),
        static_cast<jlong>(metrics.generation_time_us / 1000),
        static_cast<jlong>(metrics.total_time_us / 1000),
        static_cast<jint>(metrics.decode_calls),
        static_cast<jfloat>(metrics.tokens_per_second),
        jFinishReason,
        jErrorMessage,
        static_cast<jlong>(metrics.prompt_tokenize_time_us / 1000),
        static_cast<jlong>(metrics.first_token_time_us / 1000),
        static_cast<jint>(metrics.prompt_decode_calls),
        static_cast<jint>(metrics.generation_decode_calls)
    );

    env->DeleteLocalRef(jFinishReason);
    env->DeleteLocalRef(jErrorMessage);
    env->DeleteLocalRef(metricsClass);
    if (callbackClass != nullptr) env->DeleteLocalRef(callbackClass);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeGetAllocatedHeap(JNIEnv* /* env */, jobject /* this */, jlong handle) {
    if (handle == 0) return 0;
    return static_cast<jlong>(
        reinterpret_cast<NativeLlamaEngine*>(handle)->getNativeAllocatedHeap());
}

} // extern "C"
