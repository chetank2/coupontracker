package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.MultiEngineOCR
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid Coupon Detector
 * Combines two detection strategies:
 * 1. TwoStageDetector (contour-based, finds visual boundaries)
 * 2. OcrAnchorSegmenter (text-based, finds repeated UI elements)
 * 
 * Fusion strategy:
 * - Use contour regions for precise bounding boxes
 * - Use OCR text segments for content and validation
 * - Merge overlapping detections
 * - Fallback to one method if other fails
 */
class HybridCouponDetector(
    context: Context,
    private val ocrEngine: OcrEngine
) {
    
    private val twoStageDetector: TwoStageDetector? = try {
        TwoStageDetector(context)
    } catch (e: Exception) {
        Log.w(TAG, "TwoStageDetector not available: ${e.message}")
        null
    }
    
    private val ocrSegmenter = OcrAnchorSegmenter(ocrEngine)
    
    companion object {
        private const val TAG = "HybridCouponDetector"
        
        // IoU (Intersection over Union) threshold for matching contour and text regions
        private const val IOU_THRESHOLD = 0.3f
        
        // Minimum overlap to consider regions as matching
        private const val MIN_OVERLAP_PERCENT = 0.4f
    }
    
    /**
     * Unified coupon region from hybrid detection
     */
    data class CouponRegion(
        val boundingBox: Rect,
        val ocrText: String,
        val confidence: Float,
        val source: DetectionSource,
        val anchorMatches: List<String> = emptyList(),
        val regionIndex: Int = 0
    )
    
    /**
     * Source of detection
     */
    enum class DetectionSource {
        CONTOUR_ONLY,        // Only contour detector found this
        OCR_ANCHOR_ONLY,     // Only OCR segmenter found this
        FUSED,               // Both methods agreed (highest confidence)
        FALLBACK             // Single region fallback
    }
    
    /**
     * Main detection method: combines contour and OCR anchor detection
     */
    suspend fun detectCoupons(
        bitmap: Bitmap,
        ocrResult: MultiEngineOCR.OCRResult.Success
    ): List<CouponRegion> {
        
        Log.d(TAG, "Starting hybrid coupon detection")
        
        // Step 1: Run contour-based detection (if available)
        val contourRegions = if (twoStageDetector != null) {
            try {
                val instances = twoStageDetector.detectMultiCoupons(bitmap)
                Log.d(TAG, "Contour detection found ${instances.size} regions")
                instances.map { instance ->
                    Pair(rectFToRect(instance.boundingBox), instance.confidence)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contour detection failed: ${e.message}")
                emptyList()
            }
        } else {
            Log.d(TAG, "Contour detector not available, using OCR only")
            emptyList()
        }
        
        // Step 2: Run OCR anchor-based segmentation
        val ocrSegments = try {
            ocrSegmenter.segmentByAnchors(bitmap, ocrResult)
        } catch (e: Exception) {
            Log.w(TAG, "OCR segmentation failed: ${e.message}")
            emptyList()
        }
        
        Log.d(TAG, "OCR segmentation found ${ocrSegments.size} regions")
        
        // Step 3: Fuse results
        val fusedRegions = fuseDetections(contourRegions, ocrSegments, bitmap)
        
        Log.d(TAG, "Fusion produced ${fusedRegions.size} final regions")
        
        // Step 4: If no regions found, return full image as fallback
        return if (fusedRegions.isEmpty()) {
            val fullText = ocrResult.extractedInfo.values.joinToString("\n")
            listOf(
                CouponRegion(
                    boundingBox = Rect(0, 0, bitmap.width, bitmap.height),
                    ocrText = fullText,
                    confidence = 0.5f,
                    source = DetectionSource.FALLBACK,
                    regionIndex = 0
                )
            )
        } else {
            fusedRegions
        }
    }
    
    /**
     * Fuse contour and OCR detections
     */
    private fun fuseDetections(
        contourRegions: List<Pair<Rect, Float>>,
        ocrSegments: List<OcrAnchorSegmenter.CouponSegment>,
        bitmap: Bitmap
    ): List<CouponRegion> {
        
        val fusedRegions = mutableListOf<CouponRegion>()
        val matchedContours = mutableSetOf<Int>()
        val matchedOcrSegments = mutableSetOf<Int>()
        
        // Case 1: Both methods found regions - fuse them
        if (contourRegions.isNotEmpty() && ocrSegments.isNotEmpty()) {
            
            ocrSegments.forEachIndexed { ocrIndex, ocrSegment ->
                val ocrBox = ocrSegment.boundingBox ?: return@forEachIndexed
                
                // Find best matching contour region
                var bestMatch: Pair<Int, Float>? = null
                contourRegions.forEachIndexed { contourIndex, (contourBox, contourConf) ->
                    if (contourIndex in matchedContours) return@forEachIndexed
                    
                    val overlap = calculateOverlap(ocrBox, contourBox)
                    if (overlap > MIN_OVERLAP_PERCENT) {
                        if (bestMatch == null || overlap > bestMatch!!.second) {
                            bestMatch = Pair(contourIndex, overlap)
                        }
                    }
                }
                
                if (bestMatch != null) {
                    // Fused region: use contour box (more precise) + OCR text
                    val (contourIndex, overlap) = bestMatch!!
                    val (contourBox, contourConf) = contourRegions[contourIndex]
                    
                    fusedRegions.add(
                        CouponRegion(
                            boundingBox = contourBox,
                            ocrText = ocrSegment.textBlock,
                            confidence = (contourConf + overlap) / 2f,
                            source = DetectionSource.FUSED,
                            anchorMatches = ocrSegment.anchorMatches,
                            regionIndex = fusedRegions.size
                        )
                    )
                    
                    matchedContours.add(contourIndex)
                    matchedOcrSegments.add(ocrIndex)
                    
                    Log.d(TAG, "Fused region ${fusedRegions.size}: contour #$contourIndex + OCR segment #$ocrIndex (overlap=$overlap)")
                } else {
                    // OCR segment without matching contour
                    fusedRegions.add(
                        CouponRegion(
                            boundingBox = ocrBox,
                            ocrText = ocrSegment.textBlock,
                            confidence = 0.6f,
                            source = DetectionSource.OCR_ANCHOR_ONLY,
                            anchorMatches = ocrSegment.anchorMatches,
                            regionIndex = fusedRegions.size
                        )
                    )
                    
                    matchedOcrSegments.add(ocrIndex)
                }
            }
            
            // Add unmatched contour regions (no OCR text match)
            contourRegions.forEachIndexed { index, (contourBox, contourConf) ->
                if (index !in matchedContours) {
                    fusedRegions.add(
                        CouponRegion(
                            boundingBox = contourBox,
                            ocrText = extractTextFromRegion(ocrSegments, contourBox),
                            confidence = contourConf * 0.8f, // Penalize for no OCR match
                            source = DetectionSource.CONTOUR_ONLY,
                            regionIndex = fusedRegions.size
                        )
                    )
                }
            }
        }
        // Case 2: Only contour regions available
        else if (contourRegions.isNotEmpty()) {
            contourRegions.forEach { (box, conf) ->
                fusedRegions.add(
                    CouponRegion(
                        boundingBox = box,
                        ocrText = "", // Will need OCR pass on this region
                        confidence = conf,
                        source = DetectionSource.CONTOUR_ONLY,
                        regionIndex = fusedRegions.size
                    )
                )
            }
        }
        // Case 3: Only OCR segments available
        else if (ocrSegments.isNotEmpty()) {
            ocrSegments.forEach { segment ->
                segment.boundingBox?.let { box ->
                    fusedRegions.add(
                        CouponRegion(
                            boundingBox = box,
                            ocrText = segment.textBlock,
                            confidence = 0.7f,
                            source = DetectionSource.OCR_ANCHOR_ONLY,
                            anchorMatches = segment.anchorMatches,
                            regionIndex = fusedRegions.size
                        )
                    )
                }
            }
        }
        
        return fusedRegions.sortedBy { it.boundingBox.top }
    }
    
    /**
     * Calculate overlap percentage between two rectangles
     */
    private fun calculateOverlap(rect1: Rect, rect2: Rect): Float {
        val intersectLeft = max(rect1.left, rect2.left)
        val intersectTop = max(rect1.top, rect2.top)
        val intersectRight = min(rect1.right, rect2.right)
        val intersectBottom = min(rect1.bottom, rect2.bottom)
        
        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f
        }
        
        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val rect1Area = rect1.width() * rect1.height()
        val rect2Area = rect2.width() * rect2.height()
        val unionArea = rect1Area + rect2Area - intersectArea
        
        return if (unionArea > 0) intersectArea.toFloat() / unionArea.toFloat() else 0f
    }
    
    /**
     * Extract text from OCR segments that overlap with a contour region
     */
    private fun extractTextFromRegion(
        ocrSegments: List<OcrAnchorSegmenter.CouponSegment>,
        contourBox: Rect
    ): String {
        val overlappingText = ocrSegments
            .filter { segment ->
                segment.boundingBox?.let { ocrBox ->
                    calculateOverlap(ocrBox, contourBox) > 0.2f
                } ?: false
            }
            .joinToString("\n") { it.textBlock }
        
        return overlappingText.ifBlank { "" }
    }
    
    /**
     * Convert RectF to Rect
     */
    private fun rectFToRect(rectF: RectF): Rect {
        return Rect(
            rectF.left.toInt(),
            rectF.top.toInt(),
            rectF.right.toInt(),
            rectF.bottom.toInt()
        )
    }
    
    /**
     * Check if hybrid detector is fully available (both methods working)
     */
    fun isFullyAvailable(): Boolean {
        return twoStageDetector != null
    }

    /**
     * Check if at least one detection method is available
     */
    fun isPartiallyAvailable(): Boolean {
        return twoStageDetector != null || ocrSegmenter != null
    }

    /**
     * Returns true when the detector is operating in OCR-only fallback mode.
     * This happens when the dedicated two-stage detector models are missing,
     * but we can still segment coupons using OCR anchor heuristics.
     */
    fun isOcrOnlyMode(): Boolean {
        return twoStageDetector == null && ocrSegmenter != null
    }
}

