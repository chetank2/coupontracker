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
    const val MODEL_ID_GEMMA_VISION = "gemma_vision"
    
    // ===== DEFAULT MODEL =====
    const val DEFAULT_MODEL_ID = MODEL_ID_QWEN25  // Qwen2.5 is now the default
    
    // ===== MODEL FILES =====
    const val QWEN25_MODEL_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    const val QWEN2_MODEL_FILE = "qwen2-1_5b-instruct-q4_k_m.gguf"
    const val MINICPM_MODEL_FILE = "ggml-model-Q4_K_M.gguf"
    const val MINICPM_MMPROJ_FILE = "mmproj-model-f16.gguf"
    const val GEMMA_VISION_MODEL_FILE = "gemma-3-vision.task"
    const val GEMMA_VISION_REMOTE_MODEL_FILE = "gemma-3n-E2B-it-int4.task"
    const val GEMMA_VISION_ENCODER_FILE = "gemma-3-vision-encoder.task"
    const val GEMMA_VISION_ADAPTER_FILE = "gemma-3-vision-adapter.task"
    const val GEMMA_VISION_MIN_SIZE_BYTES = 2_000_000_000L
    const val CONFIG_FILE = "mlc-chat-config.json"
    const val TOKENIZER_JSON = "tokenizer.json"
    const val TOKENIZER_MODEL = "tokenizer.model"
    
    // ===== MODEL SIZES (bytes) =====
    const val QWEN25_MODEL_SIZE_BYTES = 986_048_768L  // 940 MB (bartowski Q4_K_M)
    const val QWEN2_MODEL_SIZE_BYTES = 976_506_880L      // 931 MB
    const val MINICPM_MODEL_SIZE_BYTES = 4_967_641_088L  // 4.9 GB
    const val MINICPM_MMPROJ_SIZE_BYTES = 857_407_488L   // 817 MB
    
    /**
     * Get root models directory
     */
    fun root(context: Context): File = File(context.filesDir, MODELS_ROOT)

    fun gemmaDir(context: Context): File = File(context.filesDir, "gemma")
    
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
                listOf(QWEN25_MODEL_FILE, CONFIG_FILE, TOKENIZER_JSON, ".verified")
            }
            MODEL_ID_QWEN2 -> {
                listOf(QWEN2_MODEL_FILE, CONFIG_FILE, TOKENIZER_JSON, ".verified")
            }
            MODEL_ID_MINICPM -> {
                listOf(
                    MINICPM_MODEL_FILE,
                    MINICPM_MMPROJ_FILE,
                    CONFIG_FILE,
                    TOKENIZER_MODEL,
                    ".verified"
                )
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
            MODEL_ID_GEMMA_VISION -> true
            else -> false
        }
    }
    
    /**
     * Get human-readable model name
     */
    fun getModelName(modelId: String): String {
        return when (modelId) {
            MODEL_ID_QWEN25 -> "Qwen2.5-1.5B-Instruct"
            MODEL_ID_QWEN2 -> "Qwen2-1.5B-Instruct"
            MODEL_ID_MINICPM -> "MiniCPM-Llama3-V2.5"
            MODEL_ID_GEMMA_VISION -> "Gemma Vision"
            else -> "Qwen2.5-1.5B-Instruct"
        }
    }

    fun isGemmaVisionInstalled(context: Context): Boolean {
        val status = getGemmaVisionInstallStatus(context)
        return status.installed
    }

    fun getGemmaVisionModelFile(context: Context): File {
        val dir = gemmaDir(context)
        val canonical = File(dir, GEMMA_VISION_MODEL_FILE)
        if (canonical.exists()) return canonical
        return File(dir, GEMMA_VISION_REMOTE_MODEL_FILE)
    }

    fun getGemmaVisionInstallStatus(context: Context): GemmaVisionInstallStatus {
        val dir = gemmaDir(context)
        val verified = File(dir, ".vision_verified")
        val model = getGemmaVisionModelFile(context)
        return when {
            !dir.exists() -> GemmaVisionInstallStatus(false, "Gemma Vision directory is missing.")
            !verified.exists() -> GemmaVisionInstallStatus(false, "Gemma Vision verification marker is missing.")
            !model.exists() -> GemmaVisionInstallStatus(false, "Gemma Vision task file is missing.")
            model.length() < GEMMA_VISION_MIN_SIZE_BYTES -> {
                GemmaVisionInstallStatus(
                    false,
                    "Gemma Vision task file is incomplete (${model.length()} bytes). Download or import it again."
                )
            }
            else -> GemmaVisionInstallStatus(true, "Gemma Vision is installed.", model)
        }
    }

    data class GemmaVisionInstallStatus(
        val installed: Boolean,
        val message: String,
        val modelFile: File? = null
    )
    
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
                id = MODEL_ID_QWEN25,
                name = "Qwen2.5-1.5B-Instruct",
                size = "940 MB",
                hasVision = false,
                isDefault = true,
                description = "Fast text-only model optimized for JSON outputs. Inference: 10-15s on recent devices."
            ),
            ModelInfo(
                id = MODEL_ID_QWEN2,
                name = "Qwen2-1.5B-Instruct",
                size = "931 MB",
                hasVision = false,
                isDefault = false,
                description = "Legacy fallback model. Use only if Qwen2.5 is unavailable. Inference: 10-15s."
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
            filename == QWEN25_MODEL_FILE -> 900_000_000L  // >= 900 MB
            filename == QWEN2_MODEL_FILE -> 900_000_000L  // >= 900 MB
            filename == MINICPM_MODEL_FILE -> 4_500_000_000L  // >= 4.5 GB
            filename == MINICPM_MMPROJ_FILE -> 800_000_000L  // >= 800 MB
            filename == CONFIG_FILE -> 1_024L  // treat anything >1KB as valid config
            filename == TOKENIZER_JSON -> 10_000L  // Qwen tokenizer ~500KB
            filename == TOKENIZER_MODEL -> 100_000L // MLC tokenizer model ~100KB
            filename == ".verified" -> 0L
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
