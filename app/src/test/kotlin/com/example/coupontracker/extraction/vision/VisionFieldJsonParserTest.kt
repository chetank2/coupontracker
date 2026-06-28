package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `accepts compact field label schema`() {
        val parsed = parser.parse(
            """
            {"ls":"MODAL_FOREGROUND","s":"IXIGO","d":"Up to 30% Off","cs":"PRESENT","c":"SWGG224CYNU9SPA","es":"NOT_VISIBLE","e":null,"conf":0.8}
            """.trimIndent()
        )

        assertEquals("IXIGO", parsed.activeCard?.storeName)
        assertEquals("Up to 30% Off", parsed.activeCard?.description)
        assertEquals("SWGG224CYNU9SPA", parsed.activeCard?.redeemCode)
        assertEquals(Coupon.CodeState.PRESENT, parsed.activeCard?.codeState)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, parsed.activeCard?.expiryState)
        assertEquals(Coupon.LayoutState.MODAL_FOREGROUND, parsed.activeCard?.layoutState)
    }

    @Test
    fun `compact low confidence placeholder response parses as reviewable unknowns`() {
        val parsed = parser.parse(
            """
            {"cs":null,"conf":"LOW_CONFIDENCE","s":"store","d":"offer"}
            """.trimIndent()
        )

        assertEquals(0f, parsed.confidence)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, parsed.activeCard?.layoutState)
        assertEquals(Coupon.CodeState.UNKNOWN, parsed.activeCard?.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, parsed.activeCard?.expiryState)
        assertNull(parsed.activeCard?.storeName)
        assertNull(parsed.activeCard?.description)
        assertNull(parsed.activeCard?.redeemCode)
        assertNull(parsed.activeCard?.expiryText)
    }
}
