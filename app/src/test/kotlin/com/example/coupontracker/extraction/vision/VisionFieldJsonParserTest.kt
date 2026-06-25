package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionFieldJsonParserTest {
    private val parser = VisionFieldJsonParser()

    @Test
    fun `accepts valid cards and states`() {
        val parsed = parser.parse(
            """
            {
              "confidence": 0.92,
              "cards": [
                {
                  "active": true,
                  "storeName": "IDFC FIRST Bank",
                  "description": "Monthly Interest",
                  "redeemCode": null,
                  "codeState": "NO_CODE_NEEDED",
                  "expiryState": "NOT_VISIBLE",
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.91,
                  "evidence": "No code needed"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.cards.size)
        assertEquals("IDFC FIRST Bank", parsed.activeCard?.storeName)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, parsed.activeCard?.codeState)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, parsed.activeCard?.expiryState)
        assertEquals(Coupon.LayoutState.MODAL_FOREGROUND, parsed.activeCard?.layoutState)
    }

    @Test
    fun `rejects malformed state tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(
                """
                {
                  "cards": [
                    {
                      "codeState": "MAYBE",
                      "expiryState": "NOT_VISIBLE",
                      "layoutState": "COMPLETE"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
    }
}
