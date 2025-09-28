package com.example.coupontracker.llm

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton manager for MiniCPM-Llama3-V2.5 LLM runtime
 * Handles model loading, inference, and resource management
 */
class LlmRuntimeManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LlmRuntimeManager"
        
        @Volatile
        private var INSTANCE: LlmRuntimeManager? = null
        
        fun getInstance(context: Context): LlmRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // Model configuration constants
        private const val MODEL_NAME = "minicpm_llama3_v25_q4"
        private const val MODEL_FILE = "minicpm_llm_q4f16_1.so"
        private const val CONFIG_FILE = "mlc-chat-config.json"
        private const val TOKENIZER_FILE = "tokenizer.json"
        
        // Performance constants
        private const val MAX_MEMORY_MB = 3072
        private const val MAX_TOKENS = 512
        private const val INFERENCE_TIMEOUT_MS = 30000L
        
        // Auto-unload after 5 minutes of inactivity
        private const val AUTO_UNLOAD_DELAY_MS = 5 * 60 * 1000L
    }
    
    // Native interface and model state
    private val nativeInterface = SafeMlcLlmNative()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var modelHandle: Long = 0
    private val isModelLoaded = AtomicBoolean(false)
    private val referenceCount = AtomicInteger(0)
    private val loadMutex = Mutex()
    
    // Model paths
    private val modelDir = File(context.filesDir, "models")
    private val modelPath = File(modelDir, MODEL_FILE)
    private val configPath = File(modelDir, CONFIG_FILE)
    private val tokenizerPath = File(modelDir, TOKENIZER_FILE)
    
    // Auto-unload timer
    private var lastUsedTime = System.currentTimeMillis()
    private val autoUnloadRunnable = Runnable { checkAndUnloadModel() }
    
    /**
     * Check if the model is available on device
     * Verifies all required files exist and are not empty
     */
    fun isModelAvailable(): Boolean {
        val requiredFiles = listOf(
            modelPath,           // minicpm_llm_q4f16_1.so
            configPath,          // mlc-chat-config.json
            tokenizerPath,       // tokenizer.json
            File(modelDir, "model.bin"),        // model parameters
            File(modelDir, "vision_config.json"), // vision configuration
            File(modelDir, "tokenizer.model")   // tokenizer model
        )
        
        val missingFiles = mutableListOf<String>()
        for (file in requiredFiles) {
            if (!file.exists() || file.length() == 0L) {
                missingFiles.add(file.name)
            }
        }
        
        if (missingFiles.isNotEmpty()) {
            Log.d(TAG, "Missing or empty model files: ${missingFiles.joinToString(", ")}")
            return false
        }
        
        Log.d(TAG, "✅ All required model files are present and valid")
        return true
    }
    
    /**
     * Get model information
     */
    fun getModelInfo(): ModelInfo {
        val isAvailable = isModelAvailable()
        val sizeBytes = if (isAvailable) {
            modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        
        return ModelInfo(
            name = MODEL_NAME,
            version = "v2.5-q4-android",
            isAvailable = isAvailable,
            isLoaded = isModelLoaded.get(),
            sizeBytes = sizeBytes,
            sizeMB = sizeBytes / (1024f * 1024f),
            referenceCount = referenceCount.get()
        )
    }
    
    /**
     * Load the model with reference counting
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            // If already loaded, just increment reference count
            if (isModelLoaded.get()) {
                referenceCount.incrementAndGet()
                lastUsedTime = System.currentTimeMillis()
                Log.d(TAG, "Model already loaded, reference count: ${referenceCount.get()}")
                return@withContext true
            }
            
            // Check if model files exist
            if (!isModelAvailable()) {
                Log.e(TAG, "Model files not found. Please download the model first.")
                return@withContext false
            }
            
            // Load native library
            if (!MlcLlmNative.loadLibrary()) {
                Log.e(TAG, "Failed to load MLC-LLM native library")
                return@withContext false
            }
            
            try {
                Log.d(TAG, "Loading MiniCPM-Llama3-V2.5 model...")
                
                // Initialize model through native interface
                modelHandle = nativeInterface.initializeModel(
                    modelPath.absolutePath,
                    configPath.absolutePath
                )
                
                if (modelHandle != 0L) {
                    // Warm up the model for faster inference
                    nativeInterface.warmupModel(modelHandle)
                    
                    // Set inference parameters
                    nativeInterface.setInferenceParams(
                        modelHandle,
                        temperature = 0.3f,
                        maxTokens = MAX_TOKENS,
                        topP = 0.9f
                    )
                    
                    isModelLoaded.set(true)
                    referenceCount.set(1)
                    lastUsedTime = System.currentTimeMillis()
                    
                    // Schedule auto-unload check
                    scheduleAutoUnloadCheck()
                    
                    Log.d(TAG, "MiniCPM model loaded successfully (handle: $modelHandle)")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to initialize MiniCPM model")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MiniCPM model", e)
                if (modelHandle != 0L) {
                    nativeInterface.releaseModel(modelHandle)
                    modelHandle = 0L
                }
                isModelLoaded.set(false)
                referenceCount.set(0)
                return@withContext false
            }
        }
    }
    
    /**
     * Run inference on an image with structured prompt
     */
    suspend fun runInference(bitmap: Bitmap, prompt: String): String? = withContext(Dispatchers.IO) {
        if (!isModelLoaded.get()) {
            Log.w(TAG, "Model not loaded, attempting to load...")
            if (!loadModel()) {
                return@withContext null
            }
        }
        
        lastUsedTime = System.currentTimeMillis()
        
        try {
            Log.d(TAG, "Running MiniCPM inference...")
            
            // Preprocess image for model requirements
            val processedImage = preprocessImageForMiniCPM(bitmap)
            
            // Convert bitmap to byte array for native interface
            val imageData = bitmapToByteArray(processedImage)
            
            // Run inference through native interface
            val response = nativeInterface.runVisionInference(
                modelHandle,
                imageData,
                processedImage.width,
                processedImage.height,
                prompt
            )
            
            Log.d(TAG, "Inference completed, response length: ${response?.length ?: 0}")
            return@withContext response
            
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return@withContext null
        }
    }
    
    /**
     * Release model reference, unload if no more references
     */
    fun releaseModel() {
        mainHandler.removeCallbacks(autoUnloadRunnable)

        val newCount = referenceCount.decrementAndGet()
        Log.d(TAG, "Released model reference, count: $newCount")

        if (newCount <= 0) {
            unloadModel()
        }
    }
    
    /**
     * Force unload the model
     */
    private fun unloadModel() {
        synchronized(this) {
            mainHandler.removeCallbacks(autoUnloadRunnable)

            if (isModelLoaded.get() && modelHandle != 0L) {
                try {
                    nativeInterface.releaseModel(modelHandle)
                    modelHandle = 0L
                    isModelLoaded.set(false)
                    referenceCount.set(0)
                    Log.d(TAG, "MiniCPM model unloaded")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unloading model", e)
                }
            }
        }
    }
    
    /**
     * Check if model should be auto-unloaded due to inactivity
     */
    private fun checkAndUnloadModel() {
        val timeSinceLastUse = System.currentTimeMillis() - lastUsedTime
        
        if (timeSinceLastUse > AUTO_UNLOAD_DELAY_MS && referenceCount.get() == 0) {
            Log.d(TAG, "Auto-unloading model due to inactivity")
            unloadModel()
        } else if (isModelLoaded.get()) {
            // Schedule next check
            scheduleAutoUnloadCheck()
        }
    }
    
    /**
     * Schedule auto-unload check
     */
    private fun scheduleAutoUnloadCheck() {
        mainHandler.removeCallbacks(autoUnloadRunnable)
        mainHandler.postDelayed(autoUnloadRunnable, AUTO_UNLOAD_DELAY_MS)
    }
    
    /**
     * Preprocess bitmap for MiniCPM model requirements
     */
    private fun preprocessImageForMiniCPM(bitmap: Bitmap): ProcessedImage {
        // Resize to model's expected input size (768x768 max)
        val maxSize = 768
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        
        // Convert to RGB if necessary
        val rgbBitmap = if (scaledBitmap.config != Bitmap.Config.ARGB_8888) {
            scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            scaledBitmap
        }
        
        return ProcessedImage(
            bitmap = rgbBitmap,
            width = rgbBitmap.width,
            height = rgbBitmap.height,
            channels = 3
        )
    }
    
    /**
     * Convert bitmap to RGB byte array for native interface
     */
    private fun bitmapToByteArray(processedImage: ProcessedImage): ByteArray {
        val bitmap = processedImage.bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Convert ARGB to RGB byte array
        val byteArray = ByteArray(pixels.size * 3)
        var byteIndex = 0
        
        for (pixel in pixels) {
            byteArray[byteIndex++] = ((pixel shr 16) and 0xFF).toByte() // Red
            byteArray[byteIndex++] = ((pixel shr 8) and 0xFF).toByte()  // Green
            byteArray[byteIndex++] = (pixel and 0xFF).toByte()          // Blue
        }
        
        return byteArray
    }
    
    /**
     * Create MLC Engine instance - DEPRECATED
     * This is replaced by native MLC-LLM integration
     */
    @Deprecated("Use native interface instead")
    private fun createMLCEngine(): MLCEngine? {
        return try {
            // In real implementation, this would initialize MLC-LLM with:
            // - Model path
            // - Device configuration (Vulkan/NNAPI/CPU)
            // - Memory limits
            // - Quantization settings
            
            MLCEngineStub(
                modelPath = modelPath.absolutePath,
                configPath = configPath.absolutePath,
                tokenizerPath = tokenizerPath.absolutePath,
                maxMemoryMB = MAX_MEMORY_MB
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MLC engine", e)
            null
        }
    }
    
    /**
     * Get memory usage statistics
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        return MemoryStats(
            totalMemoryMB = runtime.totalMemory() / (1024 * 1024),
            freeMemoryMB = runtime.freeMemory() / (1024 * 1024),
            maxMemoryMB = runtime.maxMemory() / (1024 * 1024),
            modelLoadedMemoryMB = if (isModelLoaded.get()) MAX_MEMORY_MB else 0
        )
    }
}

/**
 * Model information data class
 */
data class ModelInfo(
    val name: String,
    val version: String,
    val isAvailable: Boolean,
    val isLoaded: Boolean,
    val sizeBytes: Long,
    val sizeMB: Float,
    val referenceCount: Int
)

/**
 * Memory statistics data class
 */
data class MemoryStats(
    val totalMemoryMB: Long,
    val freeMemoryMB: Long,
    val maxMemoryMB: Long,
    val modelLoadedMemoryMB: Int
)

/**
 * Processed image data class
 */
data class ProcessedImage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val channels: Int
)

/**
 * MLC Engine interface (placeholder)
 * In real implementation, this would be provided by MLC-LLM JNI bindings
 */
interface MLCEngine {
    fun generate(
        prompt: String,
        image: ProcessedImage,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Long
    ): String?
    
    fun release()
}

/**
 * Stub implementation for development
 * This will be replaced with actual MLC-LLM integration
 */
private class MLCEngineStub(
    private val modelPath: String,
    private val configPath: String,
    private val tokenizerPath: String,
    private val maxMemoryMB: Int
) : MLCEngine {
    
    override fun generate(
        prompt: String,
        image: ProcessedImage,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Long
    ): String? {
        // Stub implementation - returns mock JSON response
        // In real implementation, this would call MLC-LLM native inference
        Log.d("MLCEngineStub", "Mock inference for image ${image.width}x${image.height}")
        
        return """
        {
            "storeName": "Mock Store",
            "description": "Mock coupon offer - 50% off",
            "amount": "₹500",
            "code": "MOCK50",
            "expiryDate": "31/12/2024",
            "cashbackAmount": "₹100",
            "minOrderAmount": "₹1000"
        }
        """.trimIndent()
    }
    
    override fun release() {
        Log.d("MLCEngineStub", "Released mock MLC engine")
    }
}
