package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.model.CouponData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Integrated pipeline that combines ROI detection, OCR processing, and field extraction
 * to provide a complete coupon recognition solution.
 */
class IntegratedCouponPipeline(private val context: Context) {
    
    data class PipelineResult(
        val coupon: Coupon,
        val couponData: CouponData,
        val roiResults: List<ROIOCRPipeline.ROITextResult>,
        val fieldConfidences: Map<String, Float>,
        val processingMetrics: ProcessingMetrics,
        val rawText: String
    )
    
    data class ProcessingMetrics(
        val detectionTimeMs: Long,
        val ocrTimeMs: Long,
        val fieldExtractionTimeMs: Long,
        val totalTimeMs: Long,
        val roiCount: Int,
        val fallbackUsed: Boolean
    )
    
    private val detectorPipeline = DetectorPipeline(context)
    private val roiOcrPipeline = ROIOCRPipeline(context)
    private val fieldExtractor = CouponFieldExtractor()
    private val metricsLogger = MetricsLogger
    private val latencyTracker = ComprehensiveLatencyTracker.getInstance(context)
    
    private val loggerTag = "IntegratedCouponPipeline"
    
    /**
     * Process a coupon image through the complete pipeline
     */
    suspend fun processCouponImage(bitmap: Bitmap): PipelineResult = withContext(Dispatchers.IO) {
        val sessionId = generateSessionId()
        val imageSize = "${bitmap.width}x${bitmap.height}"
        
        // Start comprehensive tracking
        latencyTracker.startSession(sessionId, imageSize)
        
        try {
            Log.d(loggerTag, "Starting integrated coupon processing pipeline (session: $sessionId)")
            
            // Step 1: Detect ROIs using the detector pipeline
            latencyTracker.logStageStart(sessionId, ComprehensiveLatencyTracker.ProcessingStage.ROI_DETECTION)
            val detectionStart = SystemClock.elapsedRealtimeNanos()
            val detections = detectorPipeline.detect(bitmap)
            val detectionTime = (SystemClock.elapsedRealtimeNanos() - detectionStart) / 1_000_000
            latencyTracker.logStageEnd(sessionId, ComprehensiveLatencyTracker.ProcessingStage.ROI_DETECTION, 
                mapOf("roiCount" to detections.size, "detectionTimeMs" to detectionTime))
            
            Log.d(loggerTag, "ROI detection completed: ${detections.size} regions in ${detectionTime}ms")
            
            // Step 2: Process ROIs with OCR
            latencyTracker.logStageStart(sessionId, ComprehensiveLatencyTracker.ProcessingStage.ROI_OCR)
            val ocrStart = SystemClock.elapsedRealtimeNanos()
            val roiRects = detections.map { it.boundingBox }
            val ocrResult = roiOcrPipeline.processROIs(bitmap, roiRects)
            val ocrTime = (SystemClock.elapsedRealtimeNanos() - ocrStart) / 1_000_000
            latencyTracker.logStageEnd(sessionId, ComprehensiveLatencyTracker.ProcessingStage.ROI_OCR,
                mapOf("roiCount" to ocrResult.roiResults.size, "ocrTimeMs" to ocrTime, "fallbackUsed" to ocrResult.fallbackUsed))
            
            Log.d(loggerTag, "OCR processing completed: ${ocrResult.roiResults.size} results in ${ocrTime}ms")
            
            // Step 3: Extract and combine text from all ROIs
            val combinedText = combineROITexts(ocrResult.roiResults)
            Log.d(loggerTag, "Combined text length: ${combinedText.length} characters")
            
            // Step 4: Extract structured fields
            latencyTracker.logStageStart(sessionId, ComprehensiveLatencyTracker.ProcessingStage.FIELD_EXTRACTION)
            val fieldExtractionStart = SystemClock.elapsedRealtimeNanos()
            val extractedFields = fieldExtractor.extractWithConfidence(combinedText)
            val fieldExtractionTime = (SystemClock.elapsedRealtimeNanos() - fieldExtractionStart) / 1_000_000
            latencyTracker.logStageEnd(sessionId, ComprehensiveLatencyTracker.ProcessingStage.FIELD_EXTRACTION,
                mapOf("fieldCount" to extractedFields.size, "extractionTimeMs" to fieldExtractionTime))
            
            Log.d(loggerTag, "Field extraction completed: ${extractedFields.size} fields in ${fieldExtractionTime}ms")
            
            // Step 5: Create CouponData and Coupon objects
            latencyTracker.logStageStart(sessionId, ComprehensiveLatencyTracker.ProcessingStage.COUPON_CREATION)
            val couponData = CouponData.fromExtractedFields(extractedFields)
            val coupon = createCouponFromData(couponData, combinedText)
            latencyTracker.logStageEnd(sessionId, ComprehensiveLatencyTracker.ProcessingStage.COUPON_CREATION)
            
            // Step 6: Calculate field confidences
            val fieldConfidences = calculateFieldConfidences(extractedFields, ocrResult.roiResults)
            
            val totalTime = (SystemClock.elapsedRealtimeNanos() - detectionStart) / 1_000_000
            
            val metrics = ProcessingMetrics(
                detectionTimeMs = detectionTime,
                ocrTimeMs = ocrTime,
                fieldExtractionTimeMs = fieldExtractionTime,
                totalTimeMs = totalTime,
                roiCount = detections.size,
                fallbackUsed = ocrResult.fallbackUsed
            )
            
            // Log metrics for analysis
            logProcessingMetrics(metrics, ocrResult.roiResults)
            
            // End successful session
            latencyTracker.endSession(sessionId, true)
            
            Log.d(loggerTag, "Pipeline completed successfully in ${totalTime}ms")
            
            PipelineResult(
                coupon = coupon,
                couponData = couponData,
                roiResults = ocrResult.roiResults,
                fieldConfidences = fieldConfidences,
                processingMetrics = metrics,
                rawText = combinedText
            )
            
        } catch (e: Exception) {
            Log.e(loggerTag, "Pipeline processing failed", e)
            latencyTracker.logError(sessionId, ComprehensiveLatencyTracker.ProcessingStage.TOTAL_PIPELINE, e)
            latencyTracker.endSession(sessionId, false)
            throw OCRProcessingException("Integrated pipeline failed: ${e.message}", e)
        }
    }
    
    /**
     * Combine text from all ROI results
     */
    private fun combineROITexts(roiResults: List<ROIOCRPipeline.ROITextResult>): String {
        if (roiResults.isEmpty()) return ""
        
        // Sort ROIs by position (top to bottom, left to right)
        val sortedResults = roiResults.sortedWith(compareBy<ROIOCRPipeline.ROITextResult> { it.boundingBox.top }
            .thenBy { it.boundingBox.left })
        
        return sortedResults.joinToString("\n") { it.text.trim() }
    }
    
    /**
     * Create a Coupon object from extracted CouponData
     */
    private fun createCouponFromData(couponData: CouponData, rawText: String): Coupon {
        return Coupon(
            storeName = couponData.merchantName,
            description = couponData.description ?: "",
            expiryDate = parseExpiryDate(couponData.expiryDate),
            cashbackAmount = couponData.getNumericAmount()?.toDouble() ?: 0.0,
            redeemCode = couponData.code,
            imageUri = null, // Will be set by the calling code
            category = null,
            status = "active",
            minimumPurchase = null,
            maximumDiscount = null,
            isPriority = false,
            paymentMethod = null,
            usageLimit = null,
            usageCount = 0,
            reminderDate = null,
            platformType = null,
            rating = null,
            createdAt = java.util.Date(),
            updatedAt = java.util.Date(),
            // Normalized fields
            code = couponData.code,
            benefitType = determineBenefitType(couponData.amount),
            benefitValue = couponData.getNumericAmount()?.toDouble(),
            currency = "INR", // Default to INR for Indian market
            expiryIso = formatExpiryIso(couponData.expiryDate),
            app = null, // Could be determined from context
            confidence = calculateOverallConfidence(couponData)
        )
    }
    
    /**
     * Calculate field confidences based on extraction and OCR results
     */
    private fun calculateFieldConfidences(
        extractedFields: Map<String, ExtractedField>,
        roiResults: List<ROIOCRPipeline.ROITextResult>
    ): Map<String, Float> {
        val confidences = mutableMapOf<String, Float>()
        
        // Calculate average OCR confidence
        val avgOcrConfidence = if (roiResults.isNotEmpty()) {
            roiResults.map { it.confidence }.average().toFloat()
        } else {
            0.5f
        }
        
        // Combine extraction confidence with OCR confidence
        for ((fieldName, field) in extractedFields) {
            val extractionConfidence = when (field.confidence) {
                ConfidenceLevel.HIGH -> 0.9f
                ConfidenceLevel.MEDIUM -> 0.7f
                ConfidenceLevel.LOW -> 0.5f
                ConfidenceLevel.SYNTHETIC -> 0.3f
            }
            
            // Weighted average: 70% extraction confidence, 30% OCR confidence
            val combinedConfidence = (extractionConfidence * 0.7f) + (avgOcrConfidence * 0.3f)
            confidences[fieldName] = combinedConfidence.coerceIn(0f, 1f)
        }
        
        return confidences
    }
    
    /**
     * Calculate overall confidence for the coupon
     */
    private fun calculateOverallConfidence(couponData: CouponData): Float {
        val extractionScore = couponData.extractionScore / 100f
        val hasRequiredFields = couponData.isValid()
        
        return if (hasRequiredFields) {
            extractionScore.coerceIn(0f, 1f)
        } else {
            (extractionScore * 0.5f).coerceIn(0f, 1f)
        }
    }
    
    /**
     * Determine benefit type from amount string
     */
    private fun determineBenefitType(amount: String?): String? {
        if (amount.isNullOrBlank()) return null
        
        return when {
            amount.contains("%") -> "percentage"
            amount.contains("₹") || amount.contains("Rs") -> "cashback"
            else -> "discount"
        }
    }
    
    /**
     * Parse expiry date string to Date object
     */
    private fun parseExpiryDate(expiryString: String?): java.util.Date {
        if (expiryString.isNullOrBlank()) return java.util.Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000) // 30 days from now
        
        // Try common date formats
        val dateFormats = listOf(
            "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy", "MM-dd-yyyy",
            "dd/MM/yy", "dd-MM-yy", "MM/dd/yy", "MM-dd-yy",
            "dd MMM yyyy", "MMM dd yyyy"
        )
        
        for (format in dateFormats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                val date = sdf.parse(expiryString)
                if (date != null) return date
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        // Default to 30 days from now if parsing fails
        return java.util.Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
    }
    
    /**
     * Format expiry date to ISO string
     */
    private fun formatExpiryIso(expiryString: String?): String? {
        if (expiryString.isNullOrBlank()) return null
        
        val date = parseExpiryDate(expiryString)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
    
    /**
     * Log processing metrics for analysis
     */
    private fun logProcessingMetrics(
        metrics: ProcessingMetrics,
        roiResults: List<ROIOCRPipeline.ROITextResult>
    ) {
        val bestConfidence = roiResults.maxOfOrNull { it.confidence } ?: 0f
        
        metricsLogger.logOcrEvent(
            context = context,
            detectionMs = metrics.detectionTimeMs,
            roiMs = metrics.ocrTimeMs,
            roiCount = metrics.roiCount,
            bestConfidence = bestConfidence,
            fallbackUsed = metrics.fallbackUsed
        )
    }
    
    /**
     * Generate a unique session ID
     */
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(0..9999).random()}"
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        detectorPipeline.close()
        roiOcrPipeline.close()
    }
}
