#include "../include/bridge_api.hpp"
#include <android/log.h>
#include <string>

/**
 * llama.cpp Backend Implementation
 * 
 * This backend uses llama.cpp for text-only inference.
 * Vision support requires either:
 * 1. llama.cpp vision branch (LLaVA/CLIP integration)
 * 2. External vision encoder → tokens → llama.cpp
 * 
 * Current status: TEXT-ONLY
 * For production vision inference, use MLC backend instead.
 */

// Include llama.cpp headers (when you add the library)
// #include "llama.h"

// LOG_TAG, LOGI, LOGW, LOGE already defined in MlcLlmNativeBridge.cpp
// Just reuse them

/**
 * Stub structures (replace with real llama.cpp when linking)
 */
struct llama_model {};
struct llama_context {};
struct llama_model_params {};
struct llama_context_params {};
struct llama_batch {};
typedef int llama_token;

struct LlamaImpl {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

/**
 * Initialize llama.cpp runtime
 * 
 * NOTE: This is a STUB implementation.
 * To use llama.cpp:
 * 1. Build libllama.so for Android
 * 2. Place in app/src/main/jniLibs/arm64-v8a/
 * 3. Uncomment llama.h include above
 * 4. Replace stub functions with real llama.cpp calls
 */
BridgeStatus bridge_initialize(BridgeSession& s, const char* modelDir, const char* cfgPath) {
    (void)cfgPath;
    
    LOGI("Initializing llama.cpp from: %s", modelDir);
    LOGW("⚠️ llama.cpp backend is STUB-ONLY");
    LOGW("⚠️ For real inference, build libllama.so and link it");
    LOGW("⚠️ For vision inference, use MLC backend instead");
    
    std::string modelPath = std::string(modelDir) + "/ggml-model-Q4_K_M.gguf";
    
    // TODO: Uncomment when llama.cpp is available
    /*
    llama_backend_init(false);
    
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only on mobile
    
    auto* model = llama_load_model_from_file(modelPath.c_str(), mp);
    if (!model) {
        LOGE("Failed to load model: %s", modelPath.c_str());
        return BridgeStatus::MODEL_MISSING;
    }
    
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_threads = 4;
    
    auto* ctx = llama_new_context_with_model(model, cp);
    if (!ctx) {
        llama_free_model(model);
        return BridgeStatus::INIT_ERROR;
    }
    
    auto* impl = new LlamaImpl();
    impl->model = model;
    impl->ctx = ctx;
    s.impl = impl;
    
    LOGI("✅ llama.cpp initialized");
    */
    
    // Stub: return error for now
    LOGE("llama.cpp not linked - this is a stub");
    return BridgeStatus::RUNTIME_MISSING;
}

/**
 * Run vision inference (STUB - TEXT ONLY)
 * 
 * NOTE: This is a stub that ignores the image input.
 * Real vision support requires:
 * - LLaVA/CLIP integration in llama.cpp
 * - Or external vision encoder
 */
BridgeStatus bridge_run_vision(BridgeSession& s,
                               const uint8_t* rgb, int nBytes, int w, int h,
                               const char* prompt, float temp, int maxTokens,
                               std::string& outJson) {
    (void)rgb; (void)nBytes; (void)w; (void)h; (void)temp;
    
    LOGW("⚠️ llama.cpp vision inference is STUB-ONLY");
    LOGW("⚠️ Image input (%dx%d) is IGNORED", w, h);
    LOGW("⚠️ Running text-only inference on prompt");
    
    auto* impl = (LlamaImpl*)s.impl;
    if (!impl || !impl->ctx || !impl->model) {
        return BridgeStatus::BAD_PARAMS;
    }
    
    // TODO: Uncomment when llama.cpp is available
    /*
    // Tokenize prompt
    std::vector<llama_token> toks(4096);
    int n = llama_tokenize(impl->model, prompt, (int)strlen(prompt),
                          toks.data(), (int)toks.size(), true, false);
    toks.resize(n);
    
    if (n <= 0) {
        LOGE("Tokenization failed");
        return BridgeStatus::BAD_PARAMS;
    }
    
    // Decode
    llama_batch batch = llama_batch_get_one(toks.data(), n, 0, 0);
    if (llama_decode(impl->ctx, batch) != 0) {
        LOGE("Decode failed");
        return BridgeStatus::INFER_ERROR;
    }
    
    // Generate
    std::string out;
    const int max_out = std::max(16, maxTokens);
    
    for (int i = 0; i < max_out; ++i) {
        llama_token id = llama_sample_token_greedy(impl->ctx, nullptr);
        if (id == llama_token_eos(impl->model)) break;
        
        char buf[256];
        int m = llama_token_to_piece(impl->model, id, buf, sizeof(buf));
        if (m > 0) out.append(buf, m);
        
        llama_batch b = llama_batch_get_one(&id, 1, n + i, 0);
        if (llama_decode(impl->ctx, b) != 0) break;
    }
    
    // Return as raw text (Kotlin will wrap in JSON)
    outJson = out;
    LOGI("Generated: %.100s...", out.c_str());
    */
    
    // Stub response
    outJson = "{\"error\":\"llama.cpp not linked\",\"status\":\"stub_only\"}";
    return BridgeStatus::RUNTIME_MISSING;
}

/**
 * Get memory statistics (STUB)
 */
void bridge_get_mem(BridgeSession& s, int out[3]) {
    (void)s;
    out[0] = out[1] = out[2] = 0;
    // TODO: Query llama.cpp memory stats when available
}

/**
 * Cleanup on session destruction (STUB)
 */
static void destroy(BridgeSession& s) {
    auto* impl = (LlamaImpl*)s.impl;
    if (!impl) return;
    
    LOGI("Releasing llama.cpp runtime");
    
    // TODO: Uncomment when llama.cpp is available
    /*
    if (impl->ctx) llama_free(impl->ctx);
    if (impl->model) llama_free_model(impl->model);
    */
    
    delete impl;
    s.impl = nullptr;
}

/**
 * Called when library is unloaded
 */
__attribute__((destructor))
static void on_unload() {
    // TODO: Uncomment when llama.cpp is available
    // llama_backend_free();
    LOGI("llama.cpp bridge unloading");
}

