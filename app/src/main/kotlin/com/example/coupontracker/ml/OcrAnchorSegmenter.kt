package com.example.coupontracker.ml

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.MultiEngineOCR

/**
 * OCR Anchor-Based Coupon Segmenter
 * Detects multiple coupons in a single screenshot by finding repeated UI elements
 * (buttons, category headers, tags) that serve as coupon boundaries.
 * 
 * Use case: App screenshots with multiple coupons in grid or list layout
 * Examples: Amazon offers page, Myntra deals, PhonePe rewards
 */
class OcrAnchorSegmenter(
    private val ocrEngine: OcrEngine
) {
    
    companion object {
        private const val TAG = "OcrAnchorSegmenter"
        
        // Anchor patterns for coupon boundaries
        private val BUTTON_ANCHORS = listOf(
            "collect now", "get offer", "claim", "apply", "activate",
            "redeem", "shop now", "buy now", "grab deal", "avail offer",
            "use code", "copy code", "view offer"
        ).map { it.lowercase() }
        
        private val CATEGORY_ANCHORS = listOf(
            "automotive", "luggage", "fashion", "electronics", "beauty",
            "groceries", "home", "kitchen", "sports", "books", "mobile",
            "appliances", "furniture", "toys", "baby", "health",
            "personal care", "watches", "footwear", "bags"
        ).map { it.lowercase() }
        
        private val ELIGIBILITY_ANCHORS = listOf(
            "prime only", "for you", "limited time", "today only",
            "exclusive", "first time user", "new user", "app only",
            "valid for", "expires", "minimum order"
        ).map { it.lowercase() }
        
        // All anchors combined
        private val ALL_ANCHORS = (BUTTON_ANCHORS + CATEGORY_ANCHORS + ELIGIBILITY_ANCHORS).distinct()
        
        // Minimum distance between anchors to be considered separate coupons (in pixels)
        private const val MIN_COUPON_SPACING = 50
    }
    
    /**
     * Represents a segmented coupon region with its text and bounding box
     */
    data class CouponSegment(
        val textBlock: String,
        val boundingBox: Rect?,
        val anchorMatches: List<String>,
        val segmentIndex: Int
    )
    
    /**
     * Anchor position found in OCR text
     */
    private data class AnchorPosition(
        val text: String,
        val lineIndex: Int,
        val charOffset: Int,
        val yCoordinate: Int?,
        val matchedAnchor: String
    )
    
    /**
     * Segment image into multiple coupon regions using OCR anchor detection
     */
    suspend fun segmentByAnchors(
        bitmap: Bitmap,
        ocrResult: MultiEngineOCR.OCRResult.Success
    ): List<CouponSegment> {
        
        Log.d(TAG, "Starting OCR anchor-based segmentation")
        
        // Extract full OCR text
        val fullText = ocrResult.text.ifBlank {
            ocrResult.extractedInfo.values.joinToString("\n")
        }
        val lines = fullText.lines()
        
        Log.d(TAG, "OCR text has ${lines.size} lines")
        
        // Find all anchor positions in the text
        val anchors = findAnchorPositions(lines, ocrResult)
        
        if (anchors.isEmpty()) {
            Log.d(TAG, "No anchors found, treating as single coupon")
            return listOf(
                CouponSegment(
                    textBlock = fullText,
                    boundingBox = Rect(0, 0, bitmap.width, bitmap.height),
                    anchorMatches = emptyList(),
                    segmentIndex = 0
                )
            )
        }
        
        Log.d(TAG, "Found ${anchors.size} anchor positions")
        
        // Group anchors into coupon segments
        val segments = groupAnchorsIntoSegments(lines, anchors, bitmap)
        
        Log.d(TAG, "Segmented into ${segments.size} coupons")
        
        return segments
    }
    
    /**
     * Find all anchor positions in OCR text
     */
    private fun findAnchorPositions(
        lines: List<String>,
        ocrResult: MultiEngineOCR.OCRResult.Success
    ): List<AnchorPosition> {
        val positions = mutableListOf<AnchorPosition>()
        
        lines.forEachIndexed { lineIndex, line ->
            val normalizedLine = line.lowercase().trim()
            
            // Check each anchor pattern
            for (anchor in ALL_ANCHORS) {
                if (normalizedLine.contains(anchor)) {
                    val charOffset = normalizedLine.indexOf(anchor)
                    
                    positions.add(
                        AnchorPosition(
                            text = line,
                            lineIndex = lineIndex,
                            charOffset = charOffset,
                            yCoordinate = estimateYCoordinate(lineIndex, lines.size),
                            matchedAnchor = anchor
                        )
                    )
                    
                    Log.d(TAG, "Found anchor '$anchor' at line $lineIndex: '$line'")
                }
            }
        }
        
        return positions.sortedBy { it.lineIndex }
    }
    
    /**
     * Estimate Y coordinate based on line index (rough approximation)
     */
    private fun estimateYCoordinate(lineIndex: Int, totalLines: Int): Int {
        // Assume average line height of 40 pixels
        return lineIndex * 40
    }
    
    /**
     * Group anchors into logical coupon segments
     */
    private fun groupAnchorsIntoSegments(
        lines: List<String>,
        anchors: List<AnchorPosition>,
        bitmap: Bitmap
    ): List<CouponSegment> {
        
        if (anchors.isEmpty()) return emptyList()
        
        // Strategy: Split text at anchor boundaries with sufficient spacing
        val segments = mutableListOf<CouponSegment>()
        var currentSegmentStart = 0
        var currentAnchors = mutableListOf<String>()
        
        anchors.forEachIndexed { index, anchor ->
            // Check if this anchor starts a new coupon (based on spacing)
            val isNewCoupon = if (index > 0) {
                val prevAnchor = anchors[index - 1]
                val lineDistance = anchor.lineIndex - prevAnchor.lineIndex
                
                // New coupon if: button anchor + significant line distance
                (BUTTON_ANCHORS.contains(anchor.matchedAnchor) && lineDistance >= 3) ||
                lineDistance >= 10 // Large gap always means new coupon
            } else {
                false
            }
            
            if (isNewCoupon && currentSegmentStart < anchor.lineIndex) {
                // Save previous segment
                val segmentText = lines.subList(currentSegmentStart, anchor.lineIndex).joinToString("\n")
                val boundingBox = estimateBoundingBox(currentSegmentStart, anchor.lineIndex, bitmap)
                
                segments.add(
                    CouponSegment(
                        textBlock = segmentText,
                        boundingBox = boundingBox,
                        anchorMatches = currentAnchors.toList(),
                        segmentIndex = segments.size
                    )
                )
                
                // Start new segment
                currentSegmentStart = anchor.lineIndex
                currentAnchors = mutableListOf(anchor.matchedAnchor)
            } else {
                // Add to current segment
                currentAnchors.add(anchor.matchedAnchor)
            }
        }
        
        // Add final segment
        if (currentSegmentStart < lines.size) {
            val segmentText = lines.subList(currentSegmentStart, lines.size).joinToString("\n")
            val boundingBox = estimateBoundingBox(currentSegmentStart, lines.size, bitmap)
            
            segments.add(
                CouponSegment(
                    textBlock = segmentText,
                    boundingBox = boundingBox,
                    anchorMatches = currentAnchors.toList(),
                    segmentIndex = segments.size
                )
            )
        }
        
        // Filter out very small segments (likely noise)
        return segments.filter { it.textBlock.trim().length > 20 }
    }
    
    /**
     * Estimate bounding box for a text segment
     */
    private fun estimateBoundingBox(
        startLine: Int,
        endLine: Int,
        bitmap: Bitmap
    ): Rect {
        // Rough estimation based on line positions
        // Assume average line height of 40 pixels
        val lineHeight = 40
        val top = startLine * lineHeight
        val bottom = endLine * lineHeight
        
        return Rect(
            0,
            top.coerceIn(0, bitmap.height),
            bitmap.width,
            bottom.coerceIn(0, bitmap.height)
        )
    }
    
    /**
     * Quick check if text contains multiple coupon indicators
     */
    fun hasMultipleCouponIndicators(text: String): Boolean {
        val normalizedText = text.lowercase()
        
        // Count occurrences of button anchors (strong signal)
        val buttonCount = BUTTON_ANCHORS.count { anchor ->
            val pattern = Regex("""\b$anchor\b""", RegexOption.IGNORE_CASE)
            pattern.findAll(normalizedText).count() > 0
        }
        
        // Multiple button occurrences suggest multiple coupons
        return buttonCount >= 2
    }
    
    /**
     * Get statistics about detected anchors
     */
    fun getAnchorStatistics(text: String): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val normalizedText = text.lowercase()
        
        for (anchor in ALL_ANCHORS) {
            val count = Regex("""\b$anchor\b""", RegexOption.IGNORE_CASE)
                .findAll(normalizedText)
                .count()
            if (count > 0) {
                stats[anchor] = count
            }
        }
        
        return stats
    }
}
