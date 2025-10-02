package com.example.coupontracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML Kit-based OCR engine implementation
 * Fully offline, bundled with Google Play Services
 * More reliable than Tesseract for production use
 */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {
    
    companion object {
        private const val TAG = "MlKitOcrEngine"
    }
    
    // ML Kit text recognizer (offline, bundled in APK)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    override suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Starting ML Kit OCR recognition...")
            
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            
            val extractedText = visionText.text
            Log.d(TAG, "✅ ML Kit extracted ${extractedText.length} chars")
            
            if (extractedText.isBlank()) {
                Log.w(TAG, "⚠️ ML Kit returned empty text")
            }
            
            return@withContext extractedText
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ ML Kit OCR failed", e)
            return@withContext ""
        }
    }
    
    override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Starting ML Kit OCR with bounding boxes...")
            
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            
            val spans = mutableListOf<OcrTextSpan>()
            
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val boundingBox = element.boundingBox
                        if (boundingBox != null) {
                            spans.add(
                                OcrTextSpan(
                                    text = element.text,
                                    boundingBox = android.graphics.Rect(
                                        boundingBox.left,
                                        boundingBox.top,
                                        boundingBox.right,
                                        boundingBox.bottom
                                    ),
                                    confidence = element.confidence ?: 0.0f
                                )
                            )
                        }
                    }
                }
            }
            
            Log.d(TAG, "✅ ML Kit extracted ${spans.size} text spans")
            return@withContext spans
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ ML Kit OCR with boxes failed", e)
            return@withContext emptyList()
        }
    }
    
    override fun isReady(): Boolean {
        // ML Kit is always ready (bundled in APK)
        return true
    }
    
    override fun release() {
        try {
            recognizer.close()
            Log.d(TAG, "ML Kit resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ML Kit resources", e)
        }
    }
}

