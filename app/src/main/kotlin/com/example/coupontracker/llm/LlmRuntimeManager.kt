package com.example.coupontracker.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.LazyThreadSafetyMode

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
    private val nativeInterface: SafeMlcLlmNative by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SafeMlcLlmNative(context)
    }
    private var modelHandle: Long? = null
    private val referenceCount = AtomicInteger(0)
    private val lifecycleMutex = Mutex()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Model paths
    private val modelDir = File(context.filesDir, "models")
    private val modelPath = File(modelDir, MODEL_FILE)
    private val configPath = File(modelDir, CONFIG_FILE)
    private val tokenizerPath = File(modelDir, TOKENIZER_FILE)
    
    // Auto-unload job
    private var autoUnloadJob: Job? = null
    
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
            isLoaded = modelHandle != null,
            sizeBytes = sizeBytes,
            sizeMB = sizeBytes / (1024f * 1024f),
            referenceCount = referenceCount.get()
        )
    }
    
    /**
     * Acquire model with reference counting and mutex protection
     */
    suspend fun acquireModel(): Unit = lifecycleMutex.withLock {
        if (referenceCount.incrementAndGet() == 1) {
            // First reference - load the model
            try {
                modelHandle = loadModelOrThrow()
                Log.d(TAG, "Model loaded on first acquire (handle: $modelHandle)")
            } catch (e: Exception) {
                // Rollback reference count on load failure
                referenceCount.decrementAndGet()
                Log.e(TAG, "Model load failed, rolled back reference count", e)
                throw e
            }
        }
        // Cancel any pending auto-unload
        autoUnloadJob?.cancel()
        autoUnloadJob = null
        Log.d(TAG, "Model acquired, reference count: ${referenceCount.get()}")
    }
    
    /**
     * Release model with reference counting and delayed unload
     */
    suspend fun releaseModel(): Unit = lifecycleMutex.withLock {
        if (referenceCount.decrementAndGet() == 0) {
            // No more references - schedule auto-unload
            autoUnloadJob = ioScope.launch {
                delay(AUTO_UNLOAD_DELAY_MS)
                lifecycleMutex.withLock {
                    if (referenceCount.get() == 0) {
                        unloadModelInternal(modelHandle)
                        modelHandle = null
                        Log.d(TAG, "Model auto-unloaded after inactivity")
                    }
                }
            }
        }
        Log.d(TAG, "Model released, reference count: ${referenceCount.get()}")
    }
    
    /**
     * Load model or throw exception (internal method)
     */
    private suspend fun loadModelOrThrow(): Long {
        // Check if model files exist
        if (!isModelAvailable()) {
            throw IllegalStateException("Model files not found. Please download the model first.")
        }
        
        // Load native library
        if (!MlcLlmNative.loadLibrary(context)) {
            throw IllegalStateException("Failed to load MLC-LLM native library")
        }
        
        Log.d(TAG, "Loading MiniCPM-Llama3-V2.5 model...")
        
        // Initialize model through native interface
        val handle = nativeInterface.initializeModel(
            modelPath.absolutePath,
            configPath.absolutePath
        )
        
        if (handle == 0L) {
            throw IllegalStateException("Failed to initialize MiniCPM model")
        }
        
        // Warm up the model for faster inference
        nativeInterface.warmupModel(handle)
        
        // Set inference parameters
        nativeInterface.setInferenceParams(
            handle,
            temperature = 0.3f,
            maxTokens = MAX_TOKENS,
            topP = 0.9f
        )
        
        Log.d(TAG, "MiniCPM model loaded successfully (handle: $handle)")
        return handle
    }
    
    /**
     * Legacy method for backward compatibility
     */
    suspend fun loadModel(): Boolean = try {
        acquireModel()
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load model", e)
        false
    }
    
    /**
     * Run inference on an image with structured prompt
     */
    suspend fun runInference(bitmap: Bitmap, prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            // Acquire model (loads if needed)
            acquireModel()
            
            try {
                val currentHandle = modelHandle ?: throw IllegalStateException("Model handle is null after acquire")
                
                Log.d(TAG, "Running MiniCPM inference...")
                
                // Preprocess image for model requirements
                val processedImage = preprocessImageForMiniCPM(bitmap)
                
                // Convert bitmap to byte array for native interface
                val imageData = bitmapToByteArray(processedImage)
                
                // Run inference through native interface
                val response = nativeInterface.runVisionInference(
                    currentHandle,
                    imageData,
                    processedImage.width,
                    processedImage.height,
                    prompt
                )
                
                Log.d(TAG, "Inference completed, response length: ${response?.length ?: 0}")
                return@withContext response
                
            } finally {
                // Always release model after use
                releaseModel()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return@withContext null
        }
    }
    
    /**
     * Internal method to unload model (called under mutex)
     */
    private fun unloadModelInternal(handle: Long?) {
        if (handle != null && handle != 0L) {
            try {
                nativeInterface.releaseModel(handle)
                Log.d(TAG, "Model unloaded (handle: $handle)")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model", e)
            }
        }
    }
    
    /**
     * Force unload the model (for cleanup)
     */
    fun forceUnload() {
        ioScope.launch {
            lifecycleMutex.withLock {
                autoUnloadJob?.cancel()
                autoUnloadJob = null
                unloadModelInternal(modelHandle)
                modelHandle = null
                referenceCount.set(0)
                Log.d(TAG, "Model force unloaded")
            }
        }
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
            // Check if real MLC-LLM native library is available
            if (!MlcLlmNative.isAvailable()) {
                Log.w(TAG, "⚠️ MLC-LLM native library not available - using stub implementation")
                Log.w(TAG, "LLM_FIRST strategy will return mock data until real libraries are integrated")
                Log.w(TAG, "See MLC_LLM_INTEGRATION_GUIDE.md for integration instructions")
                
                return MLCEngineStub(
                    modelPath = modelPath.absolutePath,
                    configPath = configPath.absolutePath,
                    tokenizerPath = tokenizerPath.absolutePath,
                    maxMemoryMB = MAX_MEMORY_MB
                )
            }
            
            // Real MLC-LLM is available - create native engine
            Log.i(TAG, "✅ MLC-LLM native library available - creating real engine")
            
            // In real implementation, this would initialize MLC-LLM with:
            // - Model path
            // - Device configuration (Vulkan/NNAPI/CPU)
            // - Memory limits
            // - Quantization settings
            
            // TODO: Implement MLCEngineReal that wraps MlcLlmNative
            // For now, fall back to stub even when native is available
            // This will be fixed when real model binaries are integrated
            Log.w(TAG, "⚠️ MLCEngineReal not yet implemented - using stub temporarily")
            
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
            modelLoadedMemoryMB = if (modelHandle != null) MAX_MEMORY_MB else 0
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
