#include "../include/bridge_api.hpp"
#include "VisionPreproc.hpp"
#include <dlfcn.h>
#include <android/log.h>
#include <string>

// LOG_TAG, LOGI, LOGW, LOGE already defined in MlcLlmNativeBridge.cpp
// Just reuse them

/**
 * MLC-LLM Backend Implementation
 * 
 * This backend loads the MLC-LLM runtime dynamically from the model directory.
 * The runtime .so is placed by the model import/download flow.
 * 
 * Expected runtime exports:
 * - mlc_init(model_dir, cfg_path, n_threads) -> bool
 * - mlc_warmup() -> bool
 * - mlc_infer_vision(rgb, w, h, prompt, temp, max_tokens) -> const char*
 * - mlc_get_mem(int stats[3]) -> void
 * - mlc_release() -> void
 */

struct MlcApi {
    // Runtime API function pointers
    bool (*init)(const char* model_dir, const char* cfg_path, int n_threads) = nullptr;
    bool (*warmup)() = nullptr;
    // Vision inference: returns JSON string (buffer owned by runtime)
    const char* (*infer_vision)(const uint8_t* rgb, int w, int h,
                                const char* prompt, float temp, int max_tokens) = nullptr;
    void (*get_mem)(int stats[3]) = nullptr;
    void (*release)() = nullptr;
};

struct MlcImpl {
    void* handle = nullptr;
    MlcApi api{};
    std::string model_dir;
};

/**
 * Load MLC-LLM runtime API from shared library
 */
static bool load_api(void* h, MlcApi& api) {
    api.init = (decltype(api.init)) dlsym(h, "mlc_init");
    api.warmup = (decltype(api.warmup)) dlsym(h, "mlc_warmup");
    api.infer_vision = (decltype(api.infer_vision)) dlsym(h, "mlc_infer_vision");
    api.get_mem = (decltype(api.get_mem)) dlsym(h, "mlc_get_mem");
    api.release = (decltype(api.release)) dlsym(h, "mlc_release");
    
    if (!api.init || !api.infer_vision || !api.release) {
        LOGE("Missing required exports: init=%p, infer=%p, release=%p",
             api.init, api.infer_vision, api.release);
        return false;
    }
    
    return true;
}

/**
 * Initialize MLC-LLM runtime
 * 
 * Loads the runtime .so from the model directory and calls mlc_init()
 */
BridgeStatus bridge_initialize(BridgeSession& s, const char* modelDir, const char* cfgPath) {
    LOGI("Initializing MLC runtime from: %s", modelDir);
    
    // Look for runtime in model directory
    // Try different possible locations:
    // 1. <modelDir>/runtime/arm64-v8a/minicpm_llm_q4f16_1.so
    // 2. <modelDir>/libmlc_llm.so (fallback)
    std::string soPath = std::string(modelDir) + "/runtime/arm64-v8a/minicpm_llm_q4f16_1.so";
    
    void* h = dlopen(soPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        // Try fallback location
        soPath = std::string(modelDir) + "/libmlc_llm.so";
        h = dlopen(soPath.c_str(), RTLD_NOW | RTLD_LOCAL);
        
        if (!h) {
            LOGE("dlopen failed for both paths: %s", dlerror());
            LOGE("  Tried: %s/runtime/arm64-v8a/minicpm_llm_q4f16_1.so", modelDir);
            LOGE("  Tried: %s/libmlc_llm.so", modelDir);
            return BridgeStatus::RUNTIME_MISSING;
        }
    }
    
    LOGI("Runtime loaded from: %s", soPath.c_str());
    
    // Create implementation
    auto impl = new MlcImpl();
    impl->handle = h;
    
    // Load API symbols
    if (!load_api(h, impl->api)) {
        LOGE("Failed to load MLC API symbols");
        dlclose(h);
        delete impl;
        return BridgeStatus::INIT_ERROR;
    }
    
    // Initialize runtime
    LOGI("Calling mlc_init(model_dir=%s, cfg=%s, threads=4)", modelDir, cfgPath);
    if (!impl->api.init(modelDir, cfgPath, /*threads*/4)) {
        LOGE("mlc_init() failed");
        dlclose(h);
        delete impl;
        return BridgeStatus::INIT_ERROR;
    }
    
    // Optional warmup
    if (impl->api.warmup) {
        LOGI("Warming up model...");
        impl->api.warmup();
    }
    
    impl->model_dir = modelDir;
    s.impl = impl;
    
    LOGI("✅ MLC runtime initialized successfully");
    return BridgeStatus::OK;
}

/**
 * Run vision inference
 * 
 * Preprocesses image (letterbox resize), then calls mlc_infer_vision()
 */
BridgeStatus bridge_run_vision(BridgeSession& s,
                               const uint8_t* rgb, int nBytes, int w, int h,
                               const char* prompt, float temp, int maxTokens,
                               std::string& outJson) {
    if (!s.impl) {
        LOGE("Invalid session (impl is null)");
        return BridgeStatus::BAD_PARAMS;
    }
    
    auto* impl = (MlcImpl*)s.impl;
    
    // Validate input
    if (!rgb || nBytes != w * h * 3) {
        LOGE("Invalid RGB input: bytes=%d, expected=%d (w=%d, h=%d)",
             nBytes, w * h * 3, w, h);
        return BridgeStatus::BAD_PARAMS;
    }
    
    LOGI("Running vision inference: %dx%d, prompt=%.50s...", w, h, prompt);
    
    // Preprocess: letterbox resize to model's expected input size
    // MiniCPM-V typically expects 768x768
    const int target_size = 768;
    std::vector<uint8_t> resized;
    int ow = 0, oh = 0;
    
    letterbox_rgb(rgb, w, h, target_size, resized, ow, oh);
    LOGI("Preprocessed: %dx%d -> %dx%d (letterboxed to %dx%d)",
         w, h, ow, oh, target_size, target_size);
    
    // Call MLC vision inference
    const char* resp = impl->api.infer_vision(
        resized.data(), target_size, target_size,
        prompt, temp, maxTokens
    );
    
    if (!resp) {
        LOGE("mlc_infer_vision() returned null");
        return BridgeStatus::INFER_ERROR;
    }
    
    outJson.assign(resp);
    LOGI("Inference complete: %zu bytes", outJson.size());
    
    return BridgeStatus::OK;
}

/**
 * Get memory statistics
 */
void bridge_get_mem(BridgeSession& s, int out[3]) {
    auto* impl = (MlcImpl*)s.impl;
    if (impl && impl->api.get_mem) {
        impl->api.get_mem(out);
    } else {
        out[0] = out[1] = out[2] = 0;
    }
}

/**
 * Called when library is unloaded
 */
__attribute__((destructor))
static void on_unload() {
    // Cleanup handled by higher-level releaseModel
    LOGI("MLC bridge unloading");
}

