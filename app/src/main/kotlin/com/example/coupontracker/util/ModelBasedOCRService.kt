package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.extraction.rules.CouponInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date

/**
 * Model-based OCR Service that uses the trained model for coupon recognition
 * without relying on external APIs
 */
class ModelBasedOCRService(
    private val context: Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine
) {
    private val TAG = "ModelBasedOCRService"

    // OCR components
    private val mlKitTextRecognition = MLKitRealTextRecognition(ocrEngine)
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor()
    private val couponPatternRecognizer = CouponPatternRecognizer(context, ocrEngine)

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
    suspend fun processCouponImage(bitmap: Bitmap, captureTimestamp: Date? = null): CouponInfo = coroutineScope {
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
                fillMissingFields(directInfo, mlKitText, captureTimestamp)
            } else {
                // Fall back to traditional OCR approach
                combineResults(mlKitText, patternResults, captureTimestamp)
            }

            Log.d(TAG, "Coupon processing complete: $combinedInfo")
            combinedInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupon image - propagating exception", e)
            // Don't return hardcoded errors - propagate exception to allow progressive pipeline fallback
            throw e
        }
    }

    /**
     * Fill in missing fields in CouponInfo using OCR results
     * Now treats generic UI words as "missing" so ML Kit can provide real data
     */
    private fun fillMissingFields(
        info: CouponInfo,
        mlKitText: String,
        captureTimestamp: Date?
    ): CouponInfo {
        return info.copy(
            storeName = if (GenericFieldHeuristics.isGenericOrMissing(info.storeName)) {
                extractStoreName(mlKitText) ?: info.storeName
            } else info.storeName,
            
            description = if (GenericFieldHeuristics.isGenericOrMissing(info.description)) {
                extractDescription(mlKitText) ?: info.description
            } else info.description,
            
            redeemCode = if (info.redeemCode.isNullOrBlank()) {
                extractCouponCode(mlKitText)
            } else info.redeemCode,
            
            expiryDate = if (info.expiryDate == null) {
                val dateStr = extractExpiryDate(mlKitText)
                if (dateStr != null) DateParser.parseDate(dateStr, captureTimestamp) else null
            } else info.expiryDate,
            
            cashbackDetail = if (!GenericFieldHeuristics.hasMeaningfulCashback(info.cashbackDetail) &&
                !GenericFieldHeuristics.hasMeaningfulCashback(info.description)) {
                extractCashbackDetail(mlKitText) ?: info.cashbackDetail
            } else info.cashbackDetail
        )
    }
    

    /**
     * Combine results from different recognition methods
     */
    private fun combineResults(
        mlKitText: String,
        patternResults: Map<String, String>,
        captureTimestamp: Date? = null
    ): CouponInfo {
        // Start with pattern results as they're most reliable
        val storeName = patternResults["store"] ?: extractStoreName(mlKitText)
        val code = patternResults["code"] ?: extractCouponCode(mlKitText)
        val expiryDateStr = patternResults["expiry"] ?: extractExpiryDate(mlKitText, captureTimestamp)
        val patternDetail = patternResults["amount"]?.let { DescriptionUtils.formatCashbackDetail(it) ?: it }

        // Extract description from the full text
        val description = extractDescription(mlKitText)

        // Resolve cashback detail
        val cashbackDetail = patternDetail ?: extractCashbackDetail(mlKitText)
        val discountType = when {
            cashbackDetail?.contains("%") == true -> "PERCENTAGE"
            cashbackDetail?.any { it.isDigit() } == true -> "AMOUNT"
            else -> null
        }

        // Parse expiry date if available
        val expiryDate = DateParser.parseDate(expiryDateStr, captureTimestamp)

        return CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackDetail = cashbackDetail,
            redeemCode = code,
            discountType = discountType
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
    private fun extractExpiryDate(mlKitText: String, captureTimestamp: Date? = null): String? {
        // Use TextExtractor to get expiry date
        val date = textExtractor.extractExpiryDate(mlKitText, captureTimestamp)
        return if (date != null) {
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            sdf.format(date)
        } else {
            null
        }
    }

    /**
     * Extract cashback detail from text
     */
    private fun extractCashbackDetail(mlKitText: String): String? {
        return textExtractor.extractCashbackDetail(mlKitText)
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
