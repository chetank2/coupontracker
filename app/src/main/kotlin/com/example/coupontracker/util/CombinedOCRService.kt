package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Service that combines Google Cloud Vision API with Mistral AI
 * for improved OCR accuracy and validation
 */
class CombinedOCRService(
    private val googleVisionHelper: EnhancedGoogleVisionHelper,
    private val mistralOcrService: MistralOcrService
) {
    private val TAG = "CombinedOCRService"
    private var mistralApiAvailable = false
    
    /**
     * Test the Mistral API connection
     * This should be called before using the service
     */
    suspend fun testMistralApiConnection() {
        try {
            Log.d(TAG, "Testing Mistral API connection...")
            mistralApiAvailable = mistralOcrService.testApiConnection()
            Log.d(TAG, "Mistral API available: $mistralApiAvailable")
        } catch (e: Exception) {
            Log.e(TAG, "Error testing Mistral API connection", e)
            mistralApiAvailable = false
        }
    }
    
    /**
     * Extract text from an image using both Google Vision and Mistral AI
     * for validation and improved results
     * 
     * @param bitmap The image to process
     * @return The improved and validated extracted text
     */
    suspend fun extractTextWithValidation(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting combined OCR processing with validation")
            
            // First get Google Vision results
            val googleVisionText = googleVisionHelper.extractText(bitmap)
            Log.d(TAG, "Google Vision extracted text (sample): ${googleVisionText.take(100)}...")
            
            if (googleVisionText.isBlank()) {
                Log.w(TAG, "Google Vision returned empty results, using Mistral only")
                return@withContext extractTextWithMistralOnly(bitmap)
            }
            
            // Only use Mistral for validation if it's available
            if (!mistralApiAvailable) {
                Log.w(TAG, "Mistral API is not available, returning Google Vision text only")
                return@withContext googleVisionText
            }
            
            // Get Mistral results to validate and enhance
            val combinedText = validateAndEnhanceWithMistral(bitmap, googleVisionText)
            return@withContext combinedText
        } catch (e: Exception) {
            Log.e(TAG, "Error in combined OCR processing", e)
            
            // Try to fall back to individual services
            try {
                Log.d(TAG, "Falling back to Google Vision")
                return@withContext googleVisionHelper.extractText(bitmap)
            } catch (e2: Exception) {
                Log.e(TAG, "Google Vision fallback failed, trying Mistral", e2)
                
                if (mistralApiAvailable) {
                    try {
                        return@withContext mistralOcrService.extractTextFromImage(bitmap) 
                    } catch (e3: Exception) {
                        Log.e(TAG, "All OCR attempts failed", e3)
                        return@withContext ""
                    }
                } else {
                    Log.e(TAG, "Mistral API is not available, and Google Vision failed")
                    return@withContext ""
                }
            }
        }
    }
    
    /**
     * Extract coupon information with validation and enhancement
     * 
     * @param bitmap The image to process
     * @return Enhanced coupon information
     */
    suspend fun extractCouponInfoWithValidation(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            // Test Mistral API if we haven't already
            if (!mistralApiAvailable) {
                testMistralApiConnection()
            }
            
            // Get validated text first
            val validatedText = extractTextWithValidation(bitmap)
            
            if (validatedText.isBlank()) {
                Log.w(TAG, "Validated text is empty, returning empty coupon info")
                return@withContext CouponInfo()
            }
            
            // Use Mistral to extract structured coupon information if available,
            // otherwise use basic text extractor
            val couponInfo = if (mistralApiAvailable) {
                try {
                    extractStructuredCouponInfo(bitmap, validatedText)
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting structured info with Mistral, using fallback", e)
                    val textExtractor = TextExtractor()
                    textExtractor.extractCouponInfoSync(validatedText)
                }
            } else {
                Log.d(TAG, "Mistral API not available, using basic text extractor")
                val textExtractor = TextExtractor()
                textExtractor.extractCouponInfoSync(validatedText)
            }
            
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting coupon info with validation", e)
            return@withContext CouponInfo()
        }
    }
    
    /**
     * Extract text using only Mistral AI when Google Vision fails
     */
    private suspend fun extractTextWithMistralOnly(bitmap: Bitmap): String {
        return try {
            Log.d(TAG, "Using Mistral OCR only")
            val mistralText = mistralOcrService.extractTextFromImage(bitmap)
            Log.d(TAG, "Mistral extracted text (sample): ${mistralText.take(100)}...")
            mistralText
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text with Mistral only", e)
            ""
        }
    }
    
    /**
     * Use Mistral AI to validate and enhance Google Vision results
     */
    private suspend fun validateAndEnhanceWithMistral(
        bitmap: Bitmap, 
        googleVisionText: String
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Validating Google Vision results with Mistral")
            
            // Create a specialized prompt for Mistral to validate and correct OCR results
            val validationPrompt = createValidationPrompt(googleVisionText)
            
            // Get Mistral validation results
            val validatedText = mistralOcrService.processTextWithCustomPrompt(bitmap, validationPrompt)
            
            if (validatedText.isBlank()) {
                Log.w(TAG, "Mistral validation returned empty results, using Google Vision text")
                return@withContext googleVisionText
            }
            
            Log.d(TAG, "Mistral validation successful, returning enhanced text")
            return@withContext validatedText
        } catch (e: Exception) {
            Log.e(TAG, "Error validating with Mistral, returning Google Vision text", e)
            return@withContext googleVisionText
        }
    }
    
    /**
     * Use Mistral to extract structured coupon information from validated text
     */
    private suspend fun extractStructuredCouponInfo(
        bitmap: Bitmap,
        validatedText: String
    ): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Extracting structured coupon info with Mistral")
            
            // Create a specialized structured data extraction prompt
            val structuredDataPrompt = createStructuredDataPrompt(validatedText)
            
            // Get Mistral structured data extraction results
            val structuredDataJson = mistralOcrService.processTextWithCustomPrompt(bitmap, structuredDataPrompt)
            
            if (structuredDataJson.isBlank()) {
                Log.w(TAG, "Mistral structured data extraction returned empty results, using fallback extraction")
                val textExtractor = TextExtractor()
                return@withContext textExtractor.extractCouponInfoSync(validatedText)
            }
            
            // Parse JSON response from Mistral
            return@withContext parseCouponInfoFromJson(structuredDataJson) ?: run {
                Log.w(TAG, "Failed to parse coupon info from Mistral JSON, using fallback extraction")
                val textExtractor = TextExtractor()
                textExtractor.extractCouponInfoSync(validatedText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting structured data, using fallback extraction", e)
            val textExtractor = TextExtractor()
            return@withContext textExtractor.extractCouponInfoSync(validatedText)
        }
    }
    
    /**
     * Create validation prompt for Mistral AI
     */
    private fun createValidationPrompt(googleVisionText: String): String {
        return """
            I have extracted the following text from a coupon image using Google Cloud Vision API, 
            but I need you to validate and improve it. The text may contain errors or missing information.
            
            Original extracted text:
            ---
            $googleVisionText
            ---
            
            Please analyze this text and provide an improved version. Focus on:
            1. Fixing any obvious OCR errors
            2. Identifying and correcting missing or incomplete information
            3. Preserving the original structure and format
            4. Ensuring all coupon-related information is accurate (brand names, codes, amounts, etc.)
            
            Return ONLY the corrected text, without any explanations or additional text.
        """.trimIndent()
    }
    
    /**
     * Create structured data extraction prompt for Mistral AI
     */
    private fun createStructuredDataPrompt(validatedText: String): String {
        return """
            I need to extract structured information from this coupon text:
            ---
            $validatedText
            ---
            
            Please extract the following information for each coupon in the text and return it in JSON format:
            - storeName: The name of the store or brand (e.g., "XYXX", "ABHIBUS", "NEWMEE")
            - description: The offer description with emphasis on the discount details (e.g., "Get flat ₹500 off", "Up to 80% off")
            - redeemCode: The coupon or redeem code (e.g., "MYNTRA500", "CRDLUKES799") - this is usually after "Code:" or "Use code:"
            - status: The status of the coupon (e.g., "Available to Redeem")
            - rating: The rating of the coupon if present (e.g., "4.31")
            - expiryInfo: The expiry information (e.g., "Expires in 14 hours", "Valid till 30th June")
            - cashbackAmount: The discount amount as a number only (without ₹ or %), following these rules:
              - For percentage discounts (e.g., "20% off"): extract just the number (20)
              - For fixed amount discounts (e.g., "₹500 off"): extract just the number (500)
            
            Important for description field:
            - For percentage discounts include the % symbol in the description (e.g., "Up to 30% off")
            - For fixed amount discounts include the ₹ symbol in the description (e.g., "Flat ₹250 off")
            - Make the description concise and focused on the discount offer (not just a repeat of other fields)
            - Include phrases like "cashback", "off", "discount" as appropriate
            
            Return ONLY the JSON object without additional text or explanations. If multiple coupons are present, return an array of objects. If a field is not found, use null for that field.
            
            Example format:
            {
              "storeName": "XYXX",
              "description": "Get ₹150 cashback via CRED pay",
              "redeemCode": "CRDLUKES799",
              "status": "Available to Redeem",
              "rating": "4.31",
              "expiryInfo": "Expires in 14 hours",
              "cashbackAmount": 150
            }
        """.trimIndent()
    }
    
    /**
     * Parse coupon information from JSON response
     */
    private fun parseCouponInfoFromJson(jsonString: String): CouponInfo? {
        try {
            val json = cleanAndParseJson(jsonString)
            
            // Parse cashback amount
            val cashbackValue = json.opt("cashbackAmount")
            val cashbackAmount = when (cashbackValue) {
                is Int -> cashbackValue.toDouble()
                is Double -> cashbackValue
                is String -> cashbackValue.toDoubleOrNull()
                else -> null
            }
            
            // Analyze the description to determine if this is a percentage or amount
            val description = json.optString("description", "")
            val discountType = if (description.contains("%")) {
                "PERCENTAGE"
            } else if (description.contains("₹") || description.contains("Rs")) {
                "AMOUNT"
            } else if (cashbackAmount != null && cashbackAmount <= 100 && 
                      (description.contains("percent", ignoreCase = true) || 
                       description.contains("off", ignoreCase = true))) {
                "PERCENTAGE"
            } else {
                null
            }
            
            // Parse expiry info if available
            val expiryDate = parseExpiryDate(json.optString("expiryInfo", ""))
            
            // Get a clean description
            val cleanDescription = if (description.isNotBlank()) {
                // Use the provided description
                description
            } else {
                // Try to construct a meaningful description
                val storeName = json.optString("storeName", "")
                val amountStr = if (discountType == "PERCENTAGE" && cashbackAmount != null) {
                    "$cashbackAmount%"
                } else if (cashbackAmount != null) {
                    "₹$cashbackAmount"
                } else {
                    ""
                }
                
                if (storeName.isNotBlank() && amountStr.isNotBlank()) {
                    val discountPhrase = when {
                        discountType == "PERCENTAGE" -> "Up to $amountStr off"
                        else -> "$amountStr off"
                    }
                    "$storeName Coupon - $discountPhrase"
                } else {
                    ""
                }
            }
            
            // Create CouponInfo object with all parameters
            return CouponInfo(
                storeName = json.optString("storeName", ""),
                description = cleanDescription,
                expiryDate = expiryDate,
                cashbackAmount = cashbackAmount,
                redeemCode = json.optString("redeemCode", "").takeIf { it.isNotBlank() },
                category = null, // Not extracted from Mistral
                rating = json.optString("rating", "").takeIf { it.isNotBlank() },
                status = json.optString("status", "").takeIf { it.isNotBlank() },
                discountType = discountType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing coupon info from JSON", e)
            return null
        }
    }
    
    /**
     * Attempt to parse expiry date from text
     */
    private fun parseExpiryDate(expiryText: String?): Date? {
        if (expiryText.isNullOrBlank()) {
            return null
        }
        
        // Common date patterns to try
        val datePatterns = listOf(
            "dd/MM/yyyy", "MM/dd/yyyy",
            "dd-MM-yyyy", "MM-dd-yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd"
        )
        
        for (pattern in datePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                return sdf.parse(expiryText.trim())
            } catch (e: Exception) {
                // Try next pattern
            }
        }
        
        return null
    }
    
    /**
     * Clean and parse JSON string
     */
    private fun cleanAndParseJson(jsonString: String): JSONObject {
        try {
            // Try direct parsing first
            return JSONObject(jsonString)
        } catch (e: Exception) {
            Log.d(TAG, "First JSON parse attempt failed, trying to clean JSON", e)
            
            // Try to extract JSON object from text response
            val jsonPattern = Pattern.compile("\\{.*\\}")
            val matcher = jsonPattern.matcher(jsonString)
            
            if (matcher.find()) {
                val cleanedJson = matcher.group(0)
                if (cleanedJson != null) {
                    try {
                        return JSONObject(cleanedJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse cleaned JSON", e)
                    }
                }
            }
            
            // If all parsing attempts fail, return an empty JSONObject
            return JSONObject()
        }
    }
    
    /**
     * Test API availability by checking both required services
     * @return True if both services are available, false otherwise
     */
    suspend fun testApiAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing Combined OCR Service availability")
            
            // Test Google Vision availability
            val googleVisionAvailable = googleVisionHelper.testApiAvailability()
            Log.d(TAG, "Google Vision API availability: $googleVisionAvailable")
            
            // Test Mistral API availability
            val mistralAvailable = mistralOcrService.testApiAvailability()
            Log.d(TAG, "Mistral API availability: $mistralAvailable")
            
            // Store Mistral API availability for future use
            mistralApiAvailable = mistralAvailable
            
            // Combined service is available only if both services are available
            val combinedAvailable = googleVisionAvailable && mistralAvailable
            Log.d(TAG, "Combined OCR Service availability: $combinedAvailable")
            
            return@withContext combinedAvailable
        } catch (e: Exception) {
            Log.e(TAG, "Error testing Combined OCR Service availability: ${e.message}", e)
            return@withContext false
        }
    }
} 