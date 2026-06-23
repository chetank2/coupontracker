package com.example.coupontracker.extraction.layout

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponLayoutJsonParserTest {

    private val parser = CouponLayoutJsonParser()

    @Test
    fun `parses vlm layout json with visible fields`() {
        val detection = parser.parse(
            """
            {
              "confidence": 0.91,
              "cards": [
                {
                  "box": {"x": 12, "y": 80, "width": 300, "height": 180},
                  "completeness": "complete",
                  "confidence": 0.88,
                  "visibleFields": ["merchant", "offer", "code", "expiry"],
                  "reason": "full card visible"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(LayoutDetectionSource.VLM, detection.source)
        assertEquals(1, detection.cards.size)
        assertEquals(CardCompleteness.COMPLETE, detection.cards[0].completeness)
        assertEquals(Rect(12, 80, 312, 260), detection.cards[0].bounds)
        assertTrue(detection.cards[0].visibleFields.contains(VisibleCouponField.CODE))
    }

    @Test
    fun `extracts first json object from surrounding model text`() {
        val detection = parser.parse(
            """
            Here is the result:
            {"cards":[{"box":{"x":0,"y":10,"width":100,"height":120},"completeness":"partial","confidence":0.7}]}
            Done.
            """.trimIndent()
        )

        assertEquals(1, detection.cards.size)
        assertEquals(CardCompleteness.PARTIAL, detection.cards[0].completeness)
    }
}
