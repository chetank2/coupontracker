package com.example.coupontracker.universal

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal layout analyzer that understands coupon structure without
 * brand-specific templates, using visual cues and ML-based region detection.
 */
@Singleton
class UniversalLayoutAnalyzer @Inject constructor() {
    
    companion object {
        private const val TAG = "UniversalLayoutAnalyzer"
    }

    /**
     * Analyze coupon structure and identify key regions
     */
    suspend fun analyzeCouponStructure(image: Bitmap): CouponLayout = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Analyzing coupon layout for ${image.width}x${image.height} image")
        
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        
        // Analyze image to detect regions
        val logoRegion = detectLogoRegion(image, width, height)
        val codeRegion = detectCodeRegion(image, width, height)
        val amountRegion = detectAmountRegion(image, width, height)
        val expiryRegion = detectExpiryRegion(image, width, height)
        val termsRegion = detectTermsRegion(image, width, height)
        
        CouponLayout(
            logoRegion = logoRegion,
            codeRegion = codeRegion,
            amountRegion = amountRegion,
            expiryRegion = expiryRegion,
            termsRegion = termsRegion,
            confidence = calculateLayoutConfidence(logoRegion, codeRegion, amountRegion, expiryRegion)
        )
    }

    /**
     * Detect logo/brand region (usually top portion)
     */
    private fun detectLogoRegion(image: Bitmap, width: Float, height: Float): Region? {
        // Logo is typically in the top 30% of the image
        val topRegion = RectF(0f, 0f, width, height * 0.3f)
        
        // Use visual analysis to find the most prominent area in top region
        val logoArea = findMostProminentArea(image, topRegion)
        
        return if (logoArea != null) {
            Region(
                bounds = logoArea,
                confidence = 0.7f,
                type = RegionType.LOGO,
                visualCues = listOf("top_position", "prominent_area")
            )
        } else {
            // Fallback to entire top region
            Region(
                bounds = topRegion,
                confidence = 0.5f,
                type = RegionType.LOGO,
                visualCues = listOf("top_position")
            )
        }
    }

    /**
     * Detect coupon code region (often center or highlighted area)
     */
    private fun detectCodeRegion(image: Bitmap, width: Float, height: Float): Region? {
        // Look for visually distinct areas that could contain codes
        val distinctAreas = findVisuallyDistinctAreas(image)
        
        // Prefer areas in the center or with box-like boundaries
        val codeArea = distinctAreas
            .filter { isLikelyCodeArea(it, width, height) }
            .maxByOrNull { it.confidence }
        
        return codeArea?.let {
            Region(
                bounds = it.bounds,
                confidence = it.confidence,
                type = RegionType.CODE,
                visualCues = it.visualCues
            )
        }
    }

    /**
     * Detect amount/discount region (often prominent and colorful)
     */
    private fun detectAmountRegion(image: Bitmap, width: Float, height: Float): Region? {
        // Look for areas with high contrast or color prominence
        val prominentAreas = findHighContrastAreas(image)
        
        // Amount regions are often in upper-middle or center-left
        val amountArea = prominentAreas
            .filter { isLikelyAmountArea(it, width, height) }
            .maxByOrNull { it.confidence }
        
        return amountArea?.let {
            Region(
                bounds = it.bounds,
                confidence = it.confidence,
                type = RegionType.AMOUNT,
                visualCues = it.visualCues + "high_contrast"
            )
        }
    }

    /**
     * Detect expiry date region (often bottom area with small text)
     */
    private fun detectExpiryRegion(image: Bitmap, width: Float, height: Float): Region? {
        // Expiry is typically in the bottom 40% of the image
        val bottomRegion = RectF(0f, height * 0.6f, width, height)
        
        // Look for small text areas in bottom region
        val smallTextAreas = findSmallTextAreas(image, bottomRegion)
        
        val expiryArea = smallTextAreas
            .filter { isLikelyExpiryArea(it, width, height) }
            .maxByOrNull { it.confidence }
        
        return if (expiryArea != null) {
            Region(
                bounds = expiryArea.bounds,
                confidence = expiryArea.confidence,
                type = RegionType.EXPIRY,
                visualCues = expiryArea.visualCues + "bottom_position"
            )
        } else {
            // Fallback to bottom region
            Region(
                bounds = bottomRegion,
                confidence = 0.3f,
                type = RegionType.EXPIRY,
                visualCues = listOf("bottom_position", "fallback")
            )
        }
    }

    /**
     * Detect terms and conditions region (usually bottom with very small text)
     */
    private fun detectTermsRegion(image: Bitmap, width: Float, height: Float): Region? {
        // Terms are typically in the bottom 20% with very small text
        val bottomRegion = RectF(0f, height * 0.8f, width, height)
        
        return Region(
            bounds = bottomRegion,
            confidence = 0.4f,
            type = RegionType.TERMS,
            visualCues = listOf("bottom_position", "small_text")
        )
    }

    // Visual analysis helper methods
    
    private fun findMostProminentArea(image: Bitmap, searchRegion: RectF): RectF? {
        val stats = sampleRegionCells(
            image = image,
            region = searchRegion,
            columns = 6,
            rows = 3
        )

        if (stats.isEmpty()) {
            return null
        }

        val best = stats.maxByOrNull { cell ->
            (cell.edgeDensity * 0.55f) +
                (cell.saturationMean * 0.3f) +
                (cell.colorRangeMean * 0.15f)
        } ?: return null

        return expandBounds(best.bounds, searchRegion, 0.2f)
    }
    
    private fun findVisuallyDistinctAreas(image: Bitmap): List<VisualArea> {
        val fullImage = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val stats = sampleRegionCells(
            image = image,
            region = fullImage,
            columns = 6,
            rows = 4
        )

        if (stats.isEmpty()) {
            return emptyList()
        }

        val maxSaturation = stats.maxOf { it.saturationMean }.takeIf { it > 0f } ?: 1f
        val maxColorRange = stats.maxOf { it.colorRangeMean }.takeIf { it > 0f } ?: 1f

        val candidates = stats.mapNotNull { cell ->
            val saturationScore = cell.saturationMean / maxSaturation
            val colorScore = cell.colorRangeMean / maxColorRange
            val edgeScore = cell.edgeDensity

            val combined = (saturationScore * 0.4f) + (colorScore * 0.3f) + (edgeScore * 0.3f)
            if (combined < 0.35f) {
                null
            } else {
                val cues = buildList {
                    if (saturationScore > 0.5f) add("high_saturation")
                    if (colorScore > 0.5f) add("color_contrast")
                    if (edgeScore > 0.4f) add("edge_density")
                    if (cell.bounds.centerY() in (fullImage.height() * 0.3f)..(fullImage.height() * 0.7f)) {
                        add("mid_region")
                    }
                }
                VisualArea(
                    bounds = expandBounds(cell.bounds, fullImage, 0.1f),
                    confidence = combined.coerceIn(0f, 1f),
                    visualCues = cues
                )
            }
        }.sortedByDescending { it.confidence }

        return mergeOverlappingVisualAreas(candidates).take(5)
    }
    
    private fun findHighContrastAreas(image: Bitmap): List<VisualArea> {
        val fullImage = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val stats = sampleRegionCells(
            image = image,
            region = fullImage,
            columns = 6,
            rows = 4
        )

        if (stats.isEmpty()) {
            return emptyList()
        }

        val maxVariance = stats.maxOf { it.luminanceVariance }.takeIf { it > 0f } ?: 1f
        val maxEdge = stats.maxOf { it.edgeDensity }.takeIf { it > 0f } ?: 1f

        val areas = stats.mapNotNull { cell ->
            val varianceScore = (cell.luminanceVariance / maxVariance).coerceIn(0f, 1f)
            val edgeScore = (cell.edgeDensity / maxEdge).coerceIn(0f, 1f)
            val combined = (varianceScore * 0.6f) + (edgeScore * 0.4f)

            if (combined < 0.3f) {
                null
            } else {
                val cues = buildList {
                    add("contrast")
                    if (varianceScore > 0.6f) add("luminance_variance")
                    if (edgeScore > 0.5f) add("edge_density")
                }
                VisualArea(
                    bounds = expandBounds(cell.bounds, fullImage, 0.12f),
                    confidence = combined.coerceIn(0f, 1f),
                    visualCues = cues
                )
            }
        }.sortedByDescending { it.confidence }

        return mergeOverlappingVisualAreas(areas).take(5)
    }
    
    private fun findSmallTextAreas(image: Bitmap, searchRegion: RectF): List<VisualArea> {
        val stats = sampleRegionCells(
            image = image,
            region = searchRegion,
            columns = 8,
            rows = 4
        )

        if (stats.isEmpty()) {
            return emptyList()
        }

        val maxEdge = stats.maxOf { it.edgeDensity }.takeIf { it > 0f } ?: 1f
        val regionArea = max(searchRegion.width() * searchRegion.height(), 1f)

        val areas = stats.mapNotNull { cell ->
            val area = cell.bounds.width() * cell.bounds.height()
            val relativeArea = (area / regionArea).coerceIn(0f, 1f)

            val edgeScore = (cell.edgeDensity / maxEdge).coerceIn(0f, 1f)
            val contrastScore = (cell.luminanceVariance + cell.colorRangeMean) / 2f
            val smallAreaBoost = (1f - relativeArea).coerceIn(0.2f, 1f)

            val combined = (edgeScore * 0.6f) + (contrastScore * 0.2f) + (smallAreaBoost * 0.2f)

            if (combined < 0.35f) {
                null
            } else {
                val cues = buildList {
                    add("small_text")
                    if (edgeScore > 0.5f) add("edge_density")
                    if (relativeArea < 0.15f) add("compact_region")
                    if (cell.bounds.centerY() > searchRegion.centerY()) add("bottom_position")
                }
                VisualArea(
                    bounds = expandBounds(cell.bounds, searchRegion, 0.08f),
                    confidence = combined.coerceIn(0f, 1f),
                    visualCues = cues
                )
            }
        }.sortedByDescending { it.confidence }

        return mergeOverlappingVisualAreas(areas).take(6)
    }
    
    // Area classification helper methods

    private fun isLikelyCodeArea(area: VisualArea, width: Float, height: Float): Boolean {
        val bounds = area.bounds
        
        // Code areas are typically:
        // - Not too wide (codes aren't usually very long visually)
        // - In the center or middle portion of the image
        // - Have reasonable aspect ratio
        
        val areaWidth = bounds.width()
        val areaHeight = bounds.height()
        val aspectRatio = areaWidth / areaHeight
        
        return bounds.centerY() > height * 0.2f && // Not in top logo area
               bounds.centerY() < height * 0.8f && // Not in bottom terms area
               areaWidth < width * 0.8f &&         // Not too wide
               aspectRatio > 0.5f &&               // Not too tall
               aspectRatio < 8.0f                  // Not too wide
    }

    private fun isLikelyAmountArea(area: VisualArea, width: Float, height: Float): Boolean {
        val bounds = area.bounds
        
        // Amount areas are typically:
        // - In upper portion (but not logo area)
        // - Prominent and attention-grabbing
        // - Not too small
        
        val areaSize = bounds.width() * bounds.height()
        val totalSize = width * height
        val sizeRatio = areaSize / totalSize
        
        return bounds.centerY() < height * 0.7f &&  // In upper portion
               sizeRatio > 0.02f &&                 // Not too small
               sizeRatio < 0.3f                     // Not too large
    }

    private fun isLikelyExpiryArea(area: VisualArea, width: Float, height: Float): Boolean {
        val bounds = area.bounds
        
        // Expiry areas are typically:
        // - In bottom portion
        // - Smaller text areas
        // - Horizontally oriented
        
        val aspectRatio = bounds.width() / bounds.height()
        
        return bounds.top > height * 0.5f &&        // In bottom half
               aspectRatio > 2.0f                   // Horizontally oriented
    }

    private fun calculateLayoutConfidence(
        logoRegion: Region?,
        codeRegion: Region?,
        amountRegion: Region?,
        expiryRegion: Region?
    ): Float {
        var confidence = 0.0f
        var regionCount = 0
        
        logoRegion?.let { confidence += it.confidence; regionCount++ }
        codeRegion?.let { confidence += it.confidence; regionCount++ }
        amountRegion?.let { confidence += it.confidence; regionCount++ }
        expiryRegion?.let { confidence += it.confidence; regionCount++ }
        
        return if (regionCount > 0) confidence / regionCount else 0.0f
    }

    private fun sampleRegionCells(
        image: Bitmap,
        region: RectF,
        columns: Int,
        rows: Int
    ): List<CellStats> {
        if (columns <= 0 || rows <= 0) {
            return emptyList()
        }

        val boundedRegion = clampRegionToImage(region, image) ?: return emptyList()

        val left = max(0, floor(boundedRegion.left).toInt())
        val top = max(0, floor(boundedRegion.top).toInt())
        val right = min(image.width, ceil(boundedRegion.right).toInt())
        val bottom = min(image.height, ceil(boundedRegion.bottom).toInt())

        val width = right - left
        val height = bottom - top
        if (width <= 1 || height <= 1) {
            return emptyList()
        }

        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, left, top, width, height)

        val columnEdges = IntArray(columns + 1) { idx ->
            if (idx == columns) {
                width
            } else {
                (width * idx) / columns
            }
        }
        val rowEdges = IntArray(rows + 1) { idx ->
            if (idx == rows) {
                height
            } else {
                (height * idx) / rows
            }
        }

        val results = mutableListOf<CellStats>()

        for (col in 0 until columns) {
            val xStart = columnEdges[col]
            val xEnd = columnEdges[col + 1]
            if (xEnd - xStart <= 1) continue

            for (row in 0 until rows) {
                val yStart = rowEdges[row]
                val yEnd = rowEdges[row + 1]
                if (yEnd - yStart <= 1) continue

                var luminanceSum = 0f
                var luminanceSumSq = 0f
                var saturationSum = 0f
                var colorRangeSum = 0f
                var edgeSum = 0f

                val pixelCount = (xEnd - xStart) * (yEnd - yStart)
                if (pixelCount <= 0) continue

                for (y in yStart until yEnd) {
                    val rowOffset = y * width
                    for (x in xStart until xEnd) {
                        val pixel = pixels[rowOffset + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        val luminance = computeLuminance(r, g, b)
                        luminanceSum += luminance
                        luminanceSumSq += luminance * luminance

                        val saturation = computeSaturation(r, g, b)
                        saturationSum += saturation

                        val colorRange = computeColorRange(r, g, b)
                        colorRangeSum += colorRange

                        if (x + 1 < xEnd) {
                            val neighbor = pixels[rowOffset + x + 1]
                            edgeSum += abs(luminance - computeLuminance(neighbor))
                        }
                        if (y + 1 < yEnd) {
                            val neighbor = pixels[(y + 1) * width + x]
                            edgeSum += abs(luminance - computeLuminance(neighbor))
                        }
                    }
                }

                val horizontalEdges = max(0, (xEnd - xStart - 1)) * (yEnd - yStart)
                val verticalEdges = max(0, (yEnd - yStart - 1)) * (xEnd - xStart)
                val maxEdgeSamples = max(1, horizontalEdges + verticalEdges)

                val meanLuminance = luminanceSum / pixelCount
                val variance = max(
                    0f,
                    (luminanceSumSq / pixelCount) - (meanLuminance * meanLuminance)
                ) / (255f * 255f)

                val edgeDensity = (edgeSum / (maxEdgeSamples * 255f)).coerceIn(0f, 1f)
                val saturationMean = (saturationSum / pixelCount).coerceIn(0f, 1f)
                val colorRangeMean = (colorRangeSum / pixelCount).coerceIn(0f, 1f)

                val bounds = RectF(
                    (left + xStart).toFloat(),
                    (top + yStart).toFloat(),
                    (left + xEnd).toFloat(),
                    (top + yEnd).toFloat()
                )

                results += CellStats(
                    bounds = bounds,
                    luminanceMean = meanLuminance / 255f,
                    luminanceVariance = variance,
                    edgeDensity = edgeDensity,
                    saturationMean = saturationMean,
                    colorRangeMean = colorRangeMean
                )
            }
        }

        return results
    }

    private fun clampRegionToImage(region: RectF, image: Bitmap): RectF? {
        val imageBounds = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val bounded = RectF(region)
        return if (bounded.intersect(imageBounds)) bounded else null
    }

    private fun expandBounds(bounds: RectF, clampRegion: RectF, expansionRatio: Float): RectF {
        if (expansionRatio <= 0f) {
            return RectF(bounds)
        }

        val dx = bounds.width() * expansionRatio
        val dy = bounds.height() * expansionRatio
        val expanded = RectF(
            bounds.left - dx,
            bounds.top - dy,
            bounds.right + dx,
            bounds.bottom + dy
        )

        val clampCopy = RectF(clampRegion)
        return if (expanded.intersect(clampCopy)) expanded else RectF(bounds)
    }

    private fun mergeOverlappingVisualAreas(
        areas: List<VisualArea>,
        overlapThreshold: Float = 0.4f
    ): List<VisualArea> {
        if (areas.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf<VisualArea>()
        areas.forEach { candidate ->
            val overlapsExisting = merged.any { existing ->
                overlapRatio(existing.bounds, candidate.bounds) > overlapThreshold
            }
            if (!overlapsExisting) {
                merged += candidate
            }
        }
        return merged
    }

    private fun overlapRatio(a: RectF, b: RectF): Float {
        val intersection = RectF(a)
        if (!intersection.intersect(b)) {
            return 0f
        }

        val intersectionArea = intersection.width() * intersection.height()
        if (intersectionArea <= 0f) {
            return 0f
        }

        val minArea = min(
            a.width() * a.height(),
            b.width() * b.height()
        )

        if (minArea <= 0f) {
            return 0f
        }

        return intersectionArea / minArea
    }

    private fun computeLuminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return computeLuminance(r, g, b)
    }

    private fun computeLuminance(r: Int, g: Int, b: Int): Float {
        return (0.299f * r) + (0.587f * g) + (0.114f * b)
    }

    private fun computeSaturation(r: Int, g: Int, b: Int): Float {
        val maxChannel = max(r, max(g, b)).toFloat()
        val minChannel = min(r, min(g, b)).toFloat()
        if (maxChannel == 0f) {
            return 0f
        }
        return (maxChannel - minChannel) / maxChannel
    }

    private fun computeColorRange(r: Int, g: Int, b: Int): Float {
        val maxChannel = max(r, max(g, b)).toFloat()
        val minChannel = min(r, min(g, b)).toFloat()
        return (maxChannel - minChannel) / 255f
    }

    private data class CellStats(
        val bounds: RectF,
        val luminanceMean: Float,
        val luminanceVariance: Float,
        val edgeDensity: Float,
        val saturationMean: Float,
        val colorRangeMean: Float
    )
}

/**
 * Represents the layout structure of a coupon
 */
data class CouponLayout(
    val logoRegion: Region? = null,
    val codeRegion: Region? = null,
    val amountRegion: Region? = null,
    val expiryRegion: Region? = null,
    val termsRegion: Region? = null,
    val confidence: Float = 0.0f
)

/**
 * Represents a region within a coupon image
 */
data class Region(
    val bounds: RectF,
    val confidence: Float,
    val type: RegionType,
    val visualCues: List<String> = emptyList()
)

/**
 * Types of regions in a coupon
 */
enum class RegionType {
    LOGO,
    CODE,
    AMOUNT,
    EXPIRY,
    TERMS
}

/**
 * Represents a visually distinct area found during analysis
 */
data class VisualArea(
    val bounds: RectF,
    val confidence: Float,
    val visualCues: List<String> = emptyList()
)
