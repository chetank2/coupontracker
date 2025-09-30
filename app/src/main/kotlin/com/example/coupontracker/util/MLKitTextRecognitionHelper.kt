package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Basic Tesseract text recognition implementation
 * This implementation uses Tesseract OCR for fully offline operation
 */
class MLKitTextRecognitionHelper(
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine
) {
    
    private val mlKitRealTextRecognition = MLKitRealTextRecognition(ocrEngine)
    
    companion object {
        private const val TAG = "MLKitTextRecognitionHelper"
    }
    
    /**
     * Process image from URI using ML Kit
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image from URI with ML Kit")
                
                // Use the real ML Kit implementation - let failures propagate
                return@withContext mlKitRealTextRecognition.processImageFromUri(context, imageUri)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI with ML Kit", e)
                throw e
            }
        }
    }
    
    /**
     * Process bitmap using ML Kit
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing bitmap with ML Kit")
                
                // Use the real ML Kit implementation - let failures propagate
                return@withContext mlKitRealTextRecognition.processImageFromBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing bitmap with ML Kit", e)
                throw e
            }
        }
    }
    
    // Removed createBasicDummyText() - we now properly propagate failures
    // instead of injecting fake coupon content
} 