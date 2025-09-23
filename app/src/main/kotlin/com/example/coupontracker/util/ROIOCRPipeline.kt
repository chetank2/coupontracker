package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ROI-based OCR pipeline that processes detected regions of interest using ML Kit Text Recognition.
 * This replaces full-image OCR with targeted processing of specific regions for better accuracy and performance.
 */
class ROIOCRPipeline(private val context: Context) {
    
    data class ROITextResult(
        val text: String,
        val confidence: Float,
        val boundingBox: RectF,
        val processingTimeMs: Long
    )
    
    data class OCRProcessingResult(
        val roiResults: List<ROITextResult>,
        val totalProcessingTimeMs: Long,
        val fallbackUsed: Boolean
    )
    
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val loggerTag = "ROIOCRPipeline"
    
    /**
     * Process multiple ROIs using ML Kit Text Recognition
     * @param bitmap Original image bitmap
     * @param rois List of regions of interest to process
     * @return Combined OCR results from all ROIs
     */
    suspend fun processROIs(bitmap: Bitmap, rois: List<RectF>): OCRProcessingResult = withContext(Dispatchers.IO) {
        val startTime = SystemClock.elapsedRealtimeNanos()
        
        if (rois.isEmpty()) {
            Log.w(loggerTag, "No ROIs provided, using fallback full-image processing")
            return@withContext processFullImage(bitmap)
        }
        
        val roiResults = mutableListOf<ROITextResult>()
        var fallbackUsed = false
        
        try {
            // Process each ROI individually
            for ((index, roi) in rois.withIndex()) {
                try {
                    val roiResult = processSingleROI(bitmap, roi, index)
                    roiResults.add(roiResult)
                    
                    Log.d(loggerTag, "ROI $index processed: ${roiResult.text.length} chars, confidence=${roiResult.confidence}")
                } catch (e: Exception) {
                    Log.w(loggerTag, "Failed to process ROI $index, skipping", e)
                    // Continue with other ROIs instead of failing completely
                }
            }
            
            // If no ROIs produced results, fall back to full image processing
            if (roiResults.isEmpty()) {
                Log.w(loggerTag, "No ROI results, falling back to full-image processing")
                val fallbackResult = processFullImage(bitmap)
                roiResults.addAll(fallbackResult.roiResults)
                fallbackUsed = true
            }
            
        } catch (e: Exception) {
            Log.e(loggerTag, "ROI processing failed, using fallback", e)
            val fallbackResult = processFullImage(bitmap)
            roiResults.addAll(fallbackResult.roiResults)
            fallbackUsed = true
        }
        
        val totalTime = (SystemClock.elapsedRealtimeNanos() - startTime) / 1_000_000
        
        Log.d(loggerTag, "ROI processing completed: ${roiResults.size} results in ${totalTime}ms")
        
        OCRProcessingResult(
            roiResults = roiResults,
            totalProcessingTimeMs = totalTime,
            fallbackUsed = fallbackUsed
        )
    }
    
    /**
     * Process a single ROI using ML Kit Text Recognition
     */
    private suspend fun processSingleROI(bitmap: Bitmap, roi: RectF, roiIndex: Int): ROITextResult = withContext(Dispatchers.IO) {
        val startTime = SystemClock.elapsedRealtimeNanos()
        
        // Crop the ROI from the original bitmap
        val croppedBitmap = cropBitmap(bitmap, roi)
        
        // Convert to InputImage for ML Kit
        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
        
        // Process with ML Kit Text Recognition
        val recognizedText = recognizeText(inputImage)
        
        val processingTime = (SystemClock.elapsedRealtimeNanos() - startTime) / 1_000_000
        
        ROITextResult(
            text = recognizedText.text,
            confidence = recognizedText.confidence,
            boundingBox = roi,
            processingTimeMs = processingTime
        )
    }
    
    /**
     * Fallback: Process the entire image when ROI processing fails
     */
    private suspend fun processFullImage(bitmap: Bitmap): OCRProcessingResult = withContext(Dispatchers.IO) {
        val startTime = SystemClock.elapsedRealtimeNanos()
        
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizedText = recognizeText(inputImage)
        
        val processingTime = (SystemClock.elapsedRealtimeNanos() - startTime) / 1_000_000
        
        val fullImageROI = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        
        OCRProcessingResult(
            roiResults = listOf(
                ROITextResult(
                    text = recognizedText.text,
                    confidence = recognizedText.confidence,
                    boundingBox = fullImageROI,
                    processingTimeMs = processingTime
                )
            ),
            totalProcessingTimeMs = processingTime,
            fallbackUsed = true
        )
    }
    
    /**
     * Crop a region from the bitmap
     */
    private fun cropBitmap(bitmap: Bitmap, roi: RectF): Bitmap {
        val x = roi.left.toInt().coerceIn(0, bitmap.width - 1)
        val y = roi.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (roi.width().toInt()).coerceIn(1, bitmap.width - x)
        val height = (roi.height().toInt()).coerceIn(1, bitmap.height - y)
        
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }
    
    /**
     * Recognize text using ML Kit Text Recognition
     */
    private suspend fun recognizeText(inputImage: InputImage): RecognizedText = suspendCancellableCoroutine { continuation ->
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val confidence = calculateAverageConfidence(visionText)
                
                continuation.resume(
                    RecognizedText(
                        text = text,
                        confidence = confidence
                    )
                )
            }
            .addOnFailureListener { exception ->
                Log.e(loggerTag, "ML Kit text recognition failed", exception)
                continuation.resumeWithException(exception)
            }
    }
    
    /**
     * Calculate average confidence from ML Kit Text Recognition results
     */
    private fun calculateAverageConfidence(visionText: Text): Float {
        val textBlocks = visionText.textBlocks
        if (textBlocks.isEmpty()) return 0f
        
        var totalConfidence = 0f
        var blockCount = 0
        
        for (block in textBlocks) {
            if (block.confidence != null) {
                totalConfidence += block.confidence!!
                blockCount++
            }
        }
        
        return if (blockCount > 0) totalConfidence / blockCount else 0f
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        textRecognizer.close()
    }
    
    private data class RecognizedText(
        val text: String,
        val confidence: Float
    )
}
