package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class that uses multiple OCR engines for better results
 */
class MultiEngineOCR(
    private val context: Context
) {
    private val ocrEngine = OCREngineImpl(context)
    
    // Track if we're connected to the network
    private var isNetworkAvailable = AtomicBoolean(false)
    
    companion object {
        private const val TAG = "MultiEngineOCR"
    }
    
    /**
     * Set the network availability status
     */
    fun setNetworkAvailability(isAvailable: Boolean) {
        isNetworkAvailable.set(isAvailable)
    }
    
    /**
     * Process an image using the best available OCR engine
     */
    suspend fun processImage(imageUri: Uri): OCRResult {
        return withContext(Dispatchers.IO) {
            try {
                // Only continue if we have network connection
                if (!isNetworkAvailable.get()) {
                    return@withContext OCRResult.Error("No network connection available for OCR processing")
                }
                
                Log.d(TAG, "Processing image with OCR Engine")
                try {
                    val text = ocrEngine.processImage(imageUri)
                    val extractedInfo = ocrEngine.extractCouponInfo(text)
                    
                    return@withContext OCRResult.Success(text, extractedInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "OCR Engine failed", e)
                    return@withContext OCRResult.Error("OCR processing failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing failed", e)
                return@withContext OCRResult.Error("OCR processing failed: ${e.message}")
            }
        }
    }
    
    /**
     * Process a bitmap using the best available OCR engine
     */
    suspend fun processImage(bitmap: Bitmap): OCRResult {
        return withContext(Dispatchers.IO) {
            try {
                // Only continue if we have network connection
                if (!isNetworkAvailable.get()) {
                    return@withContext OCRResult.Error("No network connection available for OCR processing")
                }
                
                Log.d(TAG, "Processing bitmap with OCR Engine")
                try {
                    val text = ocrEngine.processImage(bitmap)
                    val extractedInfo = ocrEngine.extractCouponInfo(text)
                    
                    return@withContext OCRResult.Success(text, extractedInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "OCR Engine failed", e)
                    return@withContext OCRResult.Error("OCR processing failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing failed", e)
                return@withContext OCRResult.Error("OCR processing failed: ${e.message}")
            }
        }
    }
    
    /**
     * Result class for OCR processing
     */
    sealed class OCRResult {
        data class Success(val text: String, val extractedInfo: Map<String, String>) : OCRResult()
        data class Error(val message: String) : OCRResult()
    }
} 