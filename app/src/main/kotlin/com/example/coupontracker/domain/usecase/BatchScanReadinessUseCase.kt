package com.example.coupontracker.domain.usecase

import javax.inject.Inject

class BatchScanReadinessUseCase @Inject constructor() {

    fun decide(
        twoStageDetectorAvailable: Boolean,
        fallbackDetectorAvailable: Boolean,
        detectorInitErrorMessage: String?
    ): BatchScanReadinessDecision {
        return when {
            twoStageDetectorAvailable -> BatchScanReadinessDecision.Ready
            fallbackDetectorAvailable -> BatchScanReadinessDecision.UseOcrAnchorFallback(
                notice = "Multi-coupon detection disabled – using OCR anchor fallback",
                telemetryReason = "ocr_anchor_fallback"
            )
            else -> BatchScanReadinessDecision.Abort(
                message = detectorInitErrorMessage ?: "Multi-coupon detector is unavailable.",
                telemetryReason = "no_detector_or_fallback"
            )
        }
    }
}

sealed class BatchScanReadinessDecision {
    data object Ready : BatchScanReadinessDecision()

    data class UseOcrAnchorFallback(
        val notice: String,
        val telemetryReason: String
    ) : BatchScanReadinessDecision()

    data class Abort(
        val message: String,
        val telemetryReason: String
    ) : BatchScanReadinessDecision()
}
