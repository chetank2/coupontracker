package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.TesseractOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date

/**
 * Utility class to process images and extract coupon information using progressive extraction pipeline
 */
class ImageProcessor(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val telemetryService: ExtractionTelemetryService,
    private val injectedLocalLlmOcrService: LocalLlmOcrService? = null,
    private val progressiveExtractionService: com.example.coupontracker.extraction.ProgressiveExtractionService? = null
) {
    private val TAG = "ImageProcessor"
    private val textExtractor = TextExtractor()

    // Coupon pattern recognizer (legacy fallback)
    private var couponPatternRecognizer: CouponPatternRecognizer = CouponPatternRecognizer(context, ocrEngine)

    // OCR services (legacy fallback)
    private var modelBasedOCRService: ModelBasedOCRService = ModelBasedOCRService(context, ocrEngine)
    private var localLlmOcrService: LocalLlmOcrService = injectedLocalLlmOcrService ?: LocalLlmOcrService(context, ocrEngine)

    // Default to using the model-based OCR service
    private var useModelBasedOcr = true

    // Secure preferences manager - lazy initialization to avoid ANR
    private val securePreferencesManager by lazy { SecurePreferencesManager(context) }
    
    // Feature flag for progressive extraction
    private val USE_PROGRESSIVE_EXTRACTION = true  // Set to true to use new pipeline

    // SharedPreferences listener - we've simplified the OCR approach
    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // No need to handle preference changes anymore
        }

    init {
        Log.d(TAG, "ImageProcessor initialized with model-based OCR")

        // Initialize everything in background thread to avoid ANR
        MainScope().launch(Dispatchers.IO) {
            try {
                // Initialize secure preferences manager in background
                securePreferencesManager.initialize()
                
                // Register for preference changes
                securePreferencesManager.registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
                
                // Initialize model-based OCR service
                modelBasedOCRService.initialize()
                Log.d(TAG, "ImageProcessor and services initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing ImageProcessor services", e)
            }
        }
    }

    /**
     * Safely recycle bitmap to prevent memory leaks
     */
    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
                Log.d(TAG, "Bitmap recycled successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error recycling bitmap", e)
        }
    }

    /**
     * Process an image URI and extract coupon information based on the selected API priority
     * @param imageUri The URI of the image to process
     * @return CouponInfo object containing extracted information
     */
    suspend fun processImage(imageUri: Uri): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing image: $imageUri")

            // Extract capture timestamp from image metadata
            val captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
            if (captureTimestamp != null) {
                Log.d(TAG, "Found image capture timestamp: $captureTimestamp")
            } else {
                Log.w(TAG, "No capture timestamp found, using fallback date")
            }
            
            val bitmap = getBitmapFromUri(imageUri)
            Log.d(TAG, "Successfully loaded bitmap from URI")

            return@withContext processImage(bitmap, captureTimestamp, imageUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }

    /**
     * Process a bitmap image and extract coupon information
     * @param bitmap The bitmap to process
     * @param captureTimestamp The timestamp when the image was captured (for relative date calculations)
     * @return The extracted coupon information
     */
    suspend fun processImage(
        bitmap: Bitmap,
        captureTimestamp: Date? = null,
        originalImageUri: String? = null
    ): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing bitmap image")

            // Check if progressive extraction is enabled and available
            if (USE_PROGRESSIVE_EXTRACTION) {
                if (progressiveExtractionService == null) {
                    Log.e(TAG, "❌ Progressive extraction is ENABLED but service is NULL! Check Hilt injection.")
                } else {
                Log.d(TAG, "✨ Using PROGRESSIVE extraction pipeline")
                return@withContext processWithProgressivePipeline(bitmap, captureTimestamp, originalImageUri)
                }
            } else {
                Log.d(TAG, "ℹ️  Progressive extraction is DISABLED via feature flag")
            }

            // Legacy extraction flow (fallback)
            Log.d(TAG, "Using LEGACY extraction flow")
            
            // Get the selected API type from preferences
            val selectedApiType = securePreferencesManager.getSelectedApiType()
            val llmModelDownloaded = securePreferencesManager.getLlmModelDownloaded()
            
            val result = when (selectedApiType) {
                ApiType.LOCAL_LLM -> {
                    if (llmModelDownloaded) {
                        Log.d(TAG, "Using Local LLM OCR service")
                        try {
                            localLlmOcrService.processCouponImage(bitmap, captureTimestamp)
                        } catch (e: Exception) {
                            Log.e(TAG, "Local LLM failed, falling back to Model-based OCR", e)
                            fallbackToModelBasedOcr(bitmap, captureTimestamp, reason = "local_llm_exception")
                        }
                    } else {
                        Log.w(TAG, "Local LLM selected but model not downloaded, falling back to Model-based OCR")
                        fallbackToModelBasedOcr(bitmap, captureTimestamp, reason = "local_llm_not_downloaded")
                    }
                }
                ApiType.MODEL_BASED -> {
                    Log.d(TAG, "Using Model-based OCR service")
                    fallbackToModelBasedOcr(bitmap, captureTimestamp, reason = "model_based_selected")
                }
                ApiType.ML_KIT_ONLY -> {
                    Log.d(TAG, "Using ML Kit OCR only")
                    tryMlKit(
                        bitmap,
                        captureTimestamp,
                        reason = "mlkit_only_selection",
                        attemptedEngines = listOf("MLKIT")
                    )
                }
            }

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }
    
    /**
     * Process image using progressive extraction pipeline
     */
    private suspend fun processWithProgressivePipeline(
        bitmap: Bitmap,
        captureTimestamp: Date? = null,
        originalImageUri: String? = null
    ): CouponInfo {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🚀 Progressive Pipeline - Starting extraction")
                
                // Step 1: Extract OCR text
                Log.d(TAG, "Step 1: Extracting OCR text using ${ocrEngine.javaClass.simpleName}")
                val ocrText = try {
                    ocrEngine.recognize(bitmap)
                } catch (ocrException: Exception) {
                    Log.e(TAG, "❌ OCR engine failed", ocrException)
                    // Try to continue with empty text, let progressive pipeline handle it
                    ""
                }
                
                Log.d(TAG, "OCR extracted ${ocrText.length} characters")
                if (ocrText.length > 0) {
                    Log.d(TAG, "OCR preview: ${ocrText.take(100)}...")
                }
                
                if (ocrText.isBlank()) {
                    Log.w(TAG, "⚠️  OCR text is empty, falling back to legacy")
                    return@withContext fallbackToModelBasedOcr(
                        bitmap,
                        captureTimestamp,
                        reason = "progressive_empty_text"
                    )
                }
                
                // Step 2: Call progressive extraction
                Log.d(TAG, "Step 2: Calling progressive extraction service")
                val progressiveResult = progressiveExtractionService!!.extractCoupon(
                    androidContext = context,
                    image = bitmap,
                    ocrText = ocrText,
                    ocrBlocks = emptyList(),
                    imageUri = originalImageUri ?: "bitmap://${System.currentTimeMillis()}",
                    captureTimestamp = captureTimestamp  // FIXED: Pass screenshot timestamp for relative date calculation
                )
                
                Log.d(TAG, "✅ Progressive extraction SUCCESS:")
                Log.d(TAG, "  - Store: '${progressiveResult.coupon.storeName}'")
                Log.d(TAG, "  - Description: '${progressiveResult.coupon.description.take(80)}...'")
                Log.d(TAG, "  - Amount: ${progressiveResult.coupon.cashbackAmount}")
                Log.d(TAG, "  - Confidence: ${progressiveResult.confidence}")
                Log.d(TAG, "  - Passes used: ${progressiveResult.passesUsed}")
                
                // Convert Coupon to CouponInfo
                val couponInfo = CouponInfo(
                    storeName = progressiveResult.coupon.storeName,
                    description = progressiveResult.coupon.description,
                    cashbackAmount = if (progressiveResult.coupon.cashbackAmount > 0.0) 
                        progressiveResult.coupon.cashbackAmount else null,
                    expiryDate = progressiveResult.coupon.expiryDate,
                    redeemCode = progressiveResult.coupon.redeemCode,
                    category = progressiveResult.coupon.category,
                    status = progressiveResult.coupon.status
                )
                
                Log.d(TAG, "✅ Converted to CouponInfo successfully")
                return@withContext couponInfo
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Progressive extraction FAILED with exception: ${e.message}", e)
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                Log.d(TAG, "Falling back to legacy extraction flow")
                return@withContext fallbackToModelBasedOcr(
                    bitmap,
                    captureTimestamp,
                    reason = "progressive_exception"
                )
            }
        }
    }
    
    /**
     * Fallback to model-based OCR with existing fallback chain
     */
    private suspend fun fallbackToModelBasedOcr(
        bitmap: Bitmap,
        captureTimestamp: Date? = null,
        reason: String = "legacy_flow"
    ): CouponInfo {
        return try {
            Log.d(TAG, "Using Model-based OCR service")
            modelBasedOCRService.processCouponImage(bitmap, captureTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error using Model-based OCR service, falling back to Pattern Recognizer", e)

            // Try Pattern Recognizer as fallback
            val attempted = mutableListOf("TESSERACT", "MODEL_BASED")
            if (reason.startsWith("progressive")) {
                attempted.add(1, "PROGRESSIVE")
            }
            val result = tryPatternRecognizer(bitmap)
            if (result != null) {
                result
            } else {
                attempted.add("PATTERN_RECOGNIZER")
                Log.d(TAG, "Pattern Recognizer failed, falling back to ML Kit")
                tryMlKit(
                    bitmap,
                    captureTimestamp,
                    reason = "$reason/model_based_exception",
                    cause = e,
                    attemptedEngines = attempted
                )
            }
        }
    }

    /**
     * Try processing with Tesseract OCR
     * @param bitmap The bitmap to process
     * @param captureTimestamp The timestamp when the image was captured (for relative date calculations)
     * @return The extracted coupon information
     */
    private suspend fun tryMlKit(
        bitmap: Bitmap,
        captureTimestamp: Date? = null,
        reason: String,
        cause: Throwable? = null,
        attemptedEngines: List<String> = listOf("TESSERACT")
    ): CouponInfo = withContext(Dispatchers.IO) {
        recordMlKitFallback(reason, cause, attemptedEngines)
        try {
            Log.d(TAG, "Trying ML Kit fallback via ${ocrEngine.javaClass.simpleName}")

            // Process with fallback OCR engine (currently Tesseract)
            val text = ocrEngine.recognize(bitmap)

            // Use TextExtractor to extract coupon info
            val couponInfo = textExtractor.extractCouponInfoSync(text, captureTimestamp)

            Log.d(TAG, "Tesseract OCR result: $couponInfo")
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Tesseract", e)
            return@withContext CouponInfo()
        }
    }

    private fun recordMlKitFallback(
        reason: String,
        cause: Throwable? = null,
        attemptedEngines: List<String> = listOf("TESSERACT")
    ) {
        val initStats = (ocrEngine as? TesseractOcrEngine)?.lastInitializationStats()
        val runPath = buildMlKitFallbackRunPath(
            MlKitFallbackContext(
                reason = reason,
                cause = cause,
                initStats = initStats,
                ocrReady = ocrEngine.isReady(),
                attemptedEngines = attemptedEngines
            )
        )
        telemetryService.trackRunPath(runPath)
    }



    /**
     * Try processing with Pattern Recognizer
     * @param bitmap The bitmap to process
     * @return The extracted coupon information or null if processing failed
     */
    private suspend fun tryPatternRecognizer(bitmap: Bitmap): CouponInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Trying Pattern Recognizer")

            // Initialize Pattern Recognizer if needed
            if (!couponPatternRecognizer.initialize()) {
                Log.e(TAG, "Failed to initialize Pattern Recognizer")
                return@withContext null
            }

            // Recognize elements using pattern recognizer
            val elements = couponPatternRecognizer.recognizeElements(bitmap)

            // If no elements were recognized, return null
            if (elements.isEmpty()) {
                Log.d(TAG, "Pattern Recognizer found no elements")
                return@withContext null
            }

            // Convert elements to CouponInfo
            val couponInfo = couponPatternRecognizer.convertToCouponInfo(elements)

            Log.d(TAG, "Pattern Recognizer result: $couponInfo")
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Pattern Recognizer", e)
            return@withContext null
        }
    }

    /**
     * Get bitmap from URI
     * @param uri The URI to get the bitmap from
     * @return The bitmap
     */
    private fun getBitmapFromUri(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        // No resources to clean up
    }


}