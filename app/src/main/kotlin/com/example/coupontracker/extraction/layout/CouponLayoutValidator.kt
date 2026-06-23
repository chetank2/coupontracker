package com.example.coupontracker.extraction.layout

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

class CouponLayoutValidator(
    private val config: CouponLayoutValidationConfig = CouponLayoutValidationConfig()
) {

    fun validate(
        detection: CouponLayoutDetection,
        imageWidth: Int,
        imageHeight: Int
    ): CouponLayoutDetection {
        val rejected = mutableListOf<String>()
        val accepted = detection.cards.mapIndexedNotNull { index, card ->
            val clamped = clamp(card.bounds, imageWidth, imageHeight)
            when {
                clamped == null -> {
                    rejected += "card_$index:out_of_bounds"
                    null
                }
                area(clamped) < config.minCardAreaPx -> {
                    rejected += "card_$index:too_small"
                    null
                }
                card.confidence < config.minConfidence -> {
                    rejected += "card_$index:low_confidence"
                    null
                }
                card.completeness == CardCompleteness.TOO_INCOMPLETE -> {
                    rejected += "card_$index:too_incomplete"
                    null
                }
                card.completeness == CardCompleteness.PARTIAL && !config.allowPartialCards -> {
                    rejected += "card_$index:partial"
                    null
                }
                else -> card.copy(
                    bounds = clamped,
                    confidence = card.confidence.coerceIn(0f, 1f)
                )
            }
        }
            .sortedWith(
                compareByDescending<CouponCardRegion> { qualityScore(it) }
                    .thenBy { it.sourceIndex }
            )
            .dedupeOverlaps(rejected)
            .take(config.maxCards)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        return detection.copy(
            cards = accepted,
            confidence = accepted.map { it.confidence }.averageOrZero().toFloat(),
            diagnostics = detection.diagnostics.copy(
                rawCardCount = detection.cards.size,
                acceptedCardCount = accepted.size,
                rejectedReasons = detection.diagnostics.rejectedReasons + rejected
            )
        )
    }

    private fun clamp(bounds: Rect, imageWidth: Int, imageHeight: Int): Rect? {
        val left = bounds.left.coerceIn(0, imageWidth)
        val top = bounds.top.coerceIn(0, imageHeight)
        val right = bounds.right.coerceIn(left, imageWidth)
        val bottom = bounds.bottom.coerceIn(top, imageHeight)
        return Rect(left, top, right, bottom).takeIf { width(it) > 0 && height(it) > 0 }
    }

    private fun List<CouponCardRegion>.dedupeOverlaps(
        rejected: MutableList<String>
    ): List<CouponCardRegion> {
        val kept = mutableListOf<CouponCardRegion>()
        for (card in this) {
            val overlaps = kept.any { iou(card.bounds, it.bounds) > config.maxOverlapIou }
            if (overlaps) {
                rejected += "card_${card.sourceIndex}:overlap"
            } else {
                kept += card
            }
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0, right - left) * max(0, bottom - top)
        if (intersection == 0) return 0f
        val union = area(a) + area(b) - intersection
        return intersection.toFloat() / union.toFloat()
    }

    private fun qualityScore(card: CouponCardRegion): Float {
        val completenessWeight = when (card.completeness) {
            CardCompleteness.COMPLETE -> 1f
            CardCompleteness.PARTIAL -> 0.75f
            CardCompleteness.TOO_INCOMPLETE -> 0.25f
        }
        return card.confidence + completenessWeight + (card.visibleFields.size * 0.03f)
    }

    private fun width(rect: Rect): Int = rect.right - rect.left

    private fun height(rect: Rect): Int = rect.bottom - rect.top

    private fun area(rect: Rect): Int = width(rect) * height(rect)

    private fun List<Float>.averageOrZero(): Double {
        return if (isEmpty()) 0.0 else average()
    }
}
