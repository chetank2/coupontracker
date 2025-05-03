package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.util.CouponInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Model-based OCR Service that uses the trained model for coupon recognition
 * without relying on external APIs
 */
class ModelBasedOCRService(private val context: Context) {
    private val TAG = "ModelBasedOCRService"

    // OCR components
    private val mlKitTextRecognition = MLKitRealTextRecognition()
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor()
    private val tesseractOCRHelper = TesseractOCRHelper(context)
    private val couponPatternRecognizer = CouponPatternRecognizer(context)

    // Track service availability
    private var tesseractAvailable = AtomicBoolean(false)
    private var patternRecognizerAvailable = AtomicBoolean(false)

    // Flag to use custom Tesseract model if available
    private var useCustomModel = tesseractOCRHelper.isCustomModelAvailable()

    /**
     * Initialize the service
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                // Initialize Tesseract with custom model if available
                tesseractAvailable.set(tesseractOCRHelper.initialize(
                    language = if (useCustomModel) "coupon" else "eng",
                    useCustomModel = useCustomModel
                ))

                // Initialize pattern recognizer
                patternRecognizerAvailable.set(couponPatternRecognizer.initialize())

                Log.d(TAG, "Service initialized. Tesseract available: ${tesseractAvailable.get()}, " +
                        "Pattern recognizer available: ${patternRecognizerAvailable.get()}, " +
                        "Using custom model: $useCustomModel")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing service", e)
                tesseractAvailable.set(false)
                patternRecognizerAvailable.set(false)
            }
        }
    }

    /**
     * Process an image to extract coupon information
     */
    suspend fun processCouponImage(bitmap: Bitmap): CouponInfo = coroutineScope {
        try {
            Log.d(TAG, "Processing coupon image")

            // Step 1: Preprocess the image for better recognition
            val preprocessedBitmap = imagePreprocessor.preprocess(bitmap)

            // Step 2: Extract text using ML Kit (always available on device)
            val mlKitText = mlKitTextRecognition.processImageFromBitmap(preprocessedBitmap)

            // Step 3: Use pattern recognition if available
            val patternResults = if (patternRecognizerAvailable.get()) {
                couponPatternRecognizer.recognizeElements(preprocessedBitmap)
            } else {
                emptyMap()
            }

            // Step 4: Use Tesseract OCR if available
            val tesseractText = if (tesseractAvailable.get()) {
                tesseractOCRHelper.processImageAccurate(preprocessedBitmap, useCustomModel)
            } else {
                ""
            }

            // Step 5: Combine results to create the best possible coupon info
            val combinedInfo = combineResults(mlKitText, tesseractText, patternResults)

            Log.d(TAG, "Coupon processing complete: $combinedInfo")
            combinedInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupon image", e)
            // Return a minimal coupon info object if processing fails
            CouponInfo(
                storeName = "Unknown Store",
                description = "Error processing coupon",
                expiryDate = null,
                cashbackAmount = null,
                redeemCode = null
            )
        }
    }

    /**
     * Combine results from different recognition methods
     */
    private fun combineResults(
        mlKitText: String,
        tesseractText: String,
        patternResults: Map<String, String>
    ): CouponInfo {
        // Start with pattern results as they're most reliable
        val storeName = patternResults["store"] ?: extractStoreName(mlKitText, tesseractText)
        val code = patternResults["code"] ?: extractCouponCode(mlKitText, tesseractText)
        val expiryDateStr = patternResults["expiry"] ?: extractExpiryDate(mlKitText, tesseractText)
        val amountStr = patternResults["amount"] ?: extractAmount(mlKitText, tesseractText)

        // Extract description from the full text
        val description = extractDescription(mlKitText, tesseractText)

        // Parse amount to Double if possible
        val amount = parseAmount(amountStr)

        // Parse expiry date if available
        val expiryDate = DateParser.parseDate(expiryDateStr)

        return CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackAmount = amount,
            redeemCode = code
        )
    }

    /**
     * Extract store name from text
     */
    private fun extractStoreName(mlKitText: String, tesseractText: String): String? {
        // Combine texts for better extraction
        val combinedText = "$mlKitText\n$tesseractText"

        // Use TextExtractor to get store name
        return textExtractor.extractStoreName(combinedText)
    }

    /**
     * Extract coupon code from text
     */
    private fun extractCouponCode(mlKitText: String, tesseractText: String): String? {
        // Combine texts for better extraction
        val combinedText = "$mlKitText\n$tesseractText"

        // Use TextExtractor to get coupon code
        return textExtractor.extractRedeemCode(combinedText)
    }

    /**
     * Extract expiry date from text
     */
    private fun extractExpiryDate(mlKitText: String, tesseractText: String): String? {
        // Combine texts for better extraction
        val combinedText = "$mlKitText\n$tesseractText"

        // Use TextExtractor to get expiry date
        val date = textExtractor.extractExpiryDate(combinedText)
        return if (date != null) {
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            sdf.format(date)
        } else {
            null
        }
    }

    /**
     * Extract amount from text
     */
    private fun extractAmount(mlKitText: String, tesseractText: String): String? {
        // Combine texts for better extraction
        val combinedText = "$mlKitText\n$tesseractText"

        // Use TextExtractor to get amount
        val amount = textExtractor.extractCashbackAmount(combinedText)
        return amount?.toString()
    }

    /**
     * Extract description from text
     */
    private fun extractDescription(mlKitText: String, tesseractText: String): String? {
        // Combine texts for better extraction
        val combinedText = "$mlKitText\n$tesseractText"

        // Use TextExtractor to get description
        return textExtractor.extractDescription(combinedText)
    }

    /**
     * Parse amount string to Double
     */
    private fun parseAmount(amountStr: String?): Double? {
        if (amountStr == null || amountStr.isBlank()) return null

        try {
            // Remove currency symbols and other non-numeric characters
            val numericStr = amountStr.replace(Regex("[^0-9.]"), "")
            if (numericStr.isBlank()) return null

            return numericStr.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing amount: $amountStr", e)
            return null
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        tesseractOCRHelper.cleanup()
    }
}
