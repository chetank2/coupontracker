package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Local LLM OCR Service using MiniCPM-Llama3-V2.5
 * Provides structured coupon extraction using on-device vision-language model
 */
class LocalLlmOcrService(
    private val context: Context,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null
) {
    
    companion object {
        private const val TAG = "LocalLlmOcrService"

        // Inference timeout (30 seconds)
        private const val INFERENCE_TIMEOUT_MS = 30000L

        // Model version tracking
        private const val SERVICE_VERSION = "1.0.0"
        private const val SUPPORTED_MODEL_VERSION = "v2.5-q4-android"

        fun cleanDescription(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return ""
            }

            val timestampPattern = Regex("^\\d{1,2}:\\d{2}")
            val singleLetterPattern = Regex("""^[A-Za-z]$""")

            val cleanedLines = raw.lines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) {
                    return@mapNotNull null
                }

                if (timestampPattern.containsMatchIn(trimmed)) {
                    return@mapNotNull null
                }

                if (singleLetterPattern.matches(trimmed)) {
                    return@mapNotNull null
                }

                if (trimmed.equals("x", ignoreCase = true)) {
                    return@mapNotNull null
                }

                val uppercaseLetters = trimmed.count { it.isLetter() && it.isUpperCase() }
                val lowercaseLetters = trimmed.count { it.isLetter() && it.isLowerCase() }

                val normalized = if (uppercaseLetters > 0 && uppercaseLetters >= lowercaseLetters && trimmed.contains(' ')) {
                    trimmed.lowercase(Locale.getDefault()).replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                    }
                } else {
                    trimmed
                }

                normalized
            }

            return cleanedLines.joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun normalizeStoreName(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return ""
            }

            val tokens = raw.trim().split(Regex("\\s+"))
            val filteredTokens = tokens.dropWhile { token ->
                GenericFieldHeuristics.shouldDiscardStorePrefix(token)
            }

            return filteredTokens.joinToString(" ") { it.trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
    
    // Dependencies
    private val llmRuntime = injectedLlmRuntimeManager ?: LlmRuntimeManager.getInstance(context)
    private val telemetryService = LlmTelemetryService.getInstance(context)
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor() // Fallback
    
    /**
     * Check if the LLM service is available
     */
    fun isServiceAvailable(): Boolean {
        return llmRuntime.isModelAvailable()
    }
    
    /**
     * Get service status information
     */
    fun getServiceStatus(): LlmServiceStatus {
        val modelInfo = llmRuntime.getModelInfo()
        val memoryStats = llmRuntime.getMemoryStats()
        
        return LlmServiceStatus(
            isAvailable = modelInfo.isAvailable,
            isModelLoaded = modelInfo.isLoaded,
            modelVersion = modelInfo.version,
            serviceVersion = SERVICE_VERSION,
            modelSizeMB = modelInfo.sizeMB,
            memoryUsageMB = memoryStats.modelLoadedMemoryMB,
            referenceCount = modelInfo.referenceCount
        )
    }
    
    /**
     * Process coupon image using local LLM
     * Main entry point that mirrors ModelBasedOCRService.processCouponImage
     */
    suspend fun processCouponImage(bitmap: Bitmap, captureTimestamp: Date? = null): CouponInfo = coroutineScope {
        val startTime = System.currentTimeMillis()
        var memoryUsage = 0L
        var extractedFieldCount = 0
        var fallbackUsed: String? = null
        
        try {
            Log.d(TAG, "Processing coupon with MiniCPM-Llama3-V2.5")
            
            // Step 1: Validate input
            if (bitmap.isRecycled) {
                throw IllegalArgumentException("Input bitmap is recycled")
            }
            
            // Step 2: Check service availability
            if (!isServiceAvailable()) {
                throw IllegalStateException("LLM model not available on device")
            }
            
            // Step 3: Preprocess image for MiniCPM (768px long side, RGB format)
            val preprocessedBitmap = preprocessForMiniCPM(bitmap)
            Log.d(TAG, "Preprocessed image for MiniCPM: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
            
            // Step 4: Create structured extraction prompt
            val prompt = createCouponExtractionPrompt()
            
            // Step 5: Run LLM inference with timeout and memory tracking
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runInference(preprocessedBitmap, prompt)
            }
            
            // Step 6: Parse and validate response
            val couponInfo = if (llmResponse != null) {
                parseLlmResponseToCouponInfo(llmResponse)
            } else {
                // Record timeout and throw
                val duration = System.currentTimeMillis() - startTime
                telemetryService.recordTimeout(duration, memoryUsage)
                throw Exception("LLM inference timed out or returned null")
            }
            
            // Step 7: Validate extraction quality and count fields
            validateExtractionQuality(couponInfo)
            extractedFieldCount = countExtractedFields(couponInfo)
            
            // Record successful inference
            val duration = System.currentTimeMillis() - startTime
            telemetryService.recordInference(
                durationMs = duration,
                success = true,
                extractedFieldCount = extractedFieldCount,
                memoryUsageMB = memoryUsage
            )
            
            Log.d(TAG, "MiniCPM extraction completed successfully in ${duration}ms")
            couponInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)
            
            // Record failure
            val duration = System.currentTimeMillis() - startTime
            val errorType = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> "TIMEOUT"
                e.message?.contains("memory", ignoreCase = true) == true -> "MEMORY"
                e.message?.contains("model", ignoreCase = true) == true -> "MODEL_ERROR"
                else -> "PROCESSING_ERROR"
            }
            
            // Fallback to traditional OCR
            Log.d(TAG, "Falling back to traditional OCR")
            val fallbackResult = fallbackToTraditionalOCR(bitmap, captureTimestamp)
            
            // Determine which fallback was used based on result quality
            fallbackUsed = if (fallbackResult.storeName != "Unknown Store" || 
                             !fallbackResult.redeemCode.isNullOrBlank()) {
                "ML_KIT"
            } else {
                "MODEL_BASED"
            }
            
            extractedFieldCount = countExtractedFields(fallbackResult)
            
            telemetryService.recordInference(
                durationMs = duration,
                success = false,
                errorType = errorType,
                fallbackUsed = fallbackUsed,
                extractedFieldCount = extractedFieldCount,
                memoryUsageMB = memoryUsage
            )
            
            fallbackResult
        }
    }
    
    private fun countExtractedFields(couponInfo: CouponInfo): Int {
        var count = 0
        if (couponInfo.storeName != "Unknown Store") count++
        if (!couponInfo.redeemCode.isNullOrBlank()) count++
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) count++
        if (couponInfo.expiryDate != null) count++
        if (couponInfo.description != "Coupon offer") count++
        if (couponInfo.minimumPurchase != null && couponInfo.minimumPurchase > 0) count++
        return count
    }
    
    /**
     * Create optimized structured prompt for coupon extraction
     */
    private fun createCouponExtractionPrompt(): String {
        return """
        You are a precise coupon information extractor. Analyze this coupon image and extract structured information in JSON format.

        CRITICAL RULES:
        1. Extract ONLY information clearly visible in the image
        2. Return valid JSON with the exact schema below
        3. Use null for missing information (never use "Unknown")
        4. Be conservative - if unsure, use null
        5. Preserve original currency symbols and formatting
        6. For dates, use format: "YYYY-MM-DD" or "DD/MM/YYYY" as shown
        7. For amounts, include currency symbol: "₹100", "$50", "20%"

        REQUIRED JSON FORMAT:
        {
            "storeName": "Store/brand name (required)",
            "description": "Offer description (required)", 
            "cashbackAmount": "Amount/percentage off with currency or null",
            "redeemCode": "Promo/coupon code or null",
            "expiryDate": "Expiry date in original format or null",
            "minOrderAmount": "Minimum order value with currency or null"
        }

        EXTRACTION GUIDE:
        - Store Name: Look for logos, brand names, prominent text
        - Description: Main offer details in 1-2 sentences
        - Cashback Amount: Exact discount (20%, ₹100, $50 off)
        - Redeem Code: Alphanumeric codes (SAVE20, FIRST50, etc.)
        - Expiry Date: Look for "valid till", "expires", date stamps
        - Min Order: Find "minimum order", "above ₹X", "orders over"

        QUALITY CHECKS:
        - Verify store name appears in image
        - Confirm discount amounts are realistic
        - Validate promo codes don't contain spaces
        - Ensure amounts include proper currency symbols

        Return ONLY the JSON object, no additional text or formatting.
        """.trimIndent()
    }
    
    /**
     * Parse LLM JSON response to CouponInfo object
     */
    private fun parseLlmResponseToCouponInfo(response: String): CouponInfo {
        return try {
            // Clean response (remove any markdown formatting)
            val cleanResponse = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val json = JSONObject(cleanResponse)
            
            // Extract fields with fallbacks and generic filtering
            val storeName = json.optString("storeName", "Unknown Store").let { raw ->
                val normalized = normalizeStoreName(raw)
                when {
                    normalized.isBlank() || normalized.equals("Unknown", ignoreCase = true) -> "Unknown Store"
                    GenericFieldHeuristics.isGenericOrMissing(normalized) -> "Unknown Store"
                    else -> normalized
                }
            }
            
            val description = json.optString("description", "").let {
                val cleaned = cleanDescription(it)
                when {
                    cleaned.isBlank() -> "Coupon offer"
                    cleaned.equals("Unknown", ignoreCase = true) -> "Coupon offer"
                    GenericFieldHeuristics.isGenericOrMissing(cleaned) -> "Coupon offer"
                    else -> cleaned.take(100)
                }
            }
            
            // Note: 'amount' field is handled by cashbackAmount, keeping for future use
            // val amount = json.optString("amount").let {
            //     if (it.isBlank() || it == "Unknown") null else it
            // }
            
            // Try both 'redeemCode' (from prompt) and 'code' (fallback) to handle both field names
            val code = (json.optString("redeemCode").takeIf { it.isNotBlank() && it != "Unknown" }
                ?: json.optString("code").takeIf { it.isNotBlank() && it != "Unknown" })
                ?.trim()?.uppercase()
                ?.takeIf { !GenericFieldHeuristics.isGenericOrMissing(it) }
            
            val expiryDate = json.optString("expiryDate").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val cashbackAmount = json.optString("cashbackAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val minOrderAmount = json.optString("minOrderAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            // Check for duplicate values between fields (common LLM issue)
            val finalStoreName = if (GenericFieldHeuristics.areDuplicateFields(storeName, code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$storeName' - downgrading store name")
                "Unknown Store"
            } else storeName
            
            val finalCode = if (GenericFieldHeuristics.areDuplicateFields(storeName, code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$code' - clearing redeem code")
                null
            } else code
            
            // Parse expiry date if available
            val parsedExpiryDate = expiryDate?.let { dateStr ->
                try {
                    DateParser.parseDate(dateStr)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse expiry date: $dateStr", e)
                    null
                }
            }
            
            return CouponInfo(
                storeName = finalStoreName,
                description = description,
                expiryDate = parsedExpiryDate,
                cashbackAmount = parseNumericValue(cashbackAmount),
                redeemCode = finalCode,
                minimumPurchase = parseNumericValue(minOrderAmount)
            )
            
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse LLM JSON response: $response", e)
            throw IllegalStateException("Invalid JSON response from LLM: ${e.message}")
        }
    }
    
    /**
     * Parse numeric value from currency/percentage strings
     * Handles formats like: ₹150, $25, 25%, 150.50, etc.
     */
     private fun parseNumericValue(value: String?): Double? {
        if (value.isNullOrBlank() || value == "Unknown") return null
        
        return try {
            // Remove currency symbols and extract numeric value
            val numericString = value
                .replace(Regex("[₹$£€¥%,\\s]"), "") // Remove common currency symbols, %, commas, spaces
                .replace(Regex("[^0-9.]"), "") // Keep only digits and decimal points
                .trim()
            
            if (numericString.isBlank()) {
                Log.w(TAG, "No numeric value found in: '$value'")
                null
            } else {
                val parsed = numericString.toDoubleOrNull()
                Log.d(TAG, "Parsed '$value' → $parsed")
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse numeric value: '$value'", e)
            null
        }
    }
    
    /**
     * Preprocess bitmap specifically for MiniCPM-Llama3-V2.5 vision model
     * Ensures optimal input format: 768px long side, RGB format, proper aspect ratio
     */
    private fun preprocessForMiniCPM(bitmap: Bitmap): Bitmap {
        val targetLongSide = 768
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        // Calculate scaling to fit target long side
        val longSide = maxOf(originalWidth, originalHeight)
        val scale = if (longSide > targetLongSide) {
            targetLongSide.toFloat() / longSide
        } else {
            1.0f // Don't upscale small images
        }
        
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        
        // Create scaled bitmap with RGB_565 format (efficient for inference)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Ensure RGB format for MiniCPM
        val rgbBitmap = if (scaledBitmap.config != Bitmap.Config.RGB_565) {
            scaledBitmap.copy(Bitmap.Config.RGB_565, false)
        } else {
            scaledBitmap
        }
        
        Log.d(TAG, "MiniCPM preprocessing: ${originalWidth}x${originalHeight} -> ${newWidth}x${newHeight} (scale: $scale)")
        
        return rgbBitmap
    }
    
    /**
     * Validate extraction quality and completeness
     */
    private fun validateExtractionQuality(couponInfo: CouponInfo) {
        var qualityScore = 0
        var failureReason: String? = null
        
        // Check essential fields
        if (couponInfo.storeName != "Unknown Store") qualityScore += 30
        if (!couponInfo.redeemCode.isNullOrBlank()) qualityScore += 25
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) qualityScore += 25
        if (couponInfo.expiryDate != null) qualityScore += 10
        if (couponInfo.description != "Coupon offer") qualityScore += 10
        
        // Additional quality checks for boilerplate/generic content
        val hasGenericStoreName = GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)
        val hasGenericDescription = GenericFieldHeuristics.isGenericOrMissing(couponInfo.description)
        val hasGenericCode = GenericFieldHeuristics.isGenericOrMissing(couponInfo.redeemCode)
        val hasMeaninglessAmount = GenericFieldHeuristics.isZeroOrMeaningless(couponInfo.cashbackAmount)
        
        // Check for duplicate fields (already handled in parsing, but validate here too)
        val hasDuplicateFields = GenericFieldHeuristics.areDuplicateFields(
            couponInfo.storeName, couponInfo.redeemCode
        )
        
        Log.d(TAG, "Extraction quality score: $qualityScore/100")
        Log.d(TAG, "Generic checks - Store: $hasGenericStoreName, Desc: $hasGenericDescription, Code: $hasGenericCode, Amount: $hasMeaninglessAmount, Duplicates: $hasDuplicateFields")
        
        // Determine failure reasons for better telemetry
        when {
            // Complete failure - no meaningful data at all
            couponInfo.storeName == "Unknown Store" && 
            couponInfo.redeemCode.isNullOrBlank() && 
            hasMeaninglessAmount -> {
                failureReason = "COMPLETE_EXTRACTION_FAILURE"
            }
            
            // Generic/boilerplate content detected
            hasGenericStoreName && hasGenericCode && hasGenericDescription -> {
                failureReason = "ALL_GENERIC_CONTENT"
            }
            
            // Duplicate field values
            hasDuplicateFields -> {
                failureReason = "DUPLICATE_FIELD_VALUES"
            }
            
            // Low quality score but some data present
            qualityScore < 40 -> {
                failureReason = "LOW_QUALITY_EXTRACTION"
            }
        }
        
        // Log warning for low quality extractions
        if (qualityScore < 40 || failureReason != null) {
            Log.w(TAG, "Low quality extraction detected (score: $qualityScore, reason: $failureReason)")
        }
        
        // Throw exception to trigger fallback OCR if quality is insufficient
        if (failureReason != null) {
            throw IllegalStateException("Insufficient extraction quality: $failureReason (score: $qualityScore)")
        }
    }
    
    /**
     * Fallback to traditional OCR when LLM fails
     */
    private suspend fun fallbackToTraditionalOCR(bitmap: Bitmap, captureTimestamp: Date? = null): CouponInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "Using traditional OCR fallback")
        
        try {
            // Use Google ML Kit for text recognition
            val mlKitText = performMlKitOcr(bitmap)
            Log.d(TAG, "ML Kit OCR extracted text: ${mlKitText.take(100)}...")
            
            // Validate that we got real text, not empty/whitespace
            if (mlKitText.isBlank()) {
                throw Exception("ML Kit OCR returned blank text")
            }
            
            // Use existing TextExtractor to parse the OCR text
            val textExtractor = TextExtractor()
            val extractedInfo = textExtractor.extractCouponInfoSync(mlKitText, captureTimestamp)
                .let { info ->
                    info.copy(description = cleanDescription(info.description))
                }
            
            // Validate that we got meaningful extraction results
            if (extractedInfo.storeName == "Unknown Store" && 
                extractedInfo.redeemCode.isNullOrBlank() && 
                (extractedInfo.cashbackAmount == null || extractedInfo.cashbackAmount <= 0)) {
                throw Exception("ML Kit OCR produced insufficient coupon data")
            }
            
            Log.d(TAG, "Traditional OCR fallback result: $extractedInfo")
            val normalizedInfo = extractedInfo.copy(
                storeName = normalizeStoreName(extractedInfo.storeName).ifBlank { "Unknown Store" },
                description = cleanDescription(extractedInfo.description)
            )

            return@withContext normalizedInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR fallback failed: ${e.message}", e)
            
            // Try using ModelBasedOCRService as final fallback
            try {
                val modelBasedService = ModelBasedOCRService(context)
                val result = modelBasedService.processCouponImage(bitmap)
                val cleanedResult = result.copy(
                    storeName = normalizeStoreName(result.storeName).ifBlank { "Unknown Store" },
                    description = cleanDescription(result.description)
                )
                Log.d(TAG, "Model-based OCR fallback result: $cleanedResult")
                return@withContext cleanedResult
            } catch (e2: Exception) {
                Log.e(TAG, "All OCR methods failed", e2)

                // Return minimal CouponInfo as last resort
                CouponInfo(
                    storeName = "Unknown Store",
                    description = cleanDescription("All OCR methods failed - please try again")
                )
            }
        }
    }
    
    /**
     * Perform ML Kit OCR on bitmap
     */
    private suspend fun performMlKitOcr(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val extractedText = visionText.text
                        Log.d(TAG, "ML Kit OCR success: ${extractedText.length} chars")
                        continuation.resume(extractedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "ML Kit OCR failed", exception)
                        continuation.resumeWithException(exception)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up ML Kit OCR", e)
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * Warm up the model (preload for faster inference)
     */
    suspend fun warmUpModel(): Boolean {
        return try {
            Log.d(TAG, "Warming up LLM model...")
            llmRuntime.loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up model", e)
            false
        }
    }
    
    /**
     * Release model resources
     */
    fun releaseResources() {
        Log.d(TAG, "Releasing LLM resources")
        llmRuntime.releaseModel()
    }
    
    /**
     * Get performance metrics
     */
    fun getPerformanceMetrics(): LlmPerformanceMetrics {
        val memoryStats = llmRuntime.getMemoryStats()
        val modelInfo = llmRuntime.getModelInfo()
        
        return LlmPerformanceMetrics(
            modelSizeMB = modelInfo.sizeMB,
            memoryUsageMB = memoryStats.modelLoadedMemoryMB.toFloat(),
            isModelLoaded = modelInfo.isLoaded,
            referenceCount = modelInfo.referenceCount,
            serviceVersion = SERVICE_VERSION
        )
    }
}

/**
 * LLM service status data class
 */
data class LlmServiceStatus(
    val isAvailable: Boolean,
    val isModelLoaded: Boolean,
    val modelVersion: String,
    val serviceVersion: String,
    val modelSizeMB: Float,
    val memoryUsageMB: Int,
    val referenceCount: Int
)

/**
 * LLM performance metrics data class
 */
data class LlmPerformanceMetrics(
    val modelSizeMB: Float,
    val memoryUsageMB: Float,
    val isModelLoaded: Boolean,
    val referenceCount: Int,
    val serviceVersion: String
)
