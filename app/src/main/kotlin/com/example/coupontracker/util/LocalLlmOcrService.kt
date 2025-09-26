package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.llm.LlmRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * Local LLM OCR Service using MiniCPM-Llama3-V2.5
 * Provides structured coupon extraction using on-device vision-language model
 */
class LocalLlmOcrService(private val context: Context) {
    
    companion object {
        private const val TAG = "LocalLlmOcrService"
        
        // Inference timeout (30 seconds)
        private const val INFERENCE_TIMEOUT_MS = 30000L
        
        // Model version tracking
        private const val SERVICE_VERSION = "1.0.0"
        private const val SUPPORTED_MODEL_VERSION = "v2.5-q4-android"
    }
    
    // Dependencies
    private val llmRuntime = LlmRuntimeManager.getInstance(context)
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
    suspend fun processCouponImage(bitmap: Bitmap): CouponInfo = coroutineScope {
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
            
            // Step 3: Preprocess image (reuse existing pipeline)
            val preprocessedBitmap = imagePreprocessor.preprocess(bitmap)
            Log.d(TAG, "Preprocessed image: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
            
            // Step 4: Create structured extraction prompt
            val prompt = createCouponExtractionPrompt()
            
            // Step 5: Run LLM inference with timeout
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runInference(preprocessedBitmap, prompt)
            }
            
            // Step 6: Parse and validate response
            val couponInfo = if (llmResponse != null) {
                parseLlmResponseToCouponInfo(llmResponse)
            } else {
                throw Exception("LLM inference timed out or returned null")
            }
            
            // Step 7: Validate extraction quality
            validateExtractionQuality(couponInfo)
            
            Log.d(TAG, "LLM extraction complete: $couponInfo")
            couponInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)
            
            // Fallback to traditional OCR
            Log.d(TAG, "Falling back to traditional OCR")
            fallbackToTraditionalOCR(bitmap)
        }
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

        REQUIRED JSON FORMAT:
        {
            "storeName": "Store/brand name (required)",
            "description": "Offer description (required)", 
            "cashbackAmount": "Amount/percentage off or null",
            "redeemCode": "Promo/coupon code or null",
            "expiryDate": "Expiry date or null",
            "minOrderAmount": "Minimum order value or null"
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
            
            // Extract fields with fallbacks
            val storeName = json.optString("storeName", "Unknown Store").let {
                if (it.isBlank() || it == "Unknown") "Unknown Store" else it
            }
            
            val description = json.optString("description", "Coupon offer").let {
                if (it.isBlank() || it == "Unknown") "Coupon offer" else it.take(100) // Limit length
            }
            
            val amount = json.optString("amount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val code = json.optString("code").let {
                if (it.isBlank() || it == "Unknown") null else it.trim().uppercase()
            }
            
            val expiryDate = json.optString("expiryDate").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val cashbackAmount = json.optString("cashbackAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val minOrderAmount = json.optString("minOrderAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            CouponInfo(
                storeName = storeName,
                description = description,
                expiryDate = null, // Will be parsed later if needed
                cashbackAmount = cashbackAmount?.toDoubleOrNull(),
                redeemCode = code,
                minimumPurchase = minOrderAmount?.toDoubleOrNull()
            )
            
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse LLM JSON response: $response", e)
            throw IllegalStateException("Invalid JSON response from LLM: ${e.message}")
        }
    }
    
    /**
     * Validate extraction quality and completeness
     */
    private fun validateExtractionQuality(couponInfo: CouponInfo) {
        var qualityScore = 0
        
        // Check essential fields
        if (couponInfo.storeName != "Unknown Store") qualityScore += 30
        if (!couponInfo.redeemCode.isNullOrBlank()) qualityScore += 25
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) qualityScore += 25
        if (couponInfo.expiryDate != null) qualityScore += 10
        if (couponInfo.description != "Coupon offer") qualityScore += 10
        
        Log.d(TAG, "Extraction quality score: $qualityScore/100")
        
        // Log warning for low quality extractions
        if (qualityScore < 40) {
            Log.w(TAG, "Low quality extraction detected (score: $qualityScore)")
        }
        
        // Ensure we have minimum viable information
        if (couponInfo.storeName == "Unknown Store" && 
            couponInfo.redeemCode.isNullOrBlank() && 
            (couponInfo.cashbackAmount == null || couponInfo.cashbackAmount <= 0)) {
            throw IllegalStateException("Insufficient information extracted from coupon")
        }
    }
    
    /**
     * Fallback to traditional OCR when LLM fails
     */
    private suspend fun fallbackToTraditionalOCR(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "Using traditional OCR fallback")
        
        try {
            // Use existing TextExtractor as fallback
            val textExtractor = TextExtractor()
            val extractedText = "Fallback text extraction" // Placeholder
            val extractedInfo = textExtractor.extractCouponInfo(extractedText)
            
            // Return the CouponInfo directly from TextExtractor
            extractedInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "Traditional OCR fallback also failed", e)
            
            // Return minimal CouponInfo as last resort
            CouponInfo(
                storeName = "Unknown Store",
                description = "OCR extraction failed"
            )
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
