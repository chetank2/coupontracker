package com.example.coupontracker.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Generic OCR engine interface
 * Allows switching between different OCR implementations
 */
interface OcrEngine {
    /**
     * Recognize text from bitmap
     * @return Recognized text string
     */
    suspend fun recognize(bitmap: Bitmap): String
    
    /**
     * Recognize text with bounding boxes
     * @return List of text spans with coordinates
     */
    suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan>
    
    /**
     * Check if engine is ready
     */
    fun isReady(): Boolean
    
    /**
     * Release resources
     */
    fun release()
}

/**
 * Text span with bounding box information
 */
data class OcrTextSpan(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float
)

