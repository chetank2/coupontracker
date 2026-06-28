package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class CouponLayoutDetectionPipeline(
    private val detectors: List<CouponLayoutDetector>,
    private val validator: CouponLayoutValidator,
    private val config: CouponLayoutValidationConfig = CouponLayoutValidationConfig()
) {

    suspend fun detect(
        bitmap: Bitmap,
        context: LayoutDetectionContext
    ): CouponLayoutDetection {
        val rejected = mutableListOf<String>()
        var firstValidDetection: CouponLayoutDetection? = null
        val preferExistingRegions = context.fallbackRegions.isNotEmpty()
        for (detector in detectors) {
            val detection = runCatching {
                withTimeout(DETECTOR_TIMEOUT_MS) {
                    detector.detectLayout(bitmap, context)
                }
            }
                .onFailure { rejected += "${detector.name}:${it.javaClass.simpleName}" }
                .getOrElse {
                    if (it is TimeoutCancellationException) {
                        rejected += "${detector.name}:timeout_${DETECTOR_TIMEOUT_MS}ms"
                    }
                    CouponLayoutDetection(
                        cards = emptyList(),
                        source = LayoutDetectionSource.FALLBACK,
                        confidence = 0f,
                        diagnostics = LayoutDiagnostics(detectorName = detector.name)
                    )
                }
            val validated = validator.validate(detection, bitmap.width, bitmap.height)
            rejected += validated.diagnostics.rejectedReasons
            if (validated.cards.isNotEmpty()) {
                val withDiagnostics = validated.copy(
                    diagnostics = validated.diagnostics.copy(rejectedReasons = rejected)
                )
                if (!preferExistingRegions || validated.source != LayoutDetectionSource.VLM) {
                    return withDiagnostics
                }
                firstValidDetection = firstValidDetection ?: withDiagnostics
            }
        }

        firstValidDetection?.let { return it }

        val fallback = if (config.allowSingleFallback) {
            listOf(
                CouponCardRegion(
                    bounds = Rect(0, 0, bitmap.width, bitmap.height),
                    completeness = CardCompleteness.COMPLETE,
                    confidence = 0.2f,
                    reason = "single_region_fallback"
                )
            )
        } else {
            emptyList()
        }

        return CouponLayoutDetection(
            cards = fallback,
            source = LayoutDetectionSource.FALLBACK,
            confidence = fallback.firstOrNull()?.confidence ?: 0f,
            diagnostics = LayoutDiagnostics(
                detectorName = "fallback",
                rawCardCount = fallback.size,
                acceptedCardCount = fallback.size,
                rejectedReasons = rejected,
                fallbackUsed = true
            )
        )
    }

    private companion object {
        private const val DETECTOR_TIMEOUT_MS = 15_000L
    }
}
