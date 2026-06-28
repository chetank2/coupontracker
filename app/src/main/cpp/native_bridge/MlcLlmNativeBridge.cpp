#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include "../include/bridge_api.hpp"

#define LOG_TAG "MlcLlmNativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * JNI Bridge for MiniCPM Vision Inference
 * 
 * This bridge implements the MlcLlmNative interface that your Kotlin code
 * already uses. It provides a backend-agnostic layer that can use either:
 * - MLC-LLM (vision-capable, recommended)
 * - llama.cpp (text-only for now, vision TODO)
 * 
 * Choose backend at compile time with CMake flags:
 * - -DUSE_MLC=ON  (default, vision support)
 * - -DUSE_LLAMA=ON (text-only for now)
 * 
 * The bridge maintains a handle-to-session map for proper lifecycle management.
 */

// Choose backend at compile time
#if defined(USE_MLC)
#include "MlcLlmBridge_MLC.cpp"
#define BACKEND_NAME "MLC-LLM"
#elif defined(USE_LLAMA)
#include "MlcLlmBridge_Llama.cpp"
#define BACKEND_NAME "llama.cpp"
#else
#error "Define either USE_MLC or USE_LLAMA"
#endif

// Session management
static std::atomic<long> g_next_handle{1};
static std::mutex g_map_mtx;
static std::unordered_map<long, std::unique_ptr<BridgeSession>> g_sessions;

extern "C" {

/**
 * Initialize model
 * 
 * @param jModelDir Path to model directory (filesDir/models/minicpm)
 * @param jConfigPath Path to config file (mlc-chat-config.json)
 * @return Handle (>0 on success, 0 on failure)
 */
JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_initializeModel(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelDir, jstring jConfigPath) {

    const char* modelDir = env->GetStringUTFChars(jModelDir, nullptr);
    const char* configPath = env->GetStringUTFChars(jConfigPath, nullptr);

    LOGI("==============================================");
    LOGI("MlcLlmNative.initializeModel()");
    LOGI("  Backend: %s", BACKEND_NAME);
    LOGI("  Model dir: %s", modelDir);
    LOGI("  Config: %s", configPath);
    LOGI("==============================================");

    // Create new session
    auto session = std::make_unique<BridgeSession>();
    BridgeStatus st = bridge_initialize(*session, modelDir, configPath);

    env->ReleaseStringUTFChars(jModelDir, modelDir);
    env->ReleaseStringUTFChars(jConfigPath, configPath);

    if (st != BridgeStatus::OK) {
        LOGE("❌ bridge_initialize failed (status=%d)", (int)st);
        return 0L;
    }

    // Assign handle and store session
    long handle = g_next_handle.fetch_add(1);
    {
        std::lock_guard<std::mutex> lk(g_map_mtx);
        g_sessions.emplace(handle, std::move(session));
    }
    
    LOGI("✅ Model initialized (handle=%ld)", handle);
    return (jlong)handle;
}

/**
 * Run vision inference
 * 
 * @param handle Model handle from initializeModel
 * @param jImageData JPEG/PNG encoded image bytes (will be decoded to RGB)
 * @param jPrompt Text prompt for extraction
 * @return JSON string with extracted fields
 */
JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runVisionInference(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle,
        jbyteArray jImageData,
        jstring jPrompt) {

    // Find session
    std::unique_ptr<BridgeSession>* pSess = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_map_mtx);
        auto it = g_sessions.find((long)handle);
        if (it != g_sessions.end()) pSess = &it->second;
    }
    
    if (!pSess || !(*pSess)) {
        LOGE("❌ Invalid handle: %lld", (long long)handle);
        return nullptr;
    }

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    
    LOGI("runVisionInference(handle=%lld)", (long long)handle);
    LOGI("  Prompt: %.100s...", prompt);

    // Get image data
    jsize imageSize = env->GetArrayLength(jImageData);
    std::vector<jbyte> imageBytes(imageSize);
    env->GetByteArrayRegion(jImageData, 0, imageSize, imageBytes.data());
    
    LOGI("  Image size: %d bytes", imageSize);

    // TODO: Decode JPEG/PNG to RGB
    // For now, assume it's already RGB (this needs proper image decoding)
    // You can use stb_image or Android's BitmapFactory
    
    // Placeholder: assume 512x512 RGB for now
    int width = 512;
    int height = 512;
    std::vector<uint8_t> rgb(width * height * 3, 128); // Gray placeholder
    
    LOGW("⚠️ Image decoding not implemented - using placeholder RGB");
    LOGW("⚠️ TODO: Decode JPEG/PNG to RGB using stb_image or BitmapFactory");

    // Run inference
    std::string outJson;
    BridgeStatus st = bridge_run_vision(
            *(*pSess),
            rgb.data(),
            (int)rgb.size(),  // nBytes
            width,
            height,
            prompt,
            0.7f,  // temperature
            512,   // maxTokens
            outJson
    );

    env->ReleaseStringUTFChars(jPrompt, prompt);

    if (st != BridgeStatus::OK) {
        LOGW("⚠️ bridge_run_vision returned status=%d", (int)st);
        return nullptr;
    }
    
    LOGI("✅ Inference complete (%zu bytes)", outJson.size());
    return env->NewStringUTF(outJson.c_str());
}

/**
 * Warmup model (prepare for inference)
 */
JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_warmupModel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    LOGI("warmupModel(handle=%lld)", (long long)handle);
    // Warmup is already done in bridge_initialize
}

/**
 * Set inference parameters
 */
JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_setInferenceParams(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle, jfloat temperature, jint maxTokens, jfloat topP) {
    LOGI("setInferenceParams(handle=%lld, temp=%.2f, max_tokens=%d, top_p=%.2f)",
         (long long)handle, temperature, maxTokens, topP);
    // TODO: Store these in session and use in runVisionInference
}

/**
 * Get memory statistics
 * 
 * @return int[3] array: [used_mb, peak_mb, total_mb]
 */
JNIEXPORT jintArray JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_getMemoryStats(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    int stats[3] = {0, 0, 0};
    
    std::unique_ptr<BridgeSession>* pSess = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_map_mtx);
        auto it = g_sessions.find((long)handle);
        if (it != g_sessions.end()) pSess = &it->second;
    }
    
    if (pSess && (*pSess)) {
        bridge_get_mem(*(*pSess), stats);
    }

    jintArray jarr = env->NewIntArray(3);
    env->SetIntArrayRegion(jarr, 0, 3, stats);
    return jarr;
}

/**
 * Release model and free resources
 */
JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_releaseModel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    LOGI("releaseModel(handle=%lld)", (long long)handle);
    
    std::lock_guard<std::mutex> lk(g_map_mtx);
    auto it = g_sessions.find((long)handle);
    if (it != g_sessions.end()) {
        // Destructor of unique_ptr will call bridge cleanup
        g_sessions.erase(it);
        LOGI("✅ Session released");
    } else {
        LOGW("⚠️ Handle not found: %lld", (long long)handle);
    }
}

/**
 * Load native library (compatibility method)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_loadLibrary(
        JNIEnv* /*env*/, jclass /*clazz*/, jobject /*context*/) {
    LOGI("loadLibrary() called");
    return JNI_TRUE;
}

/**
 * JNI_OnLoad - called when library is loaded
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    LOGI("==============================================");
    LOGI("MLC-LLM Native Bridge Loaded");
    LOGI("  Version: 1.0.0");
    LOGI("  Backend: %s", BACKEND_NAME);
    LOGI("  Model: MiniCPM-Llama3-V-2.5");
    LOGI("==============================================");
    
    return JNI_VERSION_1_6;
}

/**
 * JNI_OnUnload - called when library is unloaded
 */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    
    LOGI("MLC-LLM Native Bridge Unloading");
    
    // Clean up all sessions
    std::lock_guard<std::mutex> lk(g_map_mtx);
    g_sessions.clear();
    
    LOGI("✅ All sessions cleaned up");
}

} // extern "C"
