#pragma once
#include <string>
#include <vector>

/**
 * Bridge API for MiniCPM Vision Inference
 * 
 * This provides a clean abstraction layer between JNI and the actual
 * inference backend (MLC-LLM or llama.cpp).
 */

enum class BridgeStatus {
    OK = 0,
    INIT_ERROR,
    RUNTIME_MISSING,
    MODEL_MISSING,
    BAD_PARAMS,
    INFER_ERROR,
};

struct BridgeSession {
    // Opaque fields per-backend:
    void* impl = nullptr;
};

/**
 * Initialize bridge with model directory and config path
 * @param s Session to initialize
 * @param modelDir Path to model directory (e.g., filesDir/models/minicpm)
 * @param cfgPath Path to config file (e.g., mlc-chat-config.json)
 * @return BridgeStatus::OK on success
 */
BridgeStatus bridge_initialize(BridgeSession& s, const char* modelDir, const char* cfgPath);

/**
 * Run vision inference on RGB image
 * @param s Initialized session
 * @param rgb RGB bytes (width * height * 3)
 * @param nBytes Size of RGB buffer
 * @param width Image width
 * @param height Image height
 * @param prompt Text prompt for extraction
 * @param temperature Sampling temperature (0.0-1.0)
 * @param maxTokens Maximum tokens to generate
 * @param outJson Output JSON string
 * @return BridgeStatus::OK on success
 */
BridgeStatus bridge_run_vision(BridgeSession& s,
                               const uint8_t* rgb, int nBytes, int width, int height,
                               const char* prompt, float temperature, int maxTokens,
                               std::string& outJson);

/**
 * Get memory statistics
 * @param s Session
 * @param out Array of 3 ints: [used_mb, peak_mb, total_mb]
 */
void bridge_get_mem(BridgeSession& s, int out[3]);

