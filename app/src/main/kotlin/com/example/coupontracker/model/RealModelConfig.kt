package com.example.coupontracker.model

/**
 * Configuration for real MiniCPM-V-2.6 GGUF model download
 * Using official prebuilt weights from Hugging Face
 * 
 * License: Apache 2.0 + Commercial Use Registration Required
 * Questionnaire: https://modelbest.feishu.cn/share/base/form/shrcnpV5ZT9EJ6xkmaNKWTN7Bcd
 */
object RealModelConfig {
    
    // Model Information
    const val MODEL_NAME = "MiniCPM-V-2.6"
    const val MODEL_VERSION = "2.6.0-gguf-q4km"
    const val QUANTIZATION = "Q4_K_M"  // Good balance: ~2.5GB, decent quality
    
    // Hugging Face Repository
    const val HF_REPO = "openbmb/MiniCPM-V-2_6-gguf"
    const val HF_BASE_URL = "https://huggingface.co/$HF_REPO/resolve/main"
    
    // Model Files (all required for vision model)
    data class ModelFile(
        val filename: String,
        val url: String,
        val expectedSize: Long,  // in bytes
        val sha256: String,      // to be computed after first download
        val required: Boolean = true
    )
    
    // Primary model weight file
    val MAIN_MODEL = ModelFile(
        filename = "ggml-model-Q4_K_M.gguf",
        url = "$HF_BASE_URL/ggml-model-Q4_K_M.gguf",
        expectedSize = 2_500_000_000L,  // ~2.5GB (approximate)
        sha256 = "COMPUTE_ON_FIRST_DOWNLOAD",  // Will be updated after verification
        required = true
    )
    
    // Additional required files (if needed for vision model)
    val TOKENIZER = ModelFile(
        filename = "tokenizer.json",
        url = "$HF_BASE_URL/tokenizer.json",
        expectedSize = 500_000L,  // ~500KB
        sha256 = "COMPUTE_ON_FIRST_DOWNLOAD",
        required = true
    )
    
    val CONFIG = ModelFile(
        filename = "config.json",
        url = "$HF_BASE_URL/config.json",
        expectedSize = 10_000L,  // ~10KB
        sha256 = "COMPUTE_ON_FIRST_DOWNLOAD",
        required = false  // May not be needed
    )
    
    // All files to download
    val ALL_FILES = listOf(MAIN_MODEL, TOKENIZER, CONFIG)
    
    // Storage Requirements
    const val REQUIRED_FREE_SPACE = 3_500_000_000L  // 3.5GB (model + buffer)
    const val MIN_MODEL_SIZE = 2_000_000_000L       // 2GB minimum (reject if smaller)
    
    // Download Configuration
    const val DOWNLOAD_TIMEOUT_MS = 300_000L  // 5 minutes per chunk
    const val CHUNK_SIZE = 8 * 1024 * 1024    // 8MB chunks for large files
    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 5000L
    
    // License Information
    const val LICENSE_URL = "https://huggingface.co/$HF_REPO"
    const val QUESTIONNAIRE_URL = "https://modelbest.feishu.cn/share/base/form/shrcnpV5ZT9EJ6xkmaNKWTN7Bcd"
    
    /**
     * Get download URL with proper parameters for Git LFS
     */
    fun getDownloadUrl(file: ModelFile): String {
        return "${file.url}?download=true"
    }
    
    /**
     * Calculate total download size
     */
    fun getTotalDownloadSize(): Long {
        return ALL_FILES.filter { it.required }.sumOf { it.expectedSize }
    }
    
    /**
     * Get model directory name
     */
    fun getModelDirName(): String {
        return "minicpm-v2.6-q4km"
    }
}

