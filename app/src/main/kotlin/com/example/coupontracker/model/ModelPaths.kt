package com.example.coupontracker.model

import android.content.Context
import java.io.File

/**
 * Central constants for model storage paths
 * All model files are stored under filesDir/models/
 * 
 * UPDATED FOR MULTI-MODEL SUPPORT:
 * - Qwen2.5-1.5B-Instruct: Text-only, improved JSON output (DEFAULT)
 * - Qwen2-1.5B-Instruct: Text-only, legacy (FALLBACK)
 * - MiniCPM-Llama3-V2.5: Vision-capable, slower, desktop-optimized (LEGACY)
 */
object ModelPaths {
    const val MODELS_ROOT = "models"
    
    // ===== MODEL IDS =====
    const val MODEL_ID_QWEN25 = "qwen25_1.5b_instruct_q4"
    const val MODEL_ID_QWEN2 = "qwen2_1.5b_instruct_q4"
    const val MODEL_ID_MINICPM = "minicpm_llama3_v25_q4"
    
    // ===== DEFAULT MODEL =====
    const val DEFAULT_MODEL_ID = MODEL_ID_QWEN25  // Qwen2.5 is now the default
    
    // ===== MODEL FILES =====
    const val QWEN25_MODEL_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    const val QWEN2_MODEL_FILE = "qwen2-1_5b-instruct-q4_k_m.gguf"
    const val MINICPM_MODEL_FILE = "ggml-model-Q4_K_M.gguf"
    const val MINICPM_MMPROJ_FILE = "mmproj-model-f16.gguf"
    
    // ===== MODEL SIZES (bytes) =====
    const val QWEN25_MODEL_SIZE_BYTES = 1_117_320_736L  // 1.04 GB (verified from HuggingFace)
    const val QWEN2_MODEL_SIZE_BYTES = 976_506_880L      // 931 MB
    const val MINICPM_MODEL_SIZE_BYTES = 4_967_641_088L  // 4.9 GB
    const val MINICPM_MMPROJ_SIZE_BYTES = 857_407_488L   // 817 MB
    
    /**
     * Get root models directory
     */
    fun root(context: Context): File = File(context.filesDir, MODELS_ROOT)
    
    /**
     * Get specific model directory
     */
    fun modelDir(context: Context, modelId: String = DEFAULT_MODEL_ID): File = 
        File(root(context), modelId)
    
    /**
     * Get model file path
     */
    fun getModelFile(context: Context, modelId: String = DEFAULT_MODEL_ID): File {
        val dir = modelDir(context, modelId)
        val filename = when (modelId) {
            MODEL_ID_QWEN25 -> QWEN25_MODEL_FILE
            MODEL_ID_QWEN2 -> QWEN2_MODEL_FILE
            MODEL_ID_MINICPM -> MINICPM_MODEL_FILE
            else -> QWEN25_MODEL_FILE
        }
        return File(dir, filename)
    }
    
    /**
     * Get required files for a model
     */
    fun getRequiredFiles(modelId: String): List<String> {
        return when (modelId) {
            MODEL_ID_QWEN25 -> {
                listOf(QWEN25_MODEL_FILE, ".verified")
            }
            MODEL_ID_QWEN2 -> {
                listOf(QWEN2_MODEL_FILE, ".verified")
            }
            MODEL_ID_MINICPM -> {
                listOf(MINICPM_MODEL_FILE, MINICPM_MMPROJ_FILE, ".verified")
            }
            else -> {
                listOf(QWEN25_MODEL_FILE, ".verified")
            }
        }
    }
    
    /**
     * Get expected total size for download
     */
    fun getExpectedSize(modelId: String): Long {
        return when (modelId) {
            MODEL_ID_QWEN25 -> QWEN25_MODEL_SIZE_BYTES
            MODEL_ID_QWEN2 -> QWEN2_MODEL_SIZE_BYTES
            MODEL_ID_MINICPM -> MINICPM_MODEL_SIZE_BYTES + MINICPM_MMPROJ_SIZE_BYTES
            else -> QWEN25_MODEL_SIZE_BYTES
        }
    }
    
    /**
     * Check if model has vision support
     */
    fun hasVisionSupport(modelId: String): Boolean {
        return when (modelId) {
            MODEL_ID_QWEN2 -> false  // Text-only
            MODEL_ID_MINICPM -> true // Vision-capable
            else -> false
        }
    }
    
    /**
     * Get human-readable model name
     */
    fun getModelName(modelId: String): String {
        return when (modelId) {
            MODEL_ID_QWEN2 -> "Qwen2-1.5B-Instruct"
            MODEL_ID_MINICPM -> "MiniCPM-Llama3-V2.5"
            else -> "Qwen2-1.5B-Instruct"
        }
    }
    
    /**
     * Get human-readable model size
     */
    fun getModelSizeFormatted(modelId: String): String {
        val bytes = getExpectedSize(modelId)
        val mb = bytes / (1024 * 1024)
        val gb = bytes / (1024 * 1024 * 1024)
        return if (gb > 1) {
            String.format("%.1f GB", gb.toFloat())
        } else {
            "$mb MB"
        }
    }
    
    /**
     * Check if model is downloaded and verified
     */
    fun isModelVerified(context: Context, modelId: String = DEFAULT_MODEL_ID): Boolean {
        val dir = modelDir(context, modelId)
        val verifiedFile = File(dir, ".verified")
        val modelFile = getModelFile(context, modelId)
        
        return verifiedFile.exists() && modelFile.exists() && modelFile.length() > 0
    }
    
    /**
     * Get model info for UI display
     */
    data class ModelInfo(
        val id: String,
        val name: String,
        val size: String,
        val hasVision: Boolean,
        val isDefault: Boolean,
        val description: String
    )
    
    /**
     * Get all available models
     */
    fun getAllModels(): List<ModelInfo> {
        return listOf(
            ModelInfo(
                id = MODEL_ID_QWEN2,
                name = "Qwen2-1.5B-Instruct",
                size = "931 MB",
                hasVision = false,
                isDefault = true,
                description = "Fast text-only model optimized for mobile. Best for coupon extraction. Inference: 10-15s."
            ),
            ModelInfo(
                id = MODEL_ID_MINICPM,
                name = "MiniCPM-Llama3-V2.5",
                size = "5.8 GB",
                hasVision = true,
                isDefault = false,
                description = "Vision-capable model for desktop. Requires GPU. Inference: 60-90s on mobile CPU."
            )
        )
    }
    
    // ===== LEGACY COMPATIBILITY =====
    
    /**
     * Minimum file sizes for validation
     */
    fun getMinFileSize(modelId: String, filename: String): Long {
        return when {
            filename == QWEN2_MODEL_FILE -> 900_000_000L  // >= 900 MB
            filename == MINICPM_MODEL_FILE -> 4_500_000_000L  // >= 4.5 GB
            filename == MINICPM_MMPROJ_FILE -> 800_000_000L  // >= 800 MB
            filename == ".verified" -> 1L
            else -> 1L
        }
    }
    
    /**
     * Check if model is GGUF format (all new models are GGUF)
     */
    fun isGgufModel(modelId: String): Boolean = true
    
    /**
     * Check if model is vision model
     */
    fun isVisionModel(modelId: String): Boolean = hasVisionSupport(modelId)
}
