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

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Utility class to process images and extract coupon information using the trained model
 */
class ImageProcessor(
    private val context: Context
) {
    private val TAG = "ImageProcessor"
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val textExtractor = TextExtractor()

    // Tesseract OCR helper
    private var tesseractOCRHelper: TesseractOCRHelper = TesseractOCRHelper(context)
    private var tesseractLanguageManager: TesseractLanguageManager = TesseractLanguageManager(context)

    // Coupon pattern recognizer
    private var couponPatternRecognizer: CouponPatternRecognizer = CouponPatternRecognizer(context)

    // Model-based OCR service
    private var modelBasedOCRService: ModelBasedOCRService = ModelBasedOCRService(context)

    // Default to using the model-based OCR service
    private var useModelBasedOcr = true

    // Secure preferences manager
    private val securePreferencesManager = SecurePreferencesManager(context)

    // SharedPreferences listener - we've simplified the OCR approach
    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // No need to handle preference changes anymore
        }

    init {
        // Initialize secure preferences manager
        securePreferencesManager.initialize()

        // Register for preference changes
        securePreferencesManager.registerOnSharedPreferenceChangeListener(sharedPreferencesListener)

        Log.d(TAG, "ImageProcessor initialized with model-based OCR")

        // Initialize model-based OCR service in a background thread
        MainScope().launch {
            try {
                modelBasedOCRService.initialize()
                Log.d(TAG, "Model-based OCR service initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing model-based OCR service", e)
            }
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
            val bitmap = getBitmapFromUri(imageUri)
            Log.d(TAG, "Successfully loaded bitmap from URI")

            return@withContext processImage(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }

    /**
     * Process a bitmap image and extract coupon information
     * @param bitmap The bitmap to process
     * @return The extracted coupon information
     */
    suspend fun processImage(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing bitmap image")

            // Use Model-based OCR service by default with fallbacks
            val result = try {
                Log.d(TAG, "Using Model-based OCR service")
                modelBasedOCRService.processCouponImage(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error using Model-based OCR service, falling back to Pattern Recognizer", e)

                // Try Pattern Recognizer as fallback
                val result = tryPatternRecognizer(bitmap)
                if (result != null) {
                    result
                } else {
                    // Fall back to Tesseract if Pattern Recognizer fails
                    Log.d(TAG, "Pattern Recognizer failed, falling back to Tesseract")
                    val tesseractResult = tryTesseract(bitmap)
                    if (tesseractResult != null) {
                        tesseractResult
                    } else {
                        // Fall back to ML Kit if both fail
                        Log.d(TAG, "Tesseract OCR also failed, falling back to ML Kit")
                        tryMlKit(bitmap)
                    }
                }
            }

            // Return the result from the when expression
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }

    /**
     * Try processing with ML Kit
     * @param bitmap The bitmap to process
     * @return The extracted coupon information
     */
    private suspend fun tryMlKit(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Trying ML Kit OCR")

            // Process with ML Kit
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = suspendCancellableCoroutine<Text> { continuation ->
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { text ->
                        continuation.resume(text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }

            // Extract text
            val text = result.text

            // Use TextExtractor to extract coupon info
            val couponInfo = textExtractor.extractCouponInfo(text)

            Log.d(TAG, "ML Kit OCR result: $couponInfo")
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with ML Kit", e)
            return@withContext CouponInfo()
        }
    }

    /**
     * Try processing with Tesseract OCR
     * @param bitmap The bitmap to process
     * @return The extracted coupon information or null if processing failed
     */
    private suspend fun tryTesseract(bitmap: Bitmap): CouponInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Trying Tesseract OCR")

            // Initialize Tesseract if needed
            if (!tesseractOCRHelper.initialize()) {
                Log.e(TAG, "Failed to initialize Tesseract")
                return@withContext null
            }

            // Extract coupon info using Tesseract
            val couponInfo = tesseractOCRHelper.extractCouponInfo(bitmap, useAccurateMode = true)

            Log.d(TAG, "Tesseract OCR result: $couponInfo")
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Tesseract", e)
            return@withContext null
        }
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
        tesseractOCRHelper.cleanup()
    }


}