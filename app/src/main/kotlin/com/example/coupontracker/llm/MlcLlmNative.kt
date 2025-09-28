package com.example.coupontracker.llm

import android.util.Log

/**
 * JNI interface for MLC-LLM native library
 * Provides low-level access to MiniCPM-Llama3-V2.5 inference
 */
class MlcLlmNative {
    
    companion object {
        private const val TAG = "MlcLlmNative"
        private const val LIBRARY_NAME = "mlc_llm_android"
        
        @Volatile
        private var isLibraryLoaded = false
        
        /**
         * Load the native MLC-LLM library
         */
        fun loadLibrary(): Boolean {
            if (isLibraryLoaded) return true
            
            return try {
                System.loadLibrary(LIBRARY_NAME)
                isLibraryLoaded = true
                Log.i(TAG, "MLC-LLM native library loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load MLC-LLM native library", e)
                false
            }
        }
        
        /**
         * Check if the native library is available
         */
        fun isAvailable(): Boolean = isLibraryLoaded
    }
    
    // Native method declarations - implemented in C++
    
    /**
     * Initialize the MLC-LLM runtime with model path
     * @param modelPath Path to the model directory
     * @param configPath Path to the model configuration
     * @return Handle to the initialized model (0 if failed)
     */
    external fun initializeModel(modelPath: String, configPath: String): Long
    
    /**
     * Run inference on the model with vision input
     * @param modelHandle Handle returned from initializeModel
     * @param imageData RGB image data as byte array
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param prompt Text prompt for the model
     * @return Generated response text
     */
    external fun runVisionInference(
        modelHandle: Long,
        imageData: ByteArray,
        width: Int,
        height: Int,
        prompt: String
    ): String?
    
    /**
     * Get model information
     * @param modelHandle Handle to the model
     * @return JSON string with model info (name, version, parameters, etc.)
     */
    external fun getModelInfo(modelHandle: Long): String?
    
    /**
     * Get current memory usage of the model
     * @param modelHandle Handle to the model
     * @return Memory usage in bytes
     */
    external fun getMemoryUsage(modelHandle: Long): Long
    
    /**
     * Warm up the model for faster inference
     * @param modelHandle Handle to the model
     * @return True if warmup successful
     */
    external fun warmupModel(modelHandle: Long): Boolean
    
    /**
     * Release model resources
     * @param modelHandle Handle to the model
     */
    external fun releaseModel(modelHandle: Long)
    
    /**
     * Set inference parameters
     * @param modelHandle Handle to the model
     * @param temperature Sampling temperature (0.0 to 1.0)
     * @param maxTokens Maximum tokens to generate
     * @param topP Top-p sampling parameter
     * @return True if parameters set successfully
     */
    external fun setInferenceParams(
        modelHandle: Long,
        temperature: Float,
        maxTokens: Int,
        topP: Float
    ): Boolean
    
    /**
     * Cancel ongoing inference
     * @param modelHandle Handle to the model
     */
    external fun cancelInference(modelHandle: Long)
    
    /**
     * Check if model is currently running inference
     * @param modelHandle Handle to the model
     * @return True if inference is running
     */
    external fun isInferenceRunning(modelHandle: Long): Boolean
    
    /**
     * Get inference progress (0.0 to 1.0)
     * @param modelHandle Handle to the model
     * @return Progress as float, -1.0 if not available
     */
    external fun getInferenceProgress(modelHandle: Long): Float
}
