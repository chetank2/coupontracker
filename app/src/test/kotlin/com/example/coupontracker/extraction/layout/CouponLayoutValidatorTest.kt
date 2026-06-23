package com.example.coupontracker.extraction.layout

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponLayoutValidatorTest {

    @Test
    fun `rejects partial and too incomplete cards by default`() {
        val validator = CouponLayoutValidator(
            CouponLayoutValidationConfig(minCardAreaPx = 100)
        )
        val result = validator.validate(
            CouponLayoutDetection(
                cards = listOf(
                    card(Rect(0, 0, 100, 100), CardCompleteness.PARTIAL),
                    card(Rect(0, 120, 100, 220), CardCompleteness.TOO_INCOMPLETE),
                    card(Rect(0, 240, 100, 340), CardCompleteness.COMPLETE)
                ),
                source = LayoutDetectionSource.VLM,
                confidence = 0.8f
            ),
            imageWidth = 400,
            imageHeight = 500
        )

        assertEquals(1, result.cards.size)
        assertEquals(240, result.cards[0].bounds.top)
        assertTrue(result.diagnostics.rejectedReasons.any { it.contains("partial") })
        assertTrue(result.diagnostics.rejectedReasons.any { it.contains("too_incomplete") })
    }

    @Test
    fun `clamps boxes and removes heavy overlaps`() {
        val validator = CouponLayoutValidator(
            CouponLayoutValidationConfig(minCardAreaPx = 100, maxOverlapIou = 0.5f)
        )
        val result = validator.validate(
            CouponLayoutDetection(
                cards = listOf(
                    card(Rect(-10, -10, 160, 160), confidence = 0.9f, index = 0),
                    card(Rect(0, 0, 150, 150), confidence = 0.8f, index = 1),
                    card(Rect(0, 180, 150, 330), confidence = 0.7f, index = 2)
                ),
                source = LayoutDetectionSource.VLM,
                confidence = 0.8f
            ),
            imageWidth = 300,
            imageHeight = 400
        )

        assertEquals(2, result.cards.size)
        assertEquals(Rect(0, 0, 160, 160), result.cards[0].bounds)
        assertEquals(Rect(0, 180, 150, 330), result.cards[1].bounds)
        assertTrue(result.diagnostics.rejectedReasons.any { it.contains("overlap") })
    }

    @Test
    fun `caps accepted card count`() {
        val validator = CouponLayoutValidator(
            CouponLayoutValidationConfig(minCardAreaPx = 10, maxCards = 2)
        )
        val result = validator.validate(
            CouponLayoutDetection(
                cards = List(4) { index ->
                    card(Rect(0, index * 20, 100, index * 20 + 10), index = index)
                },
                source = LayoutDetectionSource.HEURISTIC,
                confidence = 0.6f
            ),
            imageWidth = 200,
            imageHeight = 200
        )

        assertEquals(2, result.cards.size)
    }

    private fun card(
        bounds: Rect,
        completeness: CardCompleteness = CardCompleteness.COMPLETE,
        confidence: Float = 0.8f,
        index: Int = 0
    ): CouponCardRegion {
        return CouponCardRegion(
            bounds = bounds,
            completeness = completeness,
            confidence = confidence,
            sourceIndex = index
        )
    }
}
