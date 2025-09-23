package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class that uses the trained model for OCR
 */
class MultiEngineOCR(
    private val context: Context
) {
    private val ocrEngine = OCREngineImpl(context)

    companion object {
        private const val TAG = "MultiEngineOCR"
    }

    /**
     * Process an image using the trained model
     */
    suspend fun processImage(imageUri: Uri): OCRResult = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Processing image with trained model")
            val payload = ocrEngine.processImageDetailed(imageUri)
            OCRResult.Success(
                DetectedCoupon(
                    rawText = payload.rawText,
                    couponInfo = payload.couponInfo,
                    extractedFields = payload.fieldMap
                )
            )
        }.getOrElse { throwable ->
            Log.e(TAG, "OCR Engine failed", throwable)
            OCRResult.Error(throwable.message ?: "OCR processing failed")
        }
    }

    /**
     * Process a bitmap using the trained model
     */
    suspend fun processImage(bitmap: Bitmap): OCRResult = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Processing bitmap with trained model")
            val payload = ocrEngine.processImageDetailed(bitmap)
            OCRResult.Success(
                DetectedCoupon(
                    rawText = payload.rawText,
                    couponInfo = payload.couponInfo,
                    extractedFields = payload.fieldMap
                )
            )
        }.getOrElse { throwable ->
            Log.e(TAG, "OCR Engine failed", throwable)
            OCRResult.Error(throwable.message ?: "OCR processing failed")
        }
    }

    /**
     * Result class for OCR processing
     */
    sealed class OCRResult {
        data class Success(val detectedCoupon: DetectedCoupon) : OCRResult()
        data class Error(val message: String) : OCRResult()
    }

    data class DetectedCoupon(
        val rawText: String,
        val couponInfo: CouponInfo,
        val extractedFields: Map<String, String>
    )
}
