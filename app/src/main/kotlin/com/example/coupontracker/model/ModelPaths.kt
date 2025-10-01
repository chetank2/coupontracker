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
     * Required files for GGUF vision model (MiniCPM-V-2.6)
     * Vision model requires BOTH base model and vision projector
     */
    val REQUIRED_FILES_GGUF = listOf(
        "base.gguf",    // Base text model (MiniCPM-Llama3-V2.5)
        "mmproj.gguf"   // Vision projector (multimodal)
    )
    
    /**
     * Alternative: Single-file GGUF (for backwards compatibility)
     * Some downloads may provide a combined model file
     */
    val REQUIRED_FILES_GGUF_SINGLE = listOf(
        "ggml-model-Q4_K_M.gguf"  // Combined model file (~4.7GB)
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
     * Checks for vision model (base.gguf + mmproj.gguf), then single GGUF, then legacy
     */
    fun getRequiredFiles(modelDir: File): List<String> {
        // Check for vision model format (base + mmproj)
        val baseGguf = File(modelDir, "base.gguf")
        val mmprojGguf = File(modelDir, "mmproj.gguf")
        
        return when {
            baseGguf.exists() && mmprojGguf.exists() -> {
                // Vision model with separate projector
                REQUIRED_FILES_GGUF
            }
            File(modelDir, REQUIRED_FILES_GGUF_SINGLE[0]).exists() -> {
                // Single combined GGUF file
                REQUIRED_FILES_GGUF_SINGLE
            }
            else -> {
                // Legacy MLC format
                REQUIRED_FILES_LEGACY
            }
        }
    }
    
    /**
     * Minimum file sizes for GGUF format
     * Vision model: base (~2GB) + mmproj (~500MB-1GB)
     */
    val MIN_FILE_SIZES_GGUF = mapOf(
        "base.gguf" to 1_500_000_000L,                // >= 1.5 GB base model
        "mmproj.gguf" to 300_000_000L,                // >= 300 MB vision projector
        "ggml-model-Q4_K_M.gguf" to 3_500_000_000L,  // >= 3.5 GB (single file)
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
     * Check if model is GGUF format (vision or single file)
     */
    fun isGgufModel(modelDir: File): Boolean {
        val hasVisionModel = File(modelDir, "base.gguf").exists() && 
                            File(modelDir, "mmproj.gguf").exists()
        val hasSingleGguf = File(modelDir, "ggml-model-Q4_K_M.gguf").exists()
        
        return hasVisionModel || hasSingleGguf
    }
    
    /**
     * Check if model is vision model (base + mmproj)
     */
    fun isVisionModel(modelDir: File): Boolean {
        return File(modelDir, "base.gguf").exists() && 
               File(modelDir, "mmproj.gguf").exists()
    }
}

