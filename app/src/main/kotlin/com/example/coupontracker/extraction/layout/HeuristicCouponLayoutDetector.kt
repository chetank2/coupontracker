package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap
import com.example.coupontracker.extraction.region.CouponRegionizer

class HeuristicCouponLayoutDetector(
    private val regionizer: CouponRegionizer
) : CouponLayoutDetector {

    override val name: String = "heuristic_regionizer"

    override suspend fun detectLayout(
        bitmap: Bitmap,
        context: LayoutDetectionContext
    ): CouponLayoutDetection {
        val regions = regionizer.regionize(
            bitmap = bitmap,
            screenshotType = context.screenshotType,
            ocrText = context.ocrText,
            fallbackRegions = context.fallbackRegions
        )
        return CouponLayoutDetection(
            cards = regions.mapIndexed { index, region ->
                CouponCardRegion(
                    bounds = region.bounds,
                    completeness = CardCompleteness.COMPLETE,
                    confidence = region.sourceRegion?.confidence ?: 0.65f,
                    reason = region.mode.name.lowercase(),
                    sourceIndex = index,
                    regionMode = region.mode,
                    sourceRegion = region.sourceRegion
                )
            },
            source = LayoutDetectionSource.HEURISTIC,
            confidence = if (regions.isEmpty()) 0f else 0.65f,
            diagnostics = LayoutDiagnostics(
                detectorName = name,
                rawCardCount = regions.size
            )
        )
    }
}
