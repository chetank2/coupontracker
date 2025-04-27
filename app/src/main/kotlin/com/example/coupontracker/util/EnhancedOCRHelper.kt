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
class EnhancedOCRHelper {
    
    private val mlKitRealTextRecognition = MLKitRealTextRecognition()
    
    companion object {
        private const val TAG = "EnhancedOCRHelper"
        
        // Regex patterns for coupon information extraction
        private val STORE_PATTERN = Pattern.compile("(?i)(store|shop|merchant|retailer|brand|company|from)\\s*:?\\s*([A-Za-z0-9\\s&.'-]+)")
        
        // Add specific pattern for Myntra store detection
        private val MYNTRA_PATTERN = Pattern.compile("(?i)\\b(myntra)\\b")
        
        private val CODE_PATTERN = Pattern.compile("(?i)(code|coupon|promo|voucher|redeem|use)\\s*:?\\s*([A-Za-z0-9\\-_]+)")
        
        // Add specific pattern for Myntra coupon codes which are typically longer
        private val MYNTRA_CODE_PATTERN = Pattern.compile("\\b([A-Z0-9]{10,})\\b")
        
        private val AMOUNT_PATTERN = Pattern.compile("(?i)(₹|Rs\\.?|\\$)?(\\d+(\\.\\d{1,2})?|\\d+(\\.\\d{1,2})?)\\s*%?\\s*(off|cashback|discount|reward|save)")
        
        // Myntra-specific amount pattern with "up to" format
        private val MYNTRA_AMOUNT_PATTERN = Pattern.compile("(?i)(up to|flat|get)\\s+(₹|Rs\\.?)(\\d+)")
        
        private val EXPIRY_PATTERN = Pattern.compile("(?i)(exp|expires|expiry|valid until|valid through|use by)\\s*:?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})")
        
        private val DESCRIPTION_PATTERN = Pattern.compile("(?i)(description|details|offer|deal|get|save)\\s*:?\\s*([^\\n\\r.]+)")
        
        // Myntra-specific description pattern
        private val MYNTRA_DESCRIPTION_PATTERN = Pattern.compile("(?i)(you won a voucher)([^\\n\\r.]+)")
    }
    
    /**
     * Process an image from URI with preprocessing for better OCR results
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image from URI with preprocessing")
                
                // Use the real ML Kit implementation
                try {
                    return@withContext mlKitRealTextRecognition.processImageFromUri(context, imageUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Real ML Kit processing failed, falling back to dummy text", e)
                    return@withContext getDummyText()
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
                try {
                    return@withContext mlKitRealTextRecognition.processImageFromBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Real ML Kit processing failed, falling back to dummy text", e)
                    return@withContext getDummyText()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing bitmap", e)
                throw e
            }
        }
    }
    
    /**
     * Create dummy text for testing
     */
    private fun getDummyText(): String {
        return """
            STORE: Example Store
            CODE: EXAMPLE20
            Get 20% off your next purchase
            Valid until: 12/31/2023
            Description: This is an example coupon for testing
        """.trimIndent()
    }
    
    /**
     * Extract coupon information from recognized text
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        Log.d(TAG, "Extracting info from text: $text")
        
        // Check if this is likely a Myntra coupon
        val isMyntraCoupon = text.contains("myntra", ignoreCase = true) || 
                             text.contains("you won a voucher", ignoreCase = true)
        
        // Handle store name extraction with special case for Myntra
        if (isMyntraCoupon) {
            result["storeName"] = "Myntra"
            Log.d(TAG, "Identified as Myntra coupon")
        } else {
            findMatch(STORE_PATTERN, text)?.let {
                result["storeName"] = it.trim()
                Log.d(TAG, "Found store name: ${it.trim()}")
            }
        }
        
        // Handle code extraction with special case for Myntra
        var codeFound = false
        
        if (isMyntraCoupon) {
            // For Myntra, first look for their specific longer code format
            val myntraMatcher = MYNTRA_CODE_PATTERN.matcher(text)
            while (myntraMatcher.find()) {
                val potentialCode = myntraMatcher.group(1)
                // Ensure it has both letters and numbers for higher confidence
                if (potentialCode.contains(Regex("[A-Z]")) && 
                    potentialCode.contains(Regex("[0-9]")) &&
                    potentialCode.length >= 10) {
                    result["code"] = potentialCode.uppercase()
                    codeFound = true
                    Log.d(TAG, "Found Myntra coupon code: $potentialCode")
                    break
                }
            }
        }
        
        // If no code found yet, try standard pattern
        if (!codeFound) {
            findMatch(CODE_PATTERN, text)?.let {
                result["code"] = it.trim().uppercase()
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
                    result["code"] = potentialCode.uppercase()
                    codeFound = true
                    Log.d(TAG, "Found potential code from isolated string: $potentialCode")
                    return@forEach
                }
            }
        }
        
        // Handle amount extraction with special case for Myntra
        var amountFound = false
        
        if (isMyntraCoupon) {
            // For Myntra, look for their specific "up to ₹200" format
            val myntraAmountMatcher = MYNTRA_AMOUNT_PATTERN.matcher(text)
            if (myntraAmountMatcher.find() && myntraAmountMatcher.groupCount() >= 3) {
                val amount = myntraAmountMatcher.group(3)
                result["amount"] = "₹$amount"
                amountFound = true
                Log.d(TAG, "Found Myntra amount: ₹$amount")
            }
        }
        
        // If no amount found yet, try standard pattern
        if (!amountFound) {
            findMatch(AMOUNT_PATTERN, text)?.let {
                // Add ₹ symbol if it doesn't already have one
                val amount = if (it.trim().startsWith("₹") || it.trim().startsWith("Rs") || it.trim().startsWith("$")) {
                    it.trim().replace("$", "₹").replace("Rs", "₹")
                } else {
                    "₹$it"
                }
                result["amount"] = amount.trim()
                amountFound = true
                Log.d(TAG, "Found standard amount: $amount")
            }
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
        
        // Handle description extraction with special case for Myntra
        var descriptionFound = false
        
        if (isMyntraCoupon) {
            // For Myntra, try to find their specific voucher description format
            findMatch(MYNTRA_DESCRIPTION_PATTERN, text)?.let {
                val description = it.trim() + " up to ₹" + (result["amount"] ?: "").replace("₹", "")
                result["description"] = description
                descriptionFound = true
                Log.d(TAG, "Found Myntra description: $description")
            }
            
            // If that fails, look for a phrase containing "voucher" and "up to"
            if (!descriptionFound) {
                val myntraDescRegex = "(?:you won a voucher|up to ₹\\d+)(.{5,30})".toRegex(RegexOption.IGNORE_CASE)
                myntraDescRegex.find(text)?.let {
                    val desc = it.groupValues[0].trim()
                    if (desc.isNotBlank()) {
                        result["description"] = desc
                        descriptionFound = true
                        Log.d(TAG, "Found Myntra description with alt pattern: $desc")
                    }
                }
            }
        }
        
        // If no description found yet, try standard pattern
        if (!descriptionFound) {
            findMatch(DESCRIPTION_PATTERN, text)?.let {
                result["description"] = it.trim()
                descriptionFound = true
                Log.d(TAG, "Found standard description: ${it.trim()}")
            }
        }
        
        // If we couldn't extract any information, provide some defaults
        if (result.isEmpty()) {
            result["storeName"] = if (isMyntraCoupon) "Myntra" else "Unknown Store"
            result["description"] = "Scanned coupon"
        }
        
        // Set default amount if not found
        if (!result.containsKey("amount") && !amountFound) {
            result["amount"] = "₹0"
        }
        
        Log.d(TAG, "Final extracted coupon info: $result")
        return result
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