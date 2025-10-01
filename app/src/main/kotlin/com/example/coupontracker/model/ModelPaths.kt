package com.example.coupontracker.model

import android.content.Context
import java.io.File

/**
 * Central constants for model storage paths
 * All model files are stored under filesDir/models/
 * 
 * UPDATED FOR GGUF: Now supports both legacy MLC format and new GGUF format
 */
object ModelPaths {
    const val MODELS_ROOT = "models"
    const val MODEL_ID = "minicpm_llama3_v25_q4"
    
    /**
     * Get root models directory
     */
    fun root(context: Context): File = File(context.filesDir, MODELS_ROOT)
    
    /**
     * Get specific model directory
     */
    fun modelDir(context: Context, modelId: String = MODEL_ID): File = 
        File(root(context), modelId)
    
    /**
     * Required files for GGUF model installation (MiniCPM-V-2.6 from Hugging Face)
     * This is the NEW format from real HF downloads
     */
    val REQUIRED_FILES_GGUF = listOf(
        "ggml-model-Q4_K_M.gguf"  // Main GGUF weight file (~4.7GB)
    )
    
    /**
     * Optional files for GGUF model (if included in download)
     */
    val OPTIONAL_FILES_GGUF = listOf(
        "tokenizer.json",         // Tokenizer config
        "config.json"             // Model config
    )
    
    /**
     * Legacy required files for old MLC format (kept for backwards compatibility)
     * Only used if GGUF files are not present
     */
    val REQUIRED_FILES_LEGACY = listOf(
        "mlc-chat-config.json",
        "tokenizer.json",
        "vision_config.json",
        "params/ndarray-cache.json",
        "weights/model.bin",
        "tokenizer/tokenizer.model"
    )
    
    /**
     * Get required files based on what's in the directory
     * Prefers GGUF format if present
     */
    fun getRequiredFiles(modelDir: File): List<String> {
        // Check if GGUF model exists
        val ggufFile = File(modelDir, REQUIRED_FILES_GGUF[0])
        return if (ggufFile.exists()) {
            REQUIRED_FILES_GGUF
        } else {
            REQUIRED_FILES_LEGACY
        }
    }
    
    /**
     * Minimum file sizes for GGUF format
     */
    val MIN_FILE_SIZES_GGUF = mapOf(
        "ggml-model-Q4_K_M.gguf" to 3_500_000_000L,  // >= 3.5 GB (reject mocks)
        "tokenizer.json" to 100_000L,                 // >= 100 KB (if present)
        "config.json" to 500L                         // >= 500 B (if present)
    )
    
    /**
     * Minimum file sizes for legacy format
     */
    val MIN_FILE_SIZES_LEGACY = mapOf(
        "weights/model.bin" to 1_500_000_000L,      // >= 1.5 GB
        "tokenizer/tokenizer.model" to 200_000L,    // >= 200 KB
        "params/ndarray-cache.json" to 1_000L,      // >= 1 KB
        "mlc-chat-config.json" to 1_000L,           // >= 1 KB
        "tokenizer.json" to 1_000_000L,             // >= 1 MB
        "vision_config.json" to 1_000L              // >= 1 KB
    )
    
    /**
     * Get minimum file sizes based on what's in the directory
     */
    fun getMinFileSizes(modelDir: File): Map<String, Long> {
        val ggufFile = File(modelDir, REQUIRED_FILES_GGUF[0])
        return if (ggufFile.exists()) {
            MIN_FILE_SIZES_GGUF
        } else {
            MIN_FILE_SIZES_LEGACY
        }
    }
    
    /**
     * Check if model is GGUF format
     */
    fun isGgufModel(modelDir: File): Boolean {
        return File(modelDir, REQUIRED_FILES_GGUF[0]).exists()
    }
}

