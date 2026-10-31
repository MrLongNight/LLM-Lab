#include "llmlab_native.h"

#include <android/log.h>
#include <malloc.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <cctype>
#include <string>
#include <string_view>
#include <cstdint>
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


size_t completeUtf8PrefixLength(const std::string& bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const auto lead = static_cast<uint8_t>(bytes[i]);
        size_t width = 0;
        if (lead <= 0x7F) width = 1;
        else if (lead >= 0xC2 && lead <= 0xDF) width = 2;
        else if (lead >= 0xE0 && lead <= 0xEF) width = 3;
        else if (lead >= 0xF0 && lead <= 0xF4) width = 4;
        else { ++i; continue; }

        if (i + width > bytes.size()) break;
        bool valid = true;
        for (size_t j = 1; j < width; ++j) {
            const auto continuation = static_cast<uint8_t>(bytes[i + j]);
            if ((continuation & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (valid && width == 3) {
            const auto second = static_cast<uint8_t>(bytes[i + 1]);
            if ((lead == 0xE0 && second < 0xA0) || (lead == 0xED && second >= 0xA0)) valid = false;
        }
        if (valid && width == 4) {
            const auto second = static_cast<uint8_t>(bytes[i + 1]);
            if ((lead == 0xF0 && second < 0x90) || (lead == 0xF4 && second > 0x8F)) valid = false;
        }
        i += valid ? width : 1;
    }
    return i;
}

std::u16string decodeUtf8ToUtf16(std::string_view input) {
    std::u16string output;
    output.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const auto lead = static_cast<uint8_t>(input[i]);
        uint32_t codePoint = 0xFFFD;
        size_t width = 1;
        if (lead <= 0x7F) {
            codePoint = lead;
        } else {
            if (lead >= 0xC2 && lead <= 0xDF) { codePoint = lead & 0x1F; width = 2; }
            else if (lead >= 0xE0 && lead <= 0xEF) { codePoint = lead & 0x0F; width = 3; }
            else if (lead >= 0xF0 && lead <= 0xF4) { codePoint = lead & 0x07; width = 4; }

            bool valid = width > 1 && i + width <= input.size();
            for (size_t j = 1; valid && j < width; ++j) {
                const auto continuation = static_cast<uint8_t>(input[i + j]);
                if ((continuation & 0xC0) != 0x80) valid = false;
                else codePoint = (codePoint << 6) | (continuation & 0x3F);
            }
            if (valid) {
                const bool invalidScalar =
                    (width == 2 && codePoint < 0x80) ||
                    (width == 3 && codePoint < 0x800) ||
                    (width == 4 && codePoint < 0x10000) ||
                    (codePoint >= 0xD800 && codePoint <= 0xDFFF) ||
                    codePoint > 0x10FFFF;
                if (invalidScalar) valid = false;
            }
            if (!valid) {
                codePoint = 0xFFFD;
                width = 1;
            }
        }

        if (codePoint <= 0xFFFF) {
            output.push_back(static_cast<char16_t>(codePoint));
        } else {
            codePoint -= 0x10000;
            output.push_back(static_cast<char16_t>(0xD800 + (codePoint >> 10)));
            output.push_back(static_cast<char16_t>(0xDC00 + (codePoint & 0x3FF)));
        }
        i += width;
    }
    return output;
}

jstring newJavaStringFromUtf8(JNIEnv* env, std::string_view value) {
    const std::u16string utf16 = decodeUtf8ToUtf16(value);
    return env->NewString(
        reinterpret_cast<const jchar*>(utf16.data()),
        static_cast<jsize>(utf16.size()));
}


std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char hex[] = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0xF] << hex[c & 0xF];
                } else out << static_cast<char>(c);
        }
    }
    return out.str();
}

std::string lowercaseAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}

std::string backendKind(const std::string& registryName) {
    const std::string name = lowercaseAscii(registryName);
    if (name == "cpu" || name.find("cpu") != std::string::npos) return "CPU";
    if (name.find("hexagon") != std::string::npos ||
        name.find("qnn") != std::string::npos ||
        name.find("cann") != std::string::npos ||
        name.find("enn") != std::string::npos ||
        name.find("npu") != std::string::npos) return "NPU/ACCELERATOR";
    if (name == "rpc" || name.find("rpc") != std::string::npos) return "REMOTE";
    return "GPU/ACCELERATOR";
}

bool initializeRuntimeBackend(size_t index, std::string& runtimeName, std::string& bufferType) {
    ggml_backend_t backend = ggml_backend_reg_init_backend(index, nullptr);
    if (backend == nullptr) return false;
    const char* name = ggml_backend_name(backend);
    runtimeName = name != nullptr ? name : "";
    ggml_backend_buffer_type_t buffer = ggml_backend_get_default_buffer_type(backend);
    const char* bufferName = buffer != nullptr ? ggml_backend_buft_name(buffer) : nullptr;
    bufferType = bufferName != nullptr ? bufferName : "";
    ggml_backend_free(backend);
    return true;
}

std::string backendRegistryReportJson() {
    std::ostringstream out;
    out << "[";
    bool first = true;
    const size_t count = ggml_backend_reg_get_count();
    for (size_t i = 0; i < count; ++i) {
        const char* rawName = ggml_backend_reg_get_name(i);
        const std::string registryName = rawName != nullptr ? rawName : "Unknown";
        std::string runtimeName;
        std::string bufferType;
        const bool available = initializeRuntimeBackend(i, runtimeName, bufferType);
        if (!first) out << ",";
        first = false;
        out << "{"
            << "\"registryName\":\"" << jsonEscape(registryName) << "\","
            << "\"runtimeName\":\"" << jsonEscape(runtimeName) << "\","
            << "\"kind\":\"" << jsonEscape(backendKind(registryName)) << "\","
            << "\"bufferType\":\"" << jsonEscape(bufferType) << "\","
            << "\"available\":" << (available ? "true" : "false") << ","
            << "\"supportsAbort\":" << (backendKind(registryName) == "CPU" ? "true" : "false")
            << "}";
    }
    out << "]";
    return out.str();
}

std::string firstAvailableAcceleratorBackend() {
    const size_t count = ggml_backend_reg_get_count();
    for (size_t i = 0; i < count; ++i) {
        const char* rawName = ggml_backend_reg_get_name(i);
        const std::string registryName = rawName != nullptr ? rawName : "";
        const std::string kind = backendKind(registryName);
        if (kind == "CPU" || kind == "REMOTE") continue;
        std::string runtimeName;
        std::string bufferType;
        if (initializeRuntimeBackend(i, runtimeName, bufferType)) return runtimeName.empty() ? registryName : runtimeName;
    }
    return "";
}

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
    loaded_model_architecture.clear();
    active_backend_name = "CPU";
    active_gpu_layers_requested = 0;
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
    const std::string acceleratorBackend = firstAvailableAcceleratorBackend();
    const bool acceleratorAvailable = !acceleratorBackend.empty();
    const bool acceleratorRequired = config.backend_preference == 2;
    if (acceleratorRequired && !acceleratorAvailable) {
        outError = "Es wurde kein initialisierbares GPU-/Accelerator-Backend gefunden. CPU-Fallback ist bei dieser Einstellung absichtlich deaktiviert.";
        return false;
    }

    // llama.cpp b3600 documents abort_callback as CPU-only. AUTO therefore
    // prefers a reliably cancellable CPU execution path; GPU/NPU offload is
    // explicit and experimental until process isolation/backend cancellation exists.
    int requestedGpuLayers = (config.backend_preference == 2 && acceleratorAvailable) ? 999 : 0;
    auto makeModelParams = [&](int gpuLayers) {
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpuLayers;
        params.progress_callback = &NativeLlamaEngine::modelLoadProgress;
        params.progress_callback_user_data = this;
        return params;
    };

    LOGI("Lade GGUF mit llama.cpp %s (%s), n_ctx=%d, n_batch=%d, n_ubatch=%d, threads=%d, batch_threads=%d, backend_policy=%d, gpu_layers=%d",
         LLAMACPP_TARGET_VERSION, LLAMACPP_COMMIT_HASH, config.n_ctx, config.n_batch,
         config.n_ubatch, config.n_threads, config.n_threads_batch,
         config.backend_preference, requestedGpuLayers);

    llama_model_params modelParams = makeModelParams(requestedGpuLayers);
    llama_model* model = llama_load_model_from_file(path.c_str(), modelParams);
    if (model == nullptr && requestedGpuLayers > 0 && config.backend_preference == 0 && !isAbortRequested()) {
        LOGW("Accelerator-Modellinitialisierung fehlgeschlagen; AUTO versucht einmal den CPU-Fallback");
        requestedGpuLayers = 0;
        modelParams = makeModelParams(0);
        model = llama_load_model_from_file(path.c_str(), modelParams);
    }

    if (model == nullptr) {
        outError = isAbortRequested()
            ? "Modellladen durch Nutzer oder Timeout abgebrochen"
            : "llama_load_model_from_file fehlgeschlagen. GGUF-Datei, Backend-Kompatibilität und verfügbaren RAM prüfen.";
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
    contextParams.n_threads_batch = static_cast<uint32_t>(std::max(1, config.n_threads_batch));
    contextParams.n_seq_max = 1;
    contextParams.seed = 42;
    contextParams.offload_kqv = requestedGpuLayers > 0;
    contextParams.abort_callback = &NativeLlamaEngine::abortCallback;
    contextParams.abort_callback_data = this;

    llama_context* context = llama_new_context_with_model(model, contextParams);
    if (context == nullptr) {
        llama_free_model(model);
        outError = isAbortRequested()
            ? "Context-Initialisierung abgebrochen"
            : "llama_new_context_with_model fehlgeschlagen. Context-Größe, Backend oder Arbeitsspeicher prüfen.";
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
    active_gpu_layers_requested = requestedGpuLayers;
    active_backend_name = requestedGpuLayers > 0 ? acceleratorBackend : "CPU";
    is_model_loaded.store(true);

    char modelDescription[256] = {};
    llama_model_desc(model, modelDescription, sizeof(modelDescription));
    char architecture[128] = {};
    const int32_t architectureLength = llama_model_meta_val_str(
        model, "general.architecture", architecture, sizeof(architecture));
    loaded_model_architecture = architectureLength > 0 ? architecture : "";
    char templateMetadata[256] = {};
    const int32_t templateMetadataLength = llama_model_meta_val_str(
        model, "tokenizer.chat_template", templateMetadata, sizeof(templateMetadata));
    LOGI("GGUF architecture=%s | chat_template_metadata=%s (%d bytes)",
         loaded_model_architecture.empty() ? "unavailable" : loaded_model_architecture.c_str(),
         templateMetadataLength > 0 ? "present" : "missing/truncated", templateMetadataLength);
    LOGI("Backend-Auswahl=%s, n_gpu_layers_requested=%d, offload_kqv=%s. Die tatsächliche Layerzahl steht im llama.cpp Modelllade-Log.",
         active_backend_name.c_str(), active_gpu_layers_requested,
         contextParams.offload_kqv ? "true" : "false");
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

std::string NativeLlamaEngine::getActiveBackendName() const {
    std::lock_guard<std::mutex> lock(engine_mutex);
    if (!is_model_loaded.load()) return "Kein Modell geladen";
    if (active_gpu_layers_requested > 0) {
        return active_backend_name + " (experimenteller Offload; Abbruch nicht garantiert; Layerzahl siehe llama.cpp-Load-Log)";
    }
    return "CPU";
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

    // Keep the system message concise: a 1.5B model should not waste context
    // or attention on a long policy prompt for ordinary chat questions.
    const std::string systemPrompt =
        "Du bist ein sachlicher KI-Assistent. Antworte in der Sprache der Frage. "
        "Beantworte die konkrete Frage direkt und korrekt, in höchstens zwei kurzen Sätzen, "
        "wenn keine längere Antwort verlangt wird. Stelle dich nicht unaufgefordert vor. "
        "Verwechsle Begriffe nicht mit Orten oder Personen. Erfinde keine Fakten. "
        "Wenn du etwas nicht sicher weißt, sage das klar. Befolge gewünschte Formate exakt.";

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

    std::string templateMode = "GGUF_DEFAULT";
    if (templateLength >= 0 &&
        templateLength <= static_cast<int32_t>(templateBuffer.size())) {
        formattedPrompt.assign(templateBuffer.data(), static_cast<size_t>(templateLength));
        addBos = false;
        parseSpecial = true;
    } else if (loaded_model_architecture == "qwen2") {
        // Qwen 2.5 Instruct uses ChatML. The built-in template formatter in the
        // pinned llama.cpp build may not support every GGUF/Jinja variant, so
        // use Qwen's exact role/end markers instead of a generic plain-text prefix.
        formattedPrompt =
            "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n" +
            "<|im_start|>user\n" + prompt + "<|im_end|>\n" +
            "<|im_start|>assistant\n";
        addBos = false;
        parseSpecial = true;
        templateMode = "QWEN2_CHATML_FALLBACK";
        LOGW("GGUF default chat template not available; using explicit Qwen2 ChatML");
    } else {
        setNativeError(metrics,
            "Kein unterstütztes Chat-Template im GGUF gefunden (Architektur: " +
            (loaded_model_architecture.empty() ? std::string("unbekannt") : loaded_model_architecture) +
            "). Ohne korrektes Template wird keine Antwort generiert.");
        return finish();
    }
    LOGI("Chat template mode=%s architecture=%s prompt_chars=%zu",
         templateMode.c_str(),
         loaded_model_architecture.empty() ? "unavailable" : loaded_model_architecture.c_str(),
         formattedPrompt.size());

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
    std::vector<char> pieceBuffer(256);
    std::string pendingUtf8Bytes;

    // Do not penalize the user's prompt tokens. On small instruction models this
    // can suppress key nouns from the question and degrade simple factual answers.
    LOGI("Sampler: temperature=%.2f top_k=%d top_p=%.2f repeat_penalty=1.00", config.temperature, config.top_k, config.top_p);
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

        llama_token sampledToken;
        if (config.temperature <= 0.0f) {
            sampledToken = llama_sample_token_greedy(llama_ctx_ptr, &candidatesArray);
        } else {
            llama_sample_top_k(llama_ctx_ptr, &candidatesArray, config.top_k, 1);
            llama_sample_top_p(llama_ctx_ptr, &candidatesArray, config.top_p, 1);
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
        if (metrics.first_token_time_us == 0) {
            metrics.first_token_time_us = std::max<int64_t>(0, llama_time_us() - totalStartUs);
        }

        if (callbackObj != nullptr && onTokenMethod != nullptr) {
            pendingUtf8Bytes += tokenText;
            const size_t completeBytes = completeUtf8PrefixLength(pendingUtf8Bytes);
            if (completeBytes > 0) {
                const std::string_view completeText(pendingUtf8Bytes.data(), completeBytes);
                jstring jToken = newJavaStringFromUtf8(env, completeText);
                if (jToken == nullptr) {
                    setNativeError(metrics, "JNI konnte UTF-8 Token-Text nicht als Java-String bereitstellen");
                    return finish();
                }
                env->CallVoidMethod(callbackObj, onTokenMethod, jToken,
                                    static_cast<jint>(metrics.generated_tokens - 1));
                env->DeleteLocalRef(jToken);
                pendingUtf8Bytes.erase(0, completeBytes);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    setNativeError(metrics, "Kotlin-Callback für generierte Tokens hat eine Exception ausgelöst");
                    return finish();
                }
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

    if (callbackObj != nullptr && onTokenMethod != nullptr && !pendingUtf8Bytes.empty()) {
        // Flush an incomplete trailing UTF-8 sequence as U+FFFD rather than
        // dropping its bytes or passing malformed UTF-8 through NewStringUTF.
        jstring jToken = newJavaStringFromUtf8(env, pendingUtf8Bytes);
        if (jToken != nullptr) {
            env->CallVoidMethod(callbackObj, onTokenMethod, jToken,
                                static_cast<jint>(metrics.generated_tokens - 1));
            env->DeleteLocalRef(jToken);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                setNativeError(metrics, "Kotlin-Callback beim UTF-8 Flush hat eine Exception ausgelöst");
            }
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
                                " (" + LLAMACPP_COMMIT_HASH + "), runtime backend registry, optimized -O3";
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeGetBackendReport(JNIEnv* env, jobject /* this */) {
    std::call_once(g_backend_init_once, []() { llama_backend_init(); });
    return newJavaStringFromUtf8(env, backendRegistryReportJson());
}

JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaNativeBridge_nativeGetActiveBackendName(JNIEnv* env, jobject /* this */, jlong handle) {
    if (handle == 0) return newJavaStringFromUtf8(env, "Native Engine nicht verfügbar");
    return newJavaStringFromUtf8(env, reinterpret_cast<NativeLlamaEngine*>(handle)->getActiveBackendName());
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
    jint nThreadsBatch,
    jint backendPreference,
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
    config.n_threads_batch = nThreadsBatch;
    config.backend_preference = backendPreference;

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
    jint nThreadsBatch,
    jfloat temperature,
    jint topK,
    jfloat topP,
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
    config.n_threads_batch = nThreadsBatch;
    config.temperature = temperature;
    config.top_k = std::max(1, topK);
    config.top_p = std::clamp(topP, 0.01f, 1.0f);
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
