package com.example.coupontracker.camera

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraX ImageAnalyzer that performs real-time text detection using ML Kit
 * 
 * Features:
 * - Live text detection overlay
 * - Automatic crop boundary detection
 * - Confidence scoring for capture readiness
 * - Throttled analysis to prevent performance issues
 */
class LiveTextDetectionAnalyzer : ImageAnalysis.Analyzer {
    
    companion object {
        private const val TAG = "LiveTextDetector"
        private const val MIN_CONFIDENCE_THRESHOLD = 0.7f
        private const val MIN_TEXT_BLOCKS = 3
        private const val THROTTLE_INTERVAL_MS = 500L
    }
    
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val _detectedText = MutableStateFlow<List<DetectedTextBlock>>(emptyList())
    val detectedText: StateFlow<List<DetectedTextBlock>> = _detectedText.asStateFlow()
    
    private val _suggestedCropRect = MutableStateFlow<Rect?>(null)
    val suggestedCropRect: StateFlow<Rect?> = _suggestedCropRect.asStateFlow()
    
    private val _captureReadiness = MutableStateFlow(CaptureReadiness.NOT_READY)
    val captureReadiness: StateFlow<CaptureReadiness> = _captureReadiness.asStateFlow()
    
    private var lastAnalysisTimestamp = 0L
    
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        
        // Throttle analysis to prevent performance issues
        if (currentTimestamp - lastAnalysisTimestamp < THROTTLE_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        
        lastAnalysisTimestamp = currentTimestamp
        
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    processTextResults(visionText.textBlocks.map { block ->
                        // ML Kit doesn't provide confidence, use synthetic score based on text quality
                        val syntheticConfidence = calculateConfidence(block.text, block.boundingBox)
                        DetectedTextBlock(
                            text = block.text,
                            boundingBox = block.boundingBox ?: Rect(),
                            confidence = syntheticConfidence,
                            lines = block.lines.map { it.text }
                        )
                    })
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
    
    private fun processTextResults(textBlocks: List<DetectedTextBlock>) {
        _detectedText.value = textBlocks
        
        if (textBlocks.isEmpty()) {
            _captureReadiness.value = CaptureReadiness.NO_TEXT_DETECTED
            _suggestedCropRect.value = null
            return
        }
        
        // Calculate bounding box that encompasses all text blocks
        val cropRect = calculateOptimalCropRect(textBlocks)
        _suggestedCropRect.value = cropRect
        
        // Determine capture readiness based on text quality and quantity
        val avgConfidence = textBlocks.map { it.confidence }.average().toFloat()
        val hasEnoughText = textBlocks.size >= MIN_TEXT_BLOCKS
        val hasGoodConfidence = avgConfidence >= MIN_CONFIDENCE_THRESHOLD
        
        _captureReadiness.value = when {
            hasEnoughText && hasGoodConfidence -> CaptureReadiness.READY
            hasEnoughText -> CaptureReadiness.LOW_CONFIDENCE
            else -> CaptureReadiness.INSUFFICIENT_TEXT
        }
        
        Log.d(TAG, "Detected ${textBlocks.size} text blocks, avg confidence: $avgConfidence")
    }
    
    private fun calculateConfidence(text: String, boundingBox: Rect?): Float {
        // Synthetic confidence based on text quality metrics
        if (text.isBlank()) return 0f
        
        var score = 0.5f // Base score
        
        // Longer text is generally more reliable
        if (text.length > 5) score += 0.2f
        
        // Presence of alphanumeric characters
        if (text.any { it.isLetterOrDigit() }) score += 0.2f
        
        // Reasonable bounding box size
        boundingBox?.let { box ->
            val area = box.width() * box.height()
            if (area > 1000) score += 0.1f
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    private fun calculateOptimalCropRect(textBlocks: List<DetectedTextBlock>): Rect {
        if (textBlocks.isEmpty()) return Rect()
        
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        
        textBlocks.forEach { block ->
            val box = block.boundingBox
            left = minOf(left, box.left)
            top = minOf(top, box.top)
            right = maxOf(right, box.right)
            bottom = maxOf(bottom, box.bottom)
        }
        
        // Add padding (10% on each side)
        val width = right - left
        val height = bottom - top
        val padding = maxOf(width, height) / 10
        
        return Rect(
            maxOf(0, left - padding),
            maxOf(0, top - padding),
            right + padding,
            bottom + padding
        )
    }
    
    fun close() {
        textRecognizer.close()
    }
}

/**
 * Represents a detected text block with bounding box and confidence
 */
data class DetectedTextBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float,
    val lines: List<String>
)

/**
 * Capture readiness states for user feedback
 */
enum class CaptureReadiness {
    NOT_READY,
    NO_TEXT_DETECTED,
    INSUFFICIENT_TEXT,
    LOW_CONFIDENCE,
    READY
}

