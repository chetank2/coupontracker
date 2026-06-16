package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Enhanced OCR Helper that provides improved OCR results with preprocessing
 */
class EnhancedOCRHelper(
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine
) {
    
    private val mlKitRealTextRecognition = MLKitRealTextRecognition(ocrEngine)
    private val textExtractor = TextExtractor()
    
    companion object {
        private const val TAG = "EnhancedOCRHelper"
        
        // Regex patterns for coupon information extraction
        private val STORE_PATTERN = Pattern.compile("(?i)(store|shop|merchant|retailer|brand|company|from)\\s*:?\\s*([A-Za-z0-9\\s&.'-]+)")
        
        private val CODE_PATTERN = Pattern.compile("(?i)(code|coupon|promo|voucher|redeem|use)\\s*:?\\s*([A-Za-z0-9\\-_]+)")

        private val AMOUNT_PATTERN = Pattern.compile("(?i)(₹|Rs\\.?|\\$)?(\\d+(\\.\\d{1,2})?|\\d+(\\.\\d{1,2})?)\\s*%?\\s*(off|cashback|discount|reward|save)")

        private val EXPIRY_PATTERN = Pattern.compile("(?i)(exp|expires|expiry|valid until|valid through|use by)\\s*:?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})")
        
        private val DESCRIPTION_PATTERN = Pattern.compile("(?i)(description|details|offer|deal|get|save)\\s*:?\\s*([^\\n\\r.]+)")
    }
    
    /**
     * Process an image from URI with preprocessing for better OCR results
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image from URI with preprocessing")
                
                // Use the real ML Kit implementation
                return@withContext try {
                    mlKitRealTextRecognition.processImageFromUri(context, imageUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Real ML Kit processing failed", e)
                    throw e
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI", e)
                throw e
            }
        }
    }
    
    /**
     * Process an image from Bitmap with preprocessing for better OCR results
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing bitmap with preprocessing")
                
                // Use the real ML Kit implementation
                return@withContext try {
                    mlKitRealTextRecognition.processImageFromBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Real ML Kit processing failed", e)
                    throw e
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing bitmap", e)
                throw e
            }
        }
    }
    
    /**
     * Extract coupon information from recognized text
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        Log.d(TAG, "Extracting info from text: $text")
        
        findMatch(STORE_PATTERN, text)?.let {
            result["storeName"] = it.trim()
            Log.d(TAG, "Found store name: ${it.trim()}")
        } ?: textExtractor.extractStoreName(text)?.let {
            result["storeName"] = it
            Log.d(TAG, "Found store name with generic extractor: $it")
        }
        
        var codeFound = false

        findMatch(CODE_PATTERN, text)?.let {
            val sanitized = RedeemCodeSanitizer.sanitize(it)
            if (sanitized != null) {
                result["code"] = sanitized
                codeFound = true
                Log.d(TAG, "Found standard coupon code: ${it.trim()}")
            }
        }

        // If still no code found, try looking for isolated alphanumeric strings
        if (!codeFound) {
            // Look for isolated alphanumeric strings that might be codes
            val codeRegex = "\\b([A-Z0-9]{6,})\\b".toRegex()
            codeRegex.findAll(text).forEach { match ->
                val potentialCode = match.groupValues[1]
                // Ensure it has both letters and numbers and isn't just a random sequence
                if (potentialCode.contains(Regex("[A-Z]")) &&
                    potentialCode.contains(Regex("[0-9]"))) {
                    val sanitized = RedeemCodeSanitizer.sanitize(potentialCode)
                    if (sanitized != null) {
                        result["code"] = sanitized
                        codeFound = true
                        Log.d(TAG, "Found potential code from isolated string: $potentialCode")
                        return@forEach
                    }
                }
            }
        }
        
        var amountFound = false

        findMatch(AMOUNT_PATTERN, text)?.let {
            val amount = if (it.trim().startsWith("₹") || it.trim().startsWith("Rs") || it.trim().startsWith("$")) {
                it.trim().replace("$", "₹").replace("Rs", "₹")
            } else {
                "₹$it"
            }
            result["amount"] = amount.trim()
            amountFound = true
            Log.d(TAG, "Found standard amount: $amount")
        }
        
        // If still no amount found, try a simpler pattern
        if (!amountFound) {
            val simpleAmountRegex = "(?:Rs\\.?|₹)\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            simpleAmountRegex.find(text)?.let {
                val amount = it.groupValues[1]
                if (amount.isNotEmpty()) {
                    result["amount"] = "₹$amount"
                    amountFound = true
                    Log.d(TAG, "Found amount with simple pattern: ₹$amount")
                }
            }
        }
        
        // Handle expiry date extraction
        findMatch(EXPIRY_PATTERN, text)?.let {
            result["expiryDate"] = it.trim()
            Log.d(TAG, "Found expiry date: ${it.trim()}")
        }
        
        findMatch(DESCRIPTION_PATTERN, text)?.let {
            setDescription(result, it.trim())
            Log.d(TAG, "Found standard description: ${it.trim()}")
        }
        
        // If we couldn't extract any information, provide some defaults
        if (result.isEmpty()) {
            result["storeName"] = com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE
            setDescription(result, "Scanned coupon")
        }
        
        // Set default amount if not found
        if (!result.containsKey("amount") && !amountFound) {
            result["amount"] = "₹0"
        }
        
        Log.d(TAG, "Final extracted coupon info: $result")
        return result
    }

    private fun setDescription(result: MutableMap<String, String>, description: String) {
        val cleaned = LocalLlmOcrService.cleanDescription(description)
        if (cleaned.isNotBlank()) {
            result["description"] = cleaned
        }
    }
    
    /**
     * Count words in the recognized text - useful for assessing OCR quality
     */
    fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).count()
    }
    
    /**
     * Find a match using a regex pattern
     */
    private fun findMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find() && matcher.groupCount() >= 2) {
            matcher.group(2)
        } else {
            null
        }
    }
} 
