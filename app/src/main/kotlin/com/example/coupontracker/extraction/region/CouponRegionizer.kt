package com.example.coupontracker.extraction.region

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.coupontracker.extraction.region.CouponRegionizerConfig.GridConfig
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ml.ScreenshotClassifier
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies lightweight geometric heuristics to carve the screenshot into coupon-ready regions.
 */
class CouponRegionizer(
    private val config: CouponRegionizerConfig
) {

    enum class RegionMode { DEFAULT, POSTER, REWARD, MAP_OVERLAY, MULTI_GRID }

    data class RegionCandidate(
        val bounds: Rect,
        val mode: RegionMode,
        val sourceRegion: HybridCouponDetector.CouponRegion?,
        val index: Int
    )

    fun regionize(
        bitmap: Bitmap,
        screenshotType: ScreenshotClassifier.ScreenshotType,
        ocrText: String,
        fallbackRegions: List<HybridCouponDetector.CouponRegion>
    ): List<RegionCandidate> {
        val workingRect = applyGlobalCrop(bitmap.width, bitmap.height)
        val mode = detectMode(screenshotType, ocrText, fallbackRegions.size, workingRect)

        val regions = when (mode) {
            RegionMode.MULTI_GRID -> buildGridRegions(workingRect, fallbackRegions, config.grid)
            RegionMode.POSTER -> listOf(buildPosterRegion(workingRect))
            RegionMode.REWARD -> listOf(RegionCandidate(workingRect, RegionMode.REWARD, fallbackRegions.firstOrNull(), 0))
            RegionMode.MAP_OVERLAY -> listOf(RegionCandidate(workingRect, RegionMode.MAP_OVERLAY, fallbackRegions.firstOrNull(), 0))
            RegionMode.DEFAULT -> fallbackRegions.takeIf { it.isNotEmpty() }
                ?.mapIndexed { index, region ->
                    RegionCandidate(intersect(region.boundingBox, workingRect) ?: workingRect, RegionMode.DEFAULT, region, index)
                }
                ?: listOf(RegionCandidate(workingRect, RegionMode.DEFAULT, null, 0))
        }

        return regions.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun detectMode(
        screenshotType: ScreenshotClassifier.ScreenshotType,
        rawText: String,
        fallbackCount: Int,
        workingRect: Rect
    ): RegionMode {
        val normalized = rawText.lowercase()

        if (config.reward.dropPhrases.any { normalized.contains(it) }) {
            return RegionMode.REWARD
        }

        if (fallbackCount >= 2 || screenshotType == ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP) {
            return RegionMode.MULTI_GRID
        }

        val aspectRatio = workingRect.height().toFloat() / workingRect.width().toFloat()
        if (aspectRatio > 1.3f && containsPosterSignals(normalized)) {
            return RegionMode.POSTER
        }

        return RegionMode.DEFAULT
    }

    private fun containsPosterSignals(text: String): Boolean {
        val keywords = listOf("flat", "upto", "up to", "% off", "shop now", "limited time")
        return keywords.any { keyword -> text.contains(keyword) }
    }

    private fun applyGlobalCrop(width: Int, height: Int): Rect {
        val topCrop = (height * config.globalCrop.topPct).roundToInt()
        val bottomCrop = (height * config.globalCrop.bottomPct).roundToInt()
        val top = topCrop.coerceIn(0, height / 2)
        val bottom = (height - bottomCrop).coerceAtLeast(top + 1)
        return Rect(0, top, width, bottom)
    }

    private fun buildPosterRegion(base: Rect): RegionCandidate {
        val posterTop = base.top + (base.height() * config.poster.focusTopPct).roundToInt()
        val posterHeight = (base.height() * config.poster.focusHeightPct).roundToInt()
        val posterBottom = min(base.bottom, posterTop + posterHeight)
        val posterRect = Rect(base.left, posterTop, base.right, max(posterTop + 1, posterBottom))
        return RegionCandidate(posterRect, RegionMode.POSTER, null, 0)
    }

    private fun buildGridRegions(
        base: Rect,
        fallback: List<HybridCouponDetector.CouponRegion>,
        gridConfig: GridConfig
    ): List<RegionCandidate> {
        if (fallback.isNotEmpty()) {
            return fallback.mapIndexed { index, region ->
                val clipped = intersect(region.boundingBox, base) ?: base
                RegionCandidate(clipped, RegionMode.MULTI_GRID, region, index)
            }
        }

        val cols = determineColumnCount(base, gridConfig)
        val rows = determineRowCount(base, cols)
        val gap = gridConfig.minGapPx
        val cellWidth = ((base.width() - (gap * (cols - 1))) / cols).coerceAtLeast(gridConfig.minCardWidthPx)
        val cellHeight = (base.height() - (gap * (rows - 1))) / rows

        val regions = mutableListOf<RegionCandidate>()
        var index = 0
        var currentTop = base.top
        for (row in 0 until rows) {
            var currentLeft = base.left
            for (col in 0 until cols) {
                val right = min(base.right, currentLeft + cellWidth)
                val bottom = min(base.bottom, currentTop + cellHeight)
                val rect = Rect(currentLeft, currentTop, right, bottom)
                regions.add(RegionCandidate(rect, RegionMode.MULTI_GRID, null, index++))
                currentLeft = right + gap
                if (currentLeft >= base.right) break
            }
            currentTop += cellHeight + gap
            if (currentTop >= base.bottom) break
        }

        if (regions.isEmpty()) {
            Log.w(TAG, "Grid heuristic failed, falling back to single region")
            regions.add(RegionCandidate(base, RegionMode.MULTI_GRID, null, 0))
        }

        return regions
    }

    private fun determineColumnCount(base: Rect, gridConfig: GridConfig): Int {
        val possibleCols = max(1, base.width() / gridConfig.minCardWidthPx)
        return possibleCols.coerceIn(1, gridConfig.maxCols)
    }

    private fun determineRowCount(base: Rect, cols: Int): Int {
        val aspectRatio = base.height().toFloat() / base.width().toFloat()
        return when {
            aspectRatio > 1.4f -> max(2, ceil(cols / 1.5).roundToInt())
            aspectRatio > 1.0f -> max(1, ceil(cols / 1.2).roundToInt())
            else -> 1
        }
    }

    private fun intersect(original: Rect, clipping: Rect): Rect? {
        val left = max(original.left, clipping.left)
        val top = max(original.top, clipping.top)
        val right = min(original.right, clipping.right)
        val bottom = min(original.bottom, clipping.bottom)
        return if (left < right && top < bottom) Rect(left, top, right, bottom) else null
    }

    companion object {
        private const val TAG = "CouponRegionizer"
    }
}
