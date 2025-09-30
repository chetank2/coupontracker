package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR Helper using Tesseract
 * Provides text recognition functionality using fully offline Tesseract OCR
 * 
 * @deprecated Use OcrEngine directly via Hilt injection instead
 */
@Deprecated("Use OcrEngine directly via Hilt injection", ReplaceWith("OcrEngine"))
class OCRHelper(
    private val ocrEngine: OcrEngine
) {
    
    companion object {
        private const val TAG = "OCRHelper"
    }
    
    /**
     * Process an image from URI
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                    context.contentResolver,
                    imageUri
                )
                val result = processImageFromBitmap(bitmap)
                bitmap.recycle()
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI", e)
                throw e
            }
        }
    }
    
    /**
     * Process an image from Bitmap
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            try {
                ocrEngine.recognize(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from bitmap", e)
                throw e
            }
        }
    }
    
    /**
     * Extract coupon information from recognized text
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        // Basic extraction logic
        val result = mutableMapOf<String, String>()
        
        // Extract code (alphanumeric sequences)
        val codePattern = Regex("[A-Z0-9]{4,}")
        val code = codePattern.find(text)?.value
        if (code != null) {
            result["code"] = code
        }
        
        // Extract amounts (₹, Rs patterns)
        val amountPattern = Regex("[₹Rs.]+\\s*\\d+")
        val amount = amountPattern.find(text)?.value
        if (amount != null) {
            result["amount"] = amount
        }
        
        return result
    }
}
