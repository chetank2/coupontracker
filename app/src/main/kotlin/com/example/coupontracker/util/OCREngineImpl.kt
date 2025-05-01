package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.util.SecurePreferencesManager.Companion.KEY_GOOGLE_CLOUD_VISION_API_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main OCR engine that orchestrates the OCR process using multiple OCR implementations
 */
class OCREngineImpl(private val context: Context) {
    companion object {
        private const val TAG = "OCREngineImpl"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)

    private val googleApiKey = sharedPreferences.getString(KEY_GOOGLE_CLOUD_VISION_API_KEY, "")
    private val googleVisionHelper = if (!googleApiKey.isNullOrBlank()) {
        GoogleVisionHelper(googleApiKey)
    } else {
        null
    }

    private val enhancedOCRHelper = EnhancedOCRHelper()
    private val mlKitTextRecognitionHelper = MLKitTextRecognitionHelper()

    /**
     * Process an image and extract text using the best available OCR implementation
     */
    suspend fun processImage(imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image with Enhanced OCR")
                try {
                    enhancedOCRHelper.processImageFromUri(context, imageUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Enhanced OCR failed, trying Google Vision API if available", e)
                    try {
                        if (googleVisionHelper != null) {
                            googleVisionHelper.processImageFromUri(context, imageUri)
                        } else {
                            throw Exception("Google Vision API not available (no API key)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Google Vision API failed or not available, trying ML Kit", e)
                        mlKitTextRecognitionHelper.processImageFromUri(context, imageUri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "All OCR methods failed", e)
                throw e
            }
        }
    }

    /**
     * Process a bitmap and extract text using the best available OCR implementation
     */
    suspend fun processImage(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing bitmap with Enhanced OCR")
                try {
                    enhancedOCRHelper.processImageFromBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Enhanced OCR failed, trying Google Vision API if available", e)
                    try {
                        if (googleVisionHelper != null) {
                            googleVisionHelper.processImageFromBitmap(bitmap)
                        } else {
                            throw Exception("Google Vision API not available (no API key)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Google Vision API failed or not available, trying ML Kit", e)
                        mlKitTextRecognitionHelper.processImageFromBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "All OCR methods failed", e)
                throw e
            }
        }
    }

    /**
     * Extract coupon info from recognized text
     * This extracts coupon information including amount in rupees (₹)
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        val isMyntraCoupon = text.contains("myntra", ignoreCase = true) ||
                           text.contains("you won a voucher", ignoreCase = true)

        try {
            Log.d(TAG, "Extracting coupon info with Enhanced OCR")
            val enhancedResult = enhancedOCRHelper.extractCouponInfo(text)
            if (enhancedResult.isNotEmpty()) {
                // Handle Myntra coupons
                val result = enhancedResult.toMutableMap()
                if (isMyntraCoupon && result["storeName"] != "Myntra") {
                    result["storeName"] = "Myntra"
                }
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced OCR extraction failed", e)
        }

        if (googleVisionHelper != null) {
            try {
                val googleResult = googleVisionHelper.extractCouponInfo(text)

                // Handle Myntra coupons
                val result = googleResult.toMutableMap()
                if (isMyntraCoupon && result["storeName"] != "Myntra") {
                    result["storeName"] = "Myntra"
                }

                return result
            } catch (e: Exception) {
                Log.e(TAG, "Google Vision extraction failed", e)
            }
        }

        // Fallback to a basic extraction if all methods fail
        val results = mutableMapOf<String, String>()
        results["storeName"] = if (isMyntraCoupon) "Myntra" else "Unknown Store"
        results["description"] = if (isMyntraCoupon) "Myntra coupon" else "Scanned coupon"
        results["amount"] = "₹0"

        // Try to extract code from text
        results["code"] = extractBasicCode(text, isMyntraCoupon)

        return results
    }

    /**
     * Simple method to extract a basic code from text
     */
    private fun extractBasicCode(text: String, isMyntraCoupon: Boolean): String {
        // For Myntra coupons look for long alphanumeric codes
        if (isMyntraCoupon) {
            val words = text.split("\\s+".toRegex())
            for (word in words) {
                if (word.length >= 10 && word.matches("[A-Z0-9]+".toRegex())) {
                    return word.uppercase()
                }
            }
        }

        // For other coupons look for code: prefix
        val lines = text.split("\n")
        for (line in lines) {
            if (line.contains("code:", ignoreCase = true)) {
                val parts = line.split(":", limit = 2)
                if (parts.size > 1 && parts[1].isNotBlank()) {
                    return parts[1].trim().uppercase()
                }
            }
        }

        // Fallback to looking for any alphanumeric code-like string
        val words = text.split("\\s+".toRegex())
        for (word in words) {
            if (word.length >= 6 && word.matches("[A-Z0-9]+".toRegex())) {
                return word.uppercase()
            }
        }

        return if (isMyntraCoupon) "MYNTRA" else "COUPON"
    }
}