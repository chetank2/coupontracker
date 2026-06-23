package com.example.coupontracker.extraction.layout

import android.graphics.Rect
import com.example.coupontracker.extraction.region.CouponRegionizer
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ml.ScreenshotClassifier

data class CouponLayoutDetection(
    val cards: List<CouponCardRegion>,
    val source: LayoutDetectionSource,
    val confidence: Float,
    val diagnostics: LayoutDiagnostics = LayoutDiagnostics()
)

data class CouponCardRegion(
    val bounds: Rect,
    val completeness: CardCompleteness,
    val confidence: Float,
    val visibleFields: Set<VisibleCouponField> = emptySet(),
    val reason: String? = null,
    val sourceIndex: Int = 0,
    val regionMode: CouponRegionizer.RegionMode = CouponRegionizer.RegionMode.DEFAULT,
    val sourceRegion: HybridCouponDetector.CouponRegion? = null
)

enum class CardCompleteness {
    COMPLETE,
    PARTIAL,
    TOO_INCOMPLETE
}

enum class VisibleCouponField {
    MERCHANT,
    OFFER,
    CODE,
    EXPIRY,
    TERMS,
    ACTION
}

enum class LayoutDetectionSource {
    VLM,
    HEURISTIC,
    HYBRID_DETECTOR,
    FALLBACK
}

data class LayoutDiagnostics(
    val detectorName: String? = null,
    val rawCardCount: Int = 0,
    val acceptedCardCount: Int = 0,
    val rejectedReasons: List<String> = emptyList(),
    val fallbackUsed: Boolean = false
)

data class LayoutDetectionContext(
    val screenshotType: ScreenshotClassifier.ScreenshotType,
    val ocrText: String,
    val fallbackRegions: List<HybridCouponDetector.CouponRegion> = emptyList()
)

data class CouponLayoutValidationConfig(
    val minCardAreaPx: Int = 12_000,
    val minConfidence: Float = 0.35f,
    val maxCards: Int = 10,
    val maxOverlapIou: Float = 0.82f,
    val allowPartialCards: Boolean = false,
    val allowSingleFallback: Boolean = true
)
