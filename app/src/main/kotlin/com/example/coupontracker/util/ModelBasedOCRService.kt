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

    // Model version and metadata
    private val modelVersion = "2.0.0"
    private val modelName = "unified_coupon_recognizer"

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

            // Step 4: Use Tesseract OCR for specific fields that pattern recognition missed
            val tesseractText = if (tesseractAvailable.get()) {
                // Only run Tesseract if we're missing key fields from pattern recognition
                val missingKeyFields = listOf("store", "code", "expiry").any { !patternResults.containsKey(it) }
                if (missingKeyFields || patternResults.isEmpty()) {
                    Log.d(TAG, "Using Tesseract OCR for missing fields")
                    tesseractOCRHelper.processImageAccurate(preprocessedBitmap, useCustomModel)
                } else {
                    ""
                }
            } else {
                ""
            }

            // Step 5: Combine results to create the best possible coupon info
            val combinedInfo = if (patternResults.isNotEmpty()) {
                // If pattern recognition worked well, convert directly to CouponInfo
                val directInfo = couponPatternRecognizer.convertToCouponInfo(patternResults)

                // Fill in any missing fields with OCR results
                fillMissingFields(directInfo, mlKitText, tesseractText)
            } else {
                // Fall back to traditional OCR approach
                combineResults(mlKitText, tesseractText, patternResults)
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
    private fun fillMissingFields(info: CouponInfo, mlKitText: String, tesseractText: String): CouponInfo {
        val combinedText = "$mlKitText\n$tesseractText"

        return info.copy(
            storeName = if (info.storeName.isBlank()) extractStoreName(mlKitText, tesseractText) ?: "" else info.storeName,
            description = if (info.description.isBlank()) extractDescription(mlKitText, tesseractText) ?: "" else info.description,
            redeemCode = if (info.redeemCode.isNullOrBlank()) extractCouponCode(mlKitText, tesseractText) else info.redeemCode,
            expiryDate = if (info.expiryDate == null) {
                val dateStr = extractExpiryDate(mlKitText, tesseractText)
                if (dateStr != null) DateParser.parseDate(dateStr) else null
            } else info.expiryDate,
            cashbackAmount = if (info.cashbackAmount == null) {
                val amountStr = extractAmount(mlKitText, tesseractText)
                parseAmount(amountStr)
            } else info.cashbackAmount
        )
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
