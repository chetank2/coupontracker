package com.example.coupontracker.model

import android.content.Context
import java.io.File

/**
 * Central constants for model storage paths
 * All model files are stored under filesDir/models/
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
     * Required files for a valid model installation
     */
    val REQUIRED_FILES = listOf(
        "mlc-chat-config.json",
        "tokenizer.json",
        "vision_config.json",
        "params/ndarray-cache.json",
        "weights/model.bin",
        "tokenizer/tokenizer.model"
    )
    
    /**
     * Minimum file sizes to reject placeholder/mock files
     */
    val MIN_FILE_SIZES = mapOf(
        "weights/model.bin" to 1_500_000_000L,      // >= 1.5 GB
        "tokenizer/tokenizer.model" to 200_000L,    // >= 200 KB
        "params/ndarray-cache.json" to 1_000L,      // >= 1 KB
        "mlc-chat-config.json" to 1_000L,           // >= 1 KB
        "tokenizer.json" to 1_000_000L,             // >= 1 MB
        "vision_config.json" to 1_000L              // >= 1 KB
    )
}

