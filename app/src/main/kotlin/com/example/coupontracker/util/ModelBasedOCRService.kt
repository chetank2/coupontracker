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
    private val couponPatternRecognizer = CouponPatternRecognizer(context)

    // Model version and metadata
    private val modelVersion = "2.0.0"
    private val modelName = "unified_coupon_recognizer"

    // Track service availability
    private var patternRecognizerAvailable = AtomicBoolean(false)

    /**
     * Initialize the service
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                // Initialize pattern recognizer
                patternRecognizerAvailable.set(couponPatternRecognizer.initialize())

                Log.d(TAG, "Service initialized. Pattern recognizer available: ${patternRecognizerAvailable.get()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing service", e)
                patternRecognizerAvailable.set(false)
            }
        }
    }

    /**
     * Process an image to extract coupon information
     */
    suspend fun processCouponImage(bitmap: Bitmap): CouponInfo = coroutineScope {
        try {
            Log.d(TAG, "Processing coupon image with model $modelName v$modelVersion")

            // Step 1: Preprocess the image for better recognition
            val preprocessedBitmap = imagePreprocessor.preprocess(bitmap)

            // Step 2: Use pattern recognition as primary method (improved in v2.0.0)
            val patternResults = if (patternRecognizerAvailable.get()) {
                Log.d(TAG, "Using pattern recognition as primary method")
                couponPatternRecognizer.recognizeElements(preprocessedBitmap)
            } else {
                Log.w(TAG, "Pattern recognizer not available, falling back to OCR only")
                emptyMap()
            }

            // Step 3: Extract text using ML Kit as backup
            val mlKitText = mlKitTextRecognition.processImageFromBitmap(preprocessedBitmap)

            // Step 4: Combine results to create the best possible coupon info
            val combinedInfo = if (patternResults.isNotEmpty()) {
                // If pattern recognition worked well, convert directly to CouponInfo
                val directInfo = couponPatternRecognizer.convertToCouponInfo(patternResults)

                // Fill in any missing fields with OCR results
                fillMissingFields(directInfo, mlKitText)
            } else {
                // Fall back to traditional OCR approach
                combineResults(mlKitText, patternResults)
            }

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
     * Fill in missing fields in CouponInfo using OCR results
     */
    private fun fillMissingFields(info: CouponInfo, mlKitText: String): CouponInfo {
        return info.copy(
            storeName = if (info.storeName.isBlank()) extractStoreName(mlKitText) ?: "" else info.storeName,
            description = if (info.description.isBlank()) extractDescription(mlKitText) ?: "" else info.description,
            redeemCode = if (info.redeemCode.isNullOrBlank()) extractCouponCode(mlKitText) else info.redeemCode,
            expiryDate = if (info.expiryDate == null) {
                val dateStr = extractExpiryDate(mlKitText)
                if (dateStr != null) DateParser.parseDate(dateStr) else null
            } else info.expiryDate,
            cashbackAmount = if (info.cashbackAmount == null) {
                val amountStr = extractAmount(mlKitText)
                parseAmount(amountStr)
            } else info.cashbackAmount
        )
    }

    /**
     * Combine results from different recognition methods
     */
    private fun combineResults(
        mlKitText: String,
        patternResults: Map<String, String>
    ): CouponInfo {
        // Start with pattern results as they're most reliable
        val storeName = patternResults["store"] ?: extractStoreName(mlKitText)
        val code = patternResults["code"] ?: extractCouponCode(mlKitText)
        val expiryDateStr = patternResults["expiry"] ?: extractExpiryDate(mlKitText)
        val amountStr = patternResults["amount"] ?: extractAmount(mlKitText)

        // Extract description from the full text
        val description = extractDescription(mlKitText)

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
    private fun extractStoreName(mlKitText: String): String? {
        // Use TextExtractor to get store name
        return textExtractor.extractStoreName(mlKitText)
    }

    /**
     * Extract coupon code from text
     */
    private fun extractCouponCode(mlKitText: String): String? {
        // Use TextExtractor to get coupon code
        return textExtractor.extractRedeemCode(mlKitText)
    }

    /**
     * Extract expiry date from text
     */
    private fun extractExpiryDate(mlKitText: String): String? {
        // Use TextExtractor to get expiry date
        val date = textExtractor.extractExpiryDate(mlKitText)
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
    private fun extractAmount(mlKitText: String): String? {
        // Use TextExtractor to get amount
        val amount = textExtractor.extractCashbackAmount(mlKitText)
        return amount?.toString()
    }

    /**
     * Extract description from text
     */
    private fun extractDescription(mlKitText: String): String? {
        // Use TextExtractor to get description
        return textExtractor.extractDescription(mlKitText)
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
        // No resources to clean up
    }


}
