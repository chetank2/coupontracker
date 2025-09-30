package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.coupontracker.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.ImageDecoder

/**
 * Tesseract-based text recognition implementation
 * Replaced ML Kit with Tesseract for fully offline operation
 * 
 * @deprecated Use OcrEngine directly via Hilt injection instead
 */
@Deprecated("Use OcrEngine directly via Hilt injection", ReplaceWith("OcrEngine"))
class MLKitRealTextRecognition(
    private val ocrEngine: OcrEngine
) {
    
    companion object {
        private const val TAG = "MLKitRealTextRecognition"
    }
    
    /**
     * Process image from URI using Tesseract OCR
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                // Load bitmap from URI
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                    ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                }
                
                // Process with Tesseract
                val text = ocrEngine.recognize(bitmap)
                
                // Clean up
                bitmap.recycle()
                
                if (text.isBlank()) {
                    Log.w(TAG, "OCR returned empty text")
                    throw Exception("OCR returned empty text")
                }
                
                text
            } catch (e: Exception) {
                Log.e(TAG, "Image processing failed", e)
                throw e
            }
        }
    }
    
    /**
     * Process image from bitmap using Tesseract OCR
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String {
        return ocrEngine.recognize(bitmap)
    }
}
