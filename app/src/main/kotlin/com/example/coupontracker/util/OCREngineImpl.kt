package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main OCR engine that orchestrates the OCR process using multiple OCR implementations
 */
class OCREngineImpl(
    private val context: Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine
) {
    companion object {
        private const val TAG = "OCREngineImpl"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)

    // We've removed Google Cloud Vision API dependencies as we're using on-device OCR only

    private val enhancedOCRHelper = EnhancedOCRHelper(ocrEngine)
    private val mlKitTextRecognitionHelper = MLKitTextRecognitionHelper(ocrEngine)
    private val textExtractor = TextExtractor()

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
                    Log.e(TAG, "Enhanced OCR failed, falling back to ML Kit", e)
                    mlKitTextRecognitionHelper.processImageFromUri(context, imageUri)
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
                    Log.e(TAG, "Enhanced OCR failed, falling back to ML Kit", e)
                    mlKitTextRecognitionHelper.processImageFromBitmap(bitmap)
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
        try {
            Log.d(TAG, "Extracting coupon info with Enhanced OCR")
            val enhancedResult = enhancedOCRHelper.extractCouponInfo(text)
            if (enhancedResult.isNotEmpty()) {
                return enhancedResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced OCR extraction failed", e)
        }

        val extracted = textExtractor.extractCouponInfoSync(text)
        val results = mutableMapOf<String, String>()
        results["storeName"] = extracted.storeName.ifBlank { com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE }
        results["description"] = extracted.description.ifBlank { "Scanned coupon" }
        results["amount"] = extracted.cashbackDetail ?: "₹0"
        results["code"] = extracted.redeemCode ?: extractBasicCode(text)

        return results
    }

    /**
     * Simple method to extract a basic code from text
     */
    private fun extractBasicCode(text: String): String {
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

        return ""
    }
}
