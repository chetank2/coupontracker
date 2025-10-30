package com.example.coupontracker.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
        
        // Model configuration constants (legacy - kept for backwards compatibility)
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
    private val inferenceMutex = Mutex()  // CRITICAL: Serialize inference to prevent concurrent access
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // CRITICAL: Detected model ID must be declared BEFORE modelDir property
    // Because modelDir getter accesses detectedModelId during initialization
    private var detectedModelId: String = com.example.coupontracker.model.ModelPaths.DEFAULT_MODEL_ID
    
    // Model paths - now uses ModelPaths for consistency
    private val modelDir: File
        get() = com.example.coupontracker.model.ModelPaths.modelDir(context, detectedModelId)
    private val configPath = File(modelDir, CONFIG_FILE)
    private val tokenizerPath = File(modelDir, TOKENIZER_FILE)
    
    // Auto-unload job
    private var autoUnloadJob: Job? = null
    
    /**
     * Detect which model is installed (Qwen2 or MiniCPM)
     * Returns the model ID of the installed model, or DEFAULT_MODEL_ID if none found
     */
    private fun detectInstalledModel(): String {
        val qwen25Dir = com.example.coupontracker.model.ModelPaths.modelDir(
            context,
            com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN25
        )
        val qwen2Dir = com.example.coupontracker.model.ModelPaths.modelDir(
            context,
            com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN2
        )
        val minicpmDir = com.example.coupontracker.model.ModelPaths.modelDir(
            context,
            com.example.coupontracker.model.ModelPaths.MODEL_ID_MINICPM
        )
        
        // Check Qwen2.5 first (it's the new default)
        val qwen25File = File(qwen25Dir, com.example.coupontracker.model.ModelPaths.QWEN25_MODEL_FILE)
        val qwen25Verified = File(qwen25Dir, ".verified")

        if (qwen25File.exists() &&
            qwen25File.length() >= com.example.coupontracker.model.ModelPaths.getMinFileSize(
                com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN25,
                com.example.coupontracker.model.ModelPaths.QWEN25_MODEL_FILE
            )
        ) {
            if (!qwen25Verified.exists()) {
                Log.w(TAG, "⚠️ Qwen2.5 model detected but .verified sentinel missing – treating as installed")
            } else if (qwen25Verified.length() == 0L) {
                Log.w(TAG, "⚠️ Qwen2.5 .verified sentinel is empty – continuing to treat model as installed")
            } else {
                Log.d(TAG, "✅ Detected Qwen2.5-1.5B model")
            }
            return com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN25
        }
        
        // Check Qwen2 (legacy fallback)
        val qwen2File = File(qwen2Dir, com.example.coupontracker.model.ModelPaths.QWEN2_MODEL_FILE)
        val qwen2Verified = File(qwen2Dir, ".verified")
        
        if (qwen2File.exists() &&
            qwen2File.length() >= com.example.coupontracker.model.ModelPaths.getMinFileSize(
                com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN2,
                com.example.coupontracker.model.ModelPaths.QWEN2_MODEL_FILE
            )
        ) {
            if (!qwen2Verified.exists()) {
                Log.w(TAG, "⚠️ Qwen2 model detected but .verified sentinel missing – treating as installed")
            } else if (qwen2Verified.length() == 0L) {
                Log.w(TAG, "⚠️ Qwen2 .verified sentinel is empty – continuing to treat model as installed")
            } else {
                Log.d(TAG, "✅ Detected Qwen2-1.5B model (legacy)")
            }
            return com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN2
        }
        
        // Check MiniCPM
        val minicpmFile = File(minicpmDir, com.example.coupontracker.model.ModelPaths.MINICPM_MODEL_FILE)
        val minicpmVerified = File(minicpmDir, ".verified")
        
        if (minicpmFile.exists() &&
            minicpmFile.length() >= com.example.coupontracker.model.ModelPaths.getMinFileSize(
                com.example.coupontracker.model.ModelPaths.MODEL_ID_MINICPM,
                com.example.coupontracker.model.ModelPaths.MINICPM_MODEL_FILE
            )
        ) {
            if (!minicpmVerified.exists()) {
                Log.w(TAG, "⚠️ MiniCPM model detected but .verified sentinel missing – treating as installed")
            } else if (minicpmVerified.length() == 0L) {
                Log.w(TAG, "⚠️ MiniCPM .verified sentinel is empty – continuing to treat model as installed")
            } else {
                Log.d(TAG, "✅ Detected MiniCPM-Llama3-V2.5 model")
            }
            return com.example.coupontracker.model.ModelPaths.MODEL_ID_MINICPM
        }
        
        // No model found, return default
        Log.d(TAG, "⚠️ No model detected, defaulting to ${com.example.coupontracker.model.ModelPaths.DEFAULT_MODEL_ID}")
        return com.example.coupontracker.model.ModelPaths.DEFAULT_MODEL_ID
    }
    
    /**
     * Check if the model is available on device
     * Verifies all required files exist and are not empty
     * Supports both Qwen2 and MiniCPM models
     */
    fun isModelAvailable(): Boolean {
        // Detect which model is installed
        detectedModelId = detectInstalledModel()
        
        // Get required files for the detected model
        val requiredFiles = com.example.coupontracker.model.ModelPaths.getRequiredFiles(detectedModelId)
        val modelName = com.example.coupontracker.model.ModelPaths.getModelName(detectedModelId)
        
        Log.d(TAG, "Checking model availability: $modelName")
        Log.d(TAG, "Model directory: ${modelDir.absolutePath}")
        
        val missingFiles = mutableListOf<String>()
        var missingSentinel = false
        for (requiredFile in requiredFiles) {
            val file = File(modelDir, requiredFile)
            val minSize = com.example.coupontracker.model.ModelPaths.getMinFileSize(detectedModelId, requiredFile)

            if (!file.exists()) {
                if (requiredFile == ".verified") {
                    missingSentinel = true
                    continue
                }
                missingFiles.add(requiredFile)
                continue
            }

            if (requiredFile == ".verified") {
                if (file.length() == 0L) {
                    missingSentinel = true
                }
                continue
            }

            if (file.length() < minSize) {
                missingFiles.add(requiredFile)
            }
        }

        if (missingFiles.isNotEmpty()) {
            Log.d(TAG, "Missing or empty model files: ${missingFiles.joinToString(", ")}")
            return false
        }

        if (missingSentinel) {
            Log.w(TAG, "⚠️ Model sentinel (.verified) missing or empty – continuing but telemetry will note unverified install")
        }

        Log.d(TAG, "✅ All required model files are present and valid ($modelName)")
        return true
    }
    
    /**
     * Get model information
     */
    fun getModelInfo(): ModelInfo {
        val isAvailable = isModelAvailable()
        val modelName = com.example.coupontracker.model.ModelPaths.getModelName(detectedModelId)
        val sizeBytes = if (isAvailable) {
            modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        
        return ModelInfo(
            name = modelName,
            version = detectedModelId,
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
     * Supports both Qwen2 and MiniCPM models
     */
    private suspend fun loadModelOrThrow(): Long {
        // Check if model files exist
        if (!isModelAvailable()) {
            throw IllegalStateException("Model files not found. Please download the model first.")
        }
        
        val modelName = com.example.coupontracker.model.ModelPaths.getModelName(detectedModelId)
        val modelFile = com.example.coupontracker.model.ModelPaths.getModelFile(context, detectedModelId)
        
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 Loading model: $modelName")
        Log.d(TAG, "📦 Model ID: $detectedModelId")
        Log.d(TAG, "📁 Model file: ${modelFile.absolutePath}")
        Log.d(TAG, "📏 Model size: ${modelFile.length() / 1_000_000} MB")
        Log.d(TAG, "========================================")
        
        // Verify GGUF file structure
        val ggufLoader = GgufModelLoader(context)
        val metadata = ggufLoader.verifyGgufFile(modelFile)
        
        if (metadata == null) {
            throw IllegalStateException("Invalid GGUF file format")
        }
        
        Log.d(TAG, "✅ GGUF verification passed")
        Log.d(TAG, "  Version: ${metadata.version}")
        Log.d(TAG, "  Tensors: ${metadata.tensorCount}")
        
        // Check if model has vision support
        val hasVision = com.example.coupontracker.model.ModelPaths.hasVisionSupport(detectedModelId)
        if (hasVision) {
            Log.d(TAG, "✅ Model has vision support (MiniCPM)")
        } else {
            Log.d(TAG, "ℹ️  Text-only model (Qwen2) - optimized for speed")
        }
        
        if (!configPath.exists() || configPath.length() == 0L) {
            Log.e(TAG, "mlc-chat-config.json missing or empty at ${configPath.absolutePath}")
            throw IllegalStateException("Missing mlc-chat-config.json for $modelName")
        }

        if (!tokenizerPath.exists() || tokenizerPath.length() == 0L) {
            Log.e(TAG, "tokenizer.json missing or empty at ${tokenizerPath.absolutePath}")
            throw IllegalStateException("Missing tokenizer.json for $modelName")
        }

        // Load native library
        if (!MlcLlmNative.loadLibrary(context)) {
            throw IllegalStateException("Failed to load native LLM library")
        }
        
        Log.d(TAG, "Initializing $modelName model...")
        
        // Initialize model through native interface
        val handle = nativeInterface.initializeModel(
            modelFile.absolutePath,   // Pass the GGUF model file path to native loader
            configPath.absolutePath   // Configuration file consumed by the native loader
        )
        
        if (handle == 0L) {
            throw IllegalStateException("Failed to initialize $modelName model")
        }
        
        Log.d(TAG, "✅ Model initialized successfully (handle: $handle)")
        
        // Warm up the model for faster inference
        nativeInterface.warmupModel(handle)
        
        // Set inference parameters (optimized for strict JSON with grammar)
        nativeInterface.setInferenceParams(
            handle,
            temperature = 0.1f,  // Lower for deterministic output (was 0.3)
            maxTokens = MAX_TOKENS,
            topP = 0.85f  // Slightly lower for less randomness (was 0.9)
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
                
                // CRITICAL: Lock to prevent concurrent inference (llama.cpp is not thread-safe)
                val response = inferenceMutex.withLock {
                    Log.d(TAG, "🔒 Acquired inference lock")
                    // Run inference through native interface
                    nativeInterface.runVisionInference(
                        currentHandle,
                        imageData,
                        processedImage.width,
                        processedImage.height,
                        prompt
                    ).also {
                        Log.d(TAG, "🔓 Released inference lock")
                    }
                }
                
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
     * Run TEXT-ONLY inference with OCR text
     * This is MUCH faster than vision inference (5-10s vs 15-30 min)
     * and still uses MiniCPM's intelligence to understand the text
     */
    suspend fun runTextInference(
        ocrText: String,
        prompt: String,
        keepLoaded: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        try {
            var releaseAfter = false
            if (!keepLoaded || modelHandle == null) {
                acquireModel()
                releaseAfter = !keepLoaded
            }
            
            try {
                val currentHandle = modelHandle ?: throw IllegalStateException("Model handle is null after acquire")
                
                Log.d(TAG, "Running MiniCPM TEXT-ONLY inference...")
                Log.d(TAG, "OCR text length: ${ocrText.length} chars")
                
                val inferenceStart = System.currentTimeMillis()
                
                // CRITICAL: Lock to prevent concurrent inference (llama.cpp is not thread-safe)
                val response = inferenceMutex.withLock {
                    Log.d(TAG, "🔒 Acquired inference lock (text-only)")
                    // Run text inference through native interface
                    nativeInterface.runTextInference(
                        currentHandle,
                        ocrText,
                        prompt
                    ).also {
                        Log.d(TAG, "🔓 Released inference lock (text-only)")
                    }
                }
                
                val inferenceTime = System.currentTimeMillis() - inferenceStart
                Log.d(TAG, "Text inference completed in ${inferenceTime}ms, response length: ${response?.length ?: 0}")
                
                // Performance warning if inference is unexpectedly slow
                if (inferenceTime > 30_000) {
                    Log.w(TAG, "⚠️  Slow inference detected: ${inferenceTime}ms (expected ~10-20s after warmup)")
                    Log.w(TAG, "⚠️  Check KV cache clearing and model state reset")
                }
                return@withContext response
                
            } finally {
                if (releaseAfter) {
                    // Release only if we acquired exclusively for this call
                    releaseModel()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Text inference failed", e)
            return@withContext null
        }
    }

    fun cancelOngoingInference() {
        val handle = modelHandle ?: return
        runCatching {
            nativeInterface.cancelInference(handle)
        }.onFailure { error ->
            Log.w(TAG, "Failed to cancel inference", error)
        }
    }

    suspend fun resetAfterTimeout() {
        lifecycleMutex.withLock {
            autoUnloadJob?.cancel()
            autoUnloadJob = null
            unloadModelInternal(modelHandle)
            modelHandle = null
            referenceCount.set(0)
            Log.d(TAG, "Model state reset after timeout")
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
     * Create legacy engine instance - DEPRECATED
     * This is replaced by the native llama.cpp integration
     */
    @Deprecated("Use native interface instead")
    private fun createMLCEngine(): MLCEngine? {
        return try {
            // Check if the real native llama.cpp library is available
            if (!MlcLlmNative.isAvailable()) {
                Log.w(TAG, "⚠️ Native LLM library not available - using stub implementation")
                Log.w(TAG, "LLM_FIRST strategy will return mock data until real libraries are integrated")
                Log.w(TAG, "See MLC_LLM_INTEGRATION_GUIDE.md for integration instructions")

                return MLCEngineStub(
                    modelPath = modelDir.absolutePath,
                    configPath = configPath.absolutePath,
                    tokenizerPath = tokenizerPath.absolutePath,
                    maxMemoryMB = MAX_MEMORY_MB
                )
            }

            // Real llama.cpp backend is available - create native engine
            Log.i(TAG, "✅ Native LLM library available - creating real engine")

            detectedModelId = detectInstalledModel()
            val primaryModelFile = com.example.coupontracker.model.ModelPaths.getModelFile(
                context,
                detectedModelId
            )

            MLCEngineReal(
                nativeInterface = nativeInterface,
                modelFile = primaryModelFile,
                configFile = configPath,
                tokenizerFile = tokenizerPath,
                maxTokens = MAX_TOKENS
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
 * Native engine interface (placeholder)
 * In real implementation, this is backed by the llama.cpp JNI bindings.
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
 * Real implementation backed by the JNI bridge.
 */
private class MLCEngineReal(
    private val nativeInterface: SafeMlcLlmNative,
    private val modelFile: File,
    private val configFile: File,
    private val tokenizerFile: File,
    private val maxTokens: Int
) : MLCEngine {

    companion object {
        private const val TAG = "MLCEngineReal"
        private const val DEFAULT_TOP_P = 0.9f
    }

    private val released = AtomicBoolean(false)
    private val modelHandle: Long

    init {
        val modelDirectory = modelFile.parentFile

        require(modelFile.exists()) {
            "Model file missing at ${modelFile.absolutePath}"
        }
        require(configFile.exists()) {
            "MLC config missing at ${configFile.absolutePath}"
        }
        require(tokenizerFile.exists()) {
            "Tokenizer missing at ${tokenizerFile.absolutePath}"
        }

        val modelDirectoryPath = modelDirectory?.absolutePath ?: modelFile.absolutePath
        Log.i(TAG, "Initializing MLC runtime from $modelDirectoryPath")
        modelHandle = nativeInterface.initializeModel(
            modelFile.absolutePath,
            configFile.absolutePath
        ).also { handle ->
            require(handle != 0L) { "MLC runtime returned invalid handle" }
        }

        Log.i(TAG, "Model handle acquired: $modelHandle")
        nativeInterface.warmupModel(modelHandle)
        nativeInterface.setInferenceParams(
            modelHandle,
            temperature = 0.1f,
            maxTokens = maxTokens,
            topP = DEFAULT_TOP_P
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override fun generate(
        prompt: String,
        image: ProcessedImage,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Long
    ): String? {
        if (released.get()) {
            Log.w(TAG, "Attempted to generate after release")
            return null
        }

        nativeInterface.setInferenceParams(modelHandle, temperature, maxTokens, DEFAULT_TOP_P)

        val imageBytes = image.toRgbByteArray()
        return try {
            nativeInterface.runVisionInference(
                modelHandle,
                imageBytes,
                image.width,
                image.height,
                prompt
            )
        } catch (error: Exception) {
            Log.e(TAG, "Vision inference failed", error)
            null
        }
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            runCatching { nativeInterface.releaseModel(modelHandle) }
                .onFailure { error -> Log.w(TAG, "Failed to release MLC runtime", error) }
        }
    }

    private fun ProcessedImage.toRgbByteArray(): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = ByteArray(pixels.size * 3)
        var index = 0
        for (pixel in pixels) {
            result[index++] = ((pixel shr 16) and 0xFF).toByte()
            result[index++] = ((pixel shr 8) and 0xFF).toByte()
            result[index++] = (pixel and 0xFF).toByte()
        }
        return result
    }
}

/**
 * Stub implementation for development
 * This will be replaced with the real llama.cpp integration.
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
        // In real implementation, this would call the native llama.cpp backend
        Log.d("MLCEngineStub", "Mock inference for image ${image.width}x${image.height}")
        
        return """
        {
            "storeName": "Mock Store",
            "description": "Mock coupon offer - 50% off",
            "code": "MOCK50",
            "expiryDate": "31/12/2024",
            "storeNameSource": "stub",
            "storeNameEvidence": ["Mock Store"],
            "needsAttention": true
        }
        """.trimIndent()
    }
    
    override fun release() {
        Log.d("MLCEngineStub", "Released mock MLC engine")
    }
}
