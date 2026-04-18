#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <string>
#include <sstream>
#include <fstream>
#include <atomic>
#include <cstring>
#include <cstdint>

#define LOG_TAG "MLC_LLM_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

struct RuntimeFunctions {
    using CreateEngineFn = void* (*)(const char*, const char*, const char*);
    using DestroyEngineFn = void (*)(void*);
    using RunVisionFn = bool (*)(void*, const uint8_t*, int, int, const char*, void (*)(const char*, void*), void*);
    using RunTextFn = bool (*)(void*, const char*, const char*, void (*)(const char*, void*), void*);
    using GetModelInfoFn = const char* (*)(void*);
    using GetMemoryFn = long long (*)(void*);
    using WarmupFn = bool (*)(void*);
    using SetParamsFn = bool (*)(void*, float, int, float);
    using CancelFn = void (*)(void*);
    using ProgressFn = float (*)(void*);

    CreateEngineFn create_engine = nullptr;
    DestroyEngineFn destroy_engine = nullptr;
    RunVisionFn run_vision = nullptr;
    RunTextFn run_text = nullptr;
    GetModelInfoFn get_model_info = nullptr;
    GetMemoryFn get_memory = nullptr;
    WarmupFn warmup = nullptr;
    SetParamsFn set_params = nullptr;
    CancelFn cancel = nullptr;
    ProgressFn progress = nullptr;
};

struct ModelContext {
    void* runtime_handle = nullptr;
    void* tvm_handle = nullptr;
    void* relax_handle = nullptr;
    void* engine = nullptr;
    RuntimeFunctions fn{};
    std::string model_dir;
    std::string config_path;
    std::string tokenizer_path;
    std::string metadata_json;
    std::atomic<bool> inference_running{false};
    std::atomic<float> last_progress{0.0f};
    float last_temperature = 0.0f;
    int last_max_tokens = 0;
    float last_top_p = 0.0f;
};

std::mutex g_mutex;
std::unordered_map<jlong, ModelContext*> g_sessions;
jlong g_next_handle = 1;

std::string ReadFileToString(const std::string& path) {
    std::ifstream file(path, std::ios::in | std::ios::binary);
    if (!file.is_open()) {
        return {};
    }
    std::ostringstream oss;
    oss << file.rdbuf();
    return oss.str();
}

std::string EscapeJson(const std::string& value) {
    std::ostringstream oss;
    for (char c : value) {
        switch (c) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\n': oss << "\\n"; break;
            case '\r': oss << "\\r"; break;
            case '\t': oss << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    oss << "\\u";
                    constexpr char hex[] = "0123456789ABCDEF";
                    oss << hex[(c >> 12) & 0xF]
                        << hex[(c >> 8) & 0xF]
                        << hex[(c >> 4) & 0xF]
                        << hex[c & 0xF];
                } else {
                    oss << c;
                }
        }
    }
    return oss.str();
}

std::string BuildMetadataJson(const ModelContext& ctx, const std::string& config_payload, size_t tokenizer_size) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"modelDir\":\"" << EscapeJson(ctx.model_dir) << "\",";
    oss << "\"configPath\":\"" << EscapeJson(ctx.config_path) << "\",";
    oss << "\"tokenizerPath\":\"" << EscapeJson(ctx.tokenizer_path) << "\",";
    oss << "\"configBytes\":" << config_payload.size() << ",";
    oss << "\"tokenizerBytes\":" << tokenizer_size << ",";
    oss << "\"status\":\"MLC_RUNTIME_INITIALIZED\"";
    if (ctx.fn.get_model_info && ctx.engine) {
        const char* runtime_info = ctx.fn.get_model_info(ctx.engine);
        if (runtime_info && std::strlen(runtime_info) > 0) {
            oss << ",\"runtime\":" << runtime_info;
        }
    }
    oss << "}";
    return oss.str();
}

void CloseLibraryHandle(void*& handle) {
    if (handle) {
        dlclose(handle);
        handle = nullptr;
    }
}

template <typename T>
T LoadSymbol(void* handle, const char* primary, const char* secondary = nullptr) {
    if (!handle) {
        return nullptr;
    }
    void* symbol = dlsym(handle, primary);
    if (!symbol && secondary) {
        symbol = dlsym(handle, secondary);
    }
    return reinterpret_cast<T>(symbol);
}

bool LoadRuntime(ModelContext& ctx) {
    ctx.runtime_handle = dlopen("libmlc_llm_runtime.so", RTLD_NOW | RTLD_LOCAL);
    if (!ctx.runtime_handle) {
        LOGE("Failed to load libmlc_llm_runtime.so: %s", dlerror());
        return false;
    }

    ctx.tvm_handle = dlopen("libtvm_runtime.so", RTLD_NOW | RTLD_LOCAL);
    if (!ctx.tvm_handle) {
        LOGW("libtvm_runtime.so not loaded: %s", dlerror());
    }

    ctx.relax_handle = dlopen("librelax_runtime.so", RTLD_NOW | RTLD_LOCAL);
    if (!ctx.relax_handle) {
        LOGW("librelax_runtime.so not loaded: %s", dlerror());
    }

    ctx.fn.create_engine = LoadSymbol<RuntimeFunctions::CreateEngineFn>(ctx.runtime_handle, "CreateMLCEngine", "CreateQwenEngine");
    ctx.fn.destroy_engine = LoadSymbol<RuntimeFunctions::DestroyEngineFn>(ctx.runtime_handle, "DestroyMLCEngine", "DestroyQwenEngine");
    ctx.fn.run_vision = LoadSymbol<RuntimeFunctions::RunVisionFn>(ctx.runtime_handle, "RunVisionInference", "RunInferenceVision");
    ctx.fn.run_text = LoadSymbol<RuntimeFunctions::RunTextFn>(ctx.runtime_handle, "RunTextInference", "RunInferenceText");
    ctx.fn.get_model_info = LoadSymbol<RuntimeFunctions::GetModelInfoFn>(ctx.runtime_handle, "GetModelInfo", "GetQwenModelInfo");
    ctx.fn.get_memory = LoadSymbol<RuntimeFunctions::GetMemoryFn>(ctx.runtime_handle, "GetMemoryUsage", "QueryMemoryUsage");
    ctx.fn.warmup = LoadSymbol<RuntimeFunctions::WarmupFn>(ctx.runtime_handle, "Warmup", "WarmupModel");
    ctx.fn.set_params = LoadSymbol<RuntimeFunctions::SetParamsFn>(ctx.runtime_handle, "SetInferenceParams", "ConfigureSampler");
    ctx.fn.cancel = LoadSymbol<RuntimeFunctions::CancelFn>(ctx.runtime_handle, "CancelInference", "Cancel");
    ctx.fn.progress = LoadSymbol<RuntimeFunctions::ProgressFn>(ctx.runtime_handle, "GetInferenceProgress", "Progress");

    if (!ctx.fn.create_engine || !ctx.fn.run_vision) {
        LOGE("Required runtime exports missing: create=%p runVision=%p", ctx.fn.create_engine, ctx.fn.run_vision);
        return false;
    }

    return true;
}

struct StreamAggregator {
    std::ostringstream buffer;
    static void Append(const char* token, void* user_data) {
        if (!user_data || !token) {
            return;
        }
        auto* self = reinterpret_cast<StreamAggregator*>(user_data);
        self->buffer << token;
    }
};

// Fallback payload returned when native inference fails.
// Must contain ONLY the seven canonical coupon-grammar keys, in canonical
// order, with no diagnostic metadata. Diagnostics belong in android_log output,
// not in the JSON payload — the JVM parser treats unknown keys as contract
// drift and we don't want to rely on key stripping to hide it.
//
// Any change here must be mirrored in JniFallbackFixtures.CANONICAL_FALLBACK_JSON.
std::string BuildFallbackResponse() {
    std::ostringstream oss;
    oss << "{";
    oss << "\"storeName\":\"unknown\",";
    oss << "\"description\":\"unknown\",";
    oss << "\"redeemCode\":\"unknown\",";
    oss << "\"expiryDate\":\"unknown\",";
    oss << "\"storeNameSource\":\"fallback\",";
    oss << "\"storeNameEvidence\":[],";
    oss << "\"needsAttention\":true";
    oss << "}";
    return oss.str();
}

std::string JStringToString(JNIEnv* env, jstring value) {
    if (!value) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_initializeModel(
        JNIEnv* env, jobject /*thiz*/, jstring jModelDir, jstring jConfigPath) {
    const std::string model_dir = JStringToString(env, jModelDir);
    const std::string config_path = JStringToString(env, jConfigPath);
    const std::string tokenizer_path = model_dir + "/tokenizer.json";

    LOGI("Initializing MLC runtime");
    LOGI("  model_dir=%s", model_dir.c_str());
    LOGI("  config=%s", config_path.c_str());
    LOGI("  tokenizer=%s", tokenizer_path.c_str());

    const std::string config_payload = ReadFileToString(config_path);
    if (config_payload.empty()) {
        LOGE("Config file missing or empty: %s", config_path.c_str());
        return 0;
    }
    std::ifstream tokenizer_file(tokenizer_path, std::ios::binary);
    if (!tokenizer_file.good()) {
        LOGE("Tokenizer missing: %s", tokenizer_path.c_str());
        return 0;
    }
    tokenizer_file.seekg(0, std::ios::end);
    const size_t tokenizer_size = static_cast<size_t>(tokenizer_file.tellg());
    tokenizer_file.close();

    auto* ctx = new ModelContext();
    ctx->model_dir = model_dir;
    ctx->config_path = config_path;
    ctx->tokenizer_path = tokenizer_path;

    if (!LoadRuntime(*ctx)) {
        LOGE("Failed to load runtime libraries");
        delete ctx;
        return 0;
    }

    ctx->engine = ctx->fn.create_engine(model_dir.c_str(), config_path.c_str(), tokenizer_path.c_str());
    if (!ctx->engine) {
        LOGE("CreateEngine returned null");
        CloseLibraryHandle(ctx->runtime_handle);
        CloseLibraryHandle(ctx->tvm_handle);
        CloseLibraryHandle(ctx->relax_handle);
        delete ctx;
        return 0;
    }

    ctx->metadata_json = BuildMetadataJson(*ctx, config_payload, tokenizer_size);
    ctx->last_temperature = 0.1f;
    ctx->last_max_tokens = 512;
    ctx->last_top_p = 0.9f;

    if (ctx->fn.warmup) {
        ctx->fn.warmup(ctx->engine);
    }

    jlong handle;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        handle = g_next_handle++;
        g_sessions[handle] = ctx;
    }

    LOGI("MLC runtime initialized (handle=%lld)", static_cast<long long>(handle));
    return handle;
}

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runTextInference(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jOcrText, jstring jPrompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("runTextInference invalid handle=%lld", static_cast<long long>(handle));
        return nullptr;
    }

    ModelContext* ctx = it->second;
    const std::string prompt = JStringToString(env, jPrompt);
    const std::string ocr = JStringToString(env, jOcrText);

    ctx->inference_running.store(true);
    ctx->last_progress.store(0.05f);

    StreamAggregator aggregator;
    bool success = false;
    if (ctx->fn.run_text) {
        success = ctx->fn.run_text(
                ctx->engine,
                ocr.c_str(),
                prompt.c_str(),
                &StreamAggregator::Append,
                &aggregator);
    }

    ctx->inference_running.store(false);
    ctx->last_progress.store(1.0f);

    if (!success) {
        LOGW("runTextInference: engine reported failure; returning schema-pure fallback (prompt prefix=\"%.160s\")",
             prompt.c_str());
        const std::string fallback = BuildFallbackResponse();
        return env->NewStringUTF(fallback.c_str());
    }

    const std::string result = aggregator.buffer.str();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runVisionInference(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray jImageData,
        jint width, jint height, jstring jPrompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("runVisionInference invalid handle=%lld", static_cast<long long>(handle));
        return nullptr;
    }

    ModelContext* ctx = it->second;
    const std::string prompt = JStringToString(env, jPrompt);

    jsize length = env->GetArrayLength(jImageData);
    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(jImageData, 0, length, reinterpret_cast<jbyte*>(buffer.data()));

    ctx->inference_running.store(true);
    ctx->last_progress.store(0.05f);

    StreamAggregator aggregator;
    bool success = ctx->fn.run_vision(
            ctx->engine,
            buffer.data(),
            static_cast<int>(width),
            static_cast<int>(height),
            prompt.c_str(),
            &StreamAggregator::Append,
            &aggregator);

    ctx->inference_running.store(false);
    ctx->last_progress.store(1.0f);

    if (!success) {
        LOGW("runVisionInference: engine reported failure; returning schema-pure fallback (width=%d height=%d prompt prefix=\"%.160s\")",
             static_cast<int>(width), static_cast<int>(height), prompt.c_str());
        const std::string fallback = BuildFallbackResponse();
        return env->NewStringUTF(fallback.c_str());
    }

    const std::string result = aggregator.buffer.str();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getModelInfo(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("getModelInfo invalid handle=%lld", static_cast<long long>(handle));
        return nullptr;
    }

    return env->NewStringUTF(it->second->metadata_json.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getMemoryUsage(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("getMemoryUsage invalid handle=%lld", static_cast<long long>(handle));
        return -1;
    }
    ModelContext* ctx = it->second;
    if (ctx->fn.get_memory && ctx->engine) {
        return static_cast<jlong>(ctx->fn.get_memory(ctx->engine));
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_warmupModel(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("warmupModel invalid handle=%lld", static_cast<long long>(handle));
        return JNI_FALSE;
    }
    ModelContext* ctx = it->second;
    if (ctx->fn.warmup && ctx->engine) {
        return ctx->fn.warmup(ctx->engine) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_setInferenceParams(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloat temperature, jint maxTokens, jfloat topP) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        LOGE("setInferenceParams invalid handle=%lld", static_cast<long long>(handle));
        return JNI_FALSE;
    }
    ModelContext* ctx = it->second;
    ctx->last_temperature = temperature;
    ctx->last_max_tokens = maxTokens;
    ctx->last_top_p = topP;
    if (ctx->fn.set_params && ctx->engine) {
        return ctx->fn.set_params(ctx->engine, temperature, maxTokens, topP) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_cancelInference(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        return;
    }
    ModelContext* ctx = it->second;
    if (ctx->fn.cancel && ctx->engine) {
        ctx->fn.cancel(ctx->engine);
    }
    ctx->inference_running.store(false);
    ctx->last_progress.store(0.0f);
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_isInferenceRunning(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        return JNI_FALSE;
    }
    return it->second->inference_running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getInferenceProgress(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        return 0.0f;
    }
    ModelContext* ctx = it->second;
    if (ctx->fn.progress && ctx->engine) {
        return ctx->fn.progress(ctx->engine);
    }
    return ctx->last_progress.load();
}

JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_releaseModel(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    ModelContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) {
            return;
        }
        ctx = it->second;
        g_sessions.erase(it);
    }

    if (!ctx) {
        return;
    }

    if (ctx->fn.destroy_engine && ctx->engine) {
        ctx->fn.destroy_engine(ctx->engine);
    }
    CloseLibraryHandle(ctx->runtime_handle);
    CloseLibraryHandle(ctx->tvm_handle);
    CloseLibraryHandle(ctx->relax_handle);
    delete ctx;
}

JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_loadLibrary(
        JNIEnv* env, jclass /*clazz*/, jobject /*context*/) {
    (void)env;
    return JNI_TRUE;
}

}  // extern "C"
