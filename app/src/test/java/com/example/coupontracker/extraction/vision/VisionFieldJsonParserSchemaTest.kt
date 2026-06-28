package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFieldJsonParserSchemaTest {
    private val parser = VisionFieldJsonParser()

    @Test
    fun `parseLayout accepts valid normalized layout cards`() {
        val result = parser.parseLayout(
            """
            {
              "layoutState": "MULTI_CARD",
              "confidence": 0.88,
              "cards": [
                {
                  "id": "card-1",
                  "active": false,
                  "layoutState": "COMPLETE",
                  "confidence": 0.76,
                  "bounds": { "x": 0.08, "y": 0.10, "w": 0.84, "h": 0.28 }
                },
                {
                  "id": "card-2",
                  "active": true,
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.92,
                  "bounds": { "x": 0.12, "y": 0.32, "w": 0.76, "h": 0.38 }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, result.cards.size)
        assertEquals(Coupon.LayoutState.MULTI_CARD, result.layoutState)
        assertEquals("card-2", result.activeCard?.id)
        assertEquals(0.12f, result.activeCard?.bounds?.x ?: -1f, 0.0001f)
        assertEquals(0.76f, result.activeCard?.bounds?.w ?: -1f, 0.0001f)
    }

    @Test
    fun `parseLayout accepts generic region aliases with normalized bounds`() {
        val result = parser.parseLayout(
            """
            {
              "layoutState": "MULTI_CARD",
              "confidence": 0.82,
              "regions": [
                {
                  "active": true,
                  "confidence": 0.84,
                  "bounds": { "x": 0.10, "y": 0.30, "w": 0.80, "h": 0.40 }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, result.cards.size)
        assertEquals(0.10f, result.activeCard?.bounds?.x ?: -1f, 0.0001f)
        assertEquals(0.40f, result.activeCard?.bounds?.h ?: -1f, 0.0001f)
    }

    @Test
    fun `parseLayout still rejects aliases that include final coupon fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.82,
                  "regions": [
                    {
                      "active": true,
                      "confidence": 0.84,
                      "storeName": "Sample Store",
                      "bounds": { "x": 0.10, "y": 0.30, "w": 0.80, "h": 0.40 }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `parseLayout accepts fenced response with preamble`() {
        val result = parser.parseLayout(
            """
            Here is the layout JSON:
            ```json
            {
              "layoutState": "MULTI_CARD",
              "confidence": 0.88,
              "cards": [
                {
                  "id": "card-1",
                  "active": true,
                  "layoutState": "COMPLETE",
                  "confidence": 0.82,
                  "bounds": { "x": 0.09, "y": 0.11, "w": 0.80, "h": 0.31 }
                }
              ]
            }
            ```
            """.trimIndent()
        )

        assertEquals(1, result.cards.size)
        assertEquals("card-1", result.activeCard?.id)
    }

    @Test
    fun `parseLayout clamps minor normalized bounds drift`() {
        val result = parser.parseLayout(
            """
            {
              "layoutState": "COMPLETE",
              "confidence": 0.86,
              "cards": [
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.8,
                  "bounds": { "x": -0.01, "y": 0.10, "w": 0.72, "h": 0.91 }
                }
              ]
            }
            """.trimIndent()
        )

        val bounds = result.activeCard?.bounds
        assertEquals(0f, bounds?.x ?: -1f, 0.0001f)
        assertEquals(1f, bounds?.bottom ?: -1f, 0.0001f)
    }

    @Test
    fun `parseLayout rejects truncated layout JSON with clear error`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                ```json
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.86,
                  "cards": [
                    {
                      "layoutState": "COMPLETE",
                      "confidence": 0.8,
                      "bounds": { "x": 0.10, "y": 0.12, "w": 0.72, "h": 0.31 }
                """.trimIndent()
            )
        }

        assertEquals("Unterminated JSON object in layout response", error.message)
    }

    @Test
    fun `parseLayout rejects malformed bounds and unknown layout states`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                {
                  "layoutState": "MULTI_CARD",
                  "confidence": 0.9,
                  "cards": [
                    {
                      "layoutState": "COMPLETE",
                      "confidence": 0.8,
                      "bounds": { "x": 0.25, "y": 0.25, "w": 0.01, "h": 0.01 }
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                {
                  "layoutState": "STACKED",
                  "confidence": 0.9,
                  "cards": [
                    {
                      "layoutState": "COMPLETE",
                      "confidence": 0.8,
                      "bounds": { "x": 0.10, "y": 0.10, "w": 0.50, "h": 0.30 }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `parseFieldLabels accepts fenced response with preamble`() {
        val result = parser.parseFieldLabels(
            """
            Field states:
            ```json
            {
              "layoutState": "MODAL_FOREGROUND",
              "confidence": 0.86,
              "fields": {
                "store": { "state": "PRESENT", "text": "CRED", "evidence": ["CRED"], "confidence": 0.9 },
                "description": { "state": "PRESENT", "text": "Save 10%", "evidence": ["Save 10%"], "confidence": 0.9 },
                "code": { "state": "NO_CODE_NEEDED", "text": null, "evidence": ["No code needed"], "confidence": 0.8 },
                "expiry": { "state": "NOT_VISIBLE", "text": null, "evidence": ["No expiry shown"], "confidence": 0.7 }
              }
            }
            ```
            """.trimIndent()
        )

        assertEquals("CRED", result.fields.store.text)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, result.fields.code.state)
    }

    @Test
    fun `parseFieldLabels accepts valid labeled evidence`() {
        val result = parser.parseFieldLabels(
            """
            {
              "layoutState": "MODAL_FOREGROUND",
              "confidence": 0.86,
              "fields": {
                "store": {
                  "state": "PRESENT",
                  "text": "IDFC FIRST Bank",
                  "evidence": ["IDFC FIRST Bank"],
                  "confidence": 0.93
                },
                "description": {
                  "state": "PRESENT",
                  "text": "Monthly Interest",
                  "evidence": ["Monthly Interest"],
                  "confidence": 0.91
                },
                "code": {
                  "state": "NO_CODE_NEEDED",
                  "text": null,
                  "evidence": ["No code needed"],
                  "confidence": 0.89
                },
                "expiry": {
                  "state": "NOT_VISIBLE",
                  "text": null,
                  "evidence": ["No expiry shown"],
                  "confidence": 0.72
                }
              },
              "noise": ["legal footer"]
            }
            """.trimIndent()
        )

        assertEquals(Coupon.LayoutState.MODAL_FOREGROUND, result.layoutState)
        assertEquals("IDFC FIRST Bank", result.fields.store.text)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, result.fields.code.state)
        assertEquals(listOf("No code needed"), result.fields.code.evidence)
        assertEquals(listOf("legal footer"), result.noise)
    }

    @Test
    fun `parseFieldLabels accepts zero confidence reviewable states`() {
        val result = parser.parseFieldLabels(
            """
            {
              "layoutState": "LOW_CONFIDENCE",
              "confidence": 0.0,
              "fields": {
                "store": {
                  "state": "UNKNOWN",
                  "text": null,
                  "evidence": [],
                  "confidence": 0.0
                },
                "description": {
                  "state": "UNKNOWN",
                  "text": null,
                  "evidence": [],
                  "confidence": 0.0
                },
                "code": {
                  "state": "NOT_VISIBLE",
                  "text": null,
                  "evidence": [],
                  "confidence": 0.0
                },
                "expiry": {
                  "state": "NOT_VISIBLE",
                  "text": null,
                  "evidence": [],
                  "confidence": 0.0
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(0f, result.confidence)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, result.layoutState)
        assertEquals(Coupon.CodeState.NOT_VISIBLE, result.fields.code.state)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, result.fields.expiry.state)
    }

    @Test
    fun `parseFieldLabels accepts compact null states and string low confidence as reviewable`() {
        val result = parser.parseFieldLabels(
            """
            {
              "ls": "MODAL_FOREGROUND",
              "s": "store",
              "d": "offer",
              "cs": null,
              "c": null,
              "es": "PRESENT",
              "e": null,
              "conf": "LOW_CONFIDENCE"
            }
            """.trimIndent()
        )

        assertEquals(Coupon.LayoutState.MODAL_FOREGROUND, result.layoutState)
        assertEquals(0f, result.confidence)
        assertEquals(Coupon.CodeState.UNKNOWN, result.fields.code.state)
        assertEquals(Coupon.ExpiryState.UNKNOWN, result.fields.expiry.state)
        assertEquals(null, result.fields.store.text)
        assertEquals(null, result.fields.description.text)
        assertEquals(null, result.fields.code.text)
        assertEquals(null, result.fields.expiry.text)
    }

    @Test
    fun `parseFieldLabels accepts compact invalid low confidence states as reviewable`() {
        val result = parser.parseFieldLabels(
            """
            {
              "ls": "MODAL_FOREGROUND",
              "s": "store",
              "d": "offer",
              "cs": null,
              "c": null,
              "es": "Flat 15% off*",
              "e": null,
              "conf": "LOW_CONFIDENCE"
            }
            """.trimIndent()
        )

        assertEquals(Coupon.LayoutState.MODAL_FOREGROUND, result.layoutState)
        assertEquals(0f, result.confidence)
        assertEquals(Coupon.CodeState.UNKNOWN, result.fields.code.state)
        assertEquals(Coupon.ExpiryState.UNKNOWN, result.fields.expiry.state)
        assertEquals(null, result.fields.store.text)
        assertEquals(null, result.fields.description.text)
    }

    @Test
    fun `parseFieldLabels treats compact expiry text in state slot as evidence`() {
        val result = parser.parseFieldLabels(
            """
            {
              "ls": "COMPLETE",
              "s": "store",
              "d": "offer",
              "cs": null,
              "c": null,
              "es": "EXPIRES IN 13 DAYS",
              "e": "EXPIRES IN 13 DAYS",
              "conf": null
            }
            """.trimIndent()
        )

        assertEquals(Coupon.LayoutState.COMPLETE, result.layoutState)
        assertEquals(0.5f, result.confidence)
        assertEquals(Coupon.CodeState.UNKNOWN, result.fields.code.state)
        assertEquals(Coupon.ExpiryState.UNKNOWN, result.fields.expiry.state)
        assertEquals(null, result.fields.expiry.text)
        assertEquals(null, result.fields.store.text)
        assertEquals(null, result.fields.description.text)
    }

    @Test
    fun `parseFieldLabels keeps compact non English expiry text in state slot review-only`() {
        val result = parser.parseFieldLabels(
            """
            {
              "ls": "COMPLETE",
              "s": "Sample Store",
              "d": "Save 10%",
              "cs": "NOT_VISIBLE",
              "c": null,
              "es": "EXPIRA EN 13 DIAS",
              "conf": 0.5
            }
            """.trimIndent()
        )

        assertEquals(Coupon.ExpiryState.UNKNOWN, result.fields.expiry.state)
        assertEquals(null, result.fields.expiry.text)
    }

    @Test
    fun `parseFieldLabels rejects unknown field states and malformed fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseFieldLabels(
                """
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.8,
                  "fields": {
                    "store": { "state": "PRESENT", "text": "CRED", "evidence": ["CRED"], "confidence": 0.8 },
                    "description": { "state": "PRESENT", "text": "Save 10%", "evidence": ["Save 10%"], "confidence": 0.8 },
                    "code": { "state": "MAYBE", "text": null, "evidence": ["unclear"], "confidence": 0.8 },
                    "expiry": { "state": "NOT_VISIBLE", "text": null, "evidence": ["not shown"], "confidence": 0.8 }
                  }
                }
                """.trimIndent()
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseFieldLabels(
                """
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.8,
                  "fields": {
                    "store": { "state": "PRESENT", "text": "CRED", "evidence": ["CRED"], "confidence": 0.8 },
                    "description": { "state": "PRESENT", "text": "Save 10%", "evidence": ["Save 10%"], "confidence": 0.8 },
                    "code": { "state": "NOT_VISIBLE", "text": null, "evidence": ["not shown"], "confidence": 0.8 }
                  }
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `parseLayout rejects final coupon fields in full screenshot response`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.84,
                  "cards": [
                    {
                      "layoutState": "COMPLETE",
                      "confidence": 0.82,
                      "bounds": { "x": 0.08, "y": 0.12, "w": 0.84, "h": 0.34 },
                      "redeemCode": "SAVE50"
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertNotNull(error.message)
    }

    @Test
    fun `parseLayout rejects unusable normalized bounds beyond minor drift`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parseLayout(
                """
                {
                  "layoutState": "COMPLETE",
                  "confidence": 0.84,
                  "cards": [
                    {
                      "layoutState": "COMPLETE",
                      "confidence": 0.82,
                      "bounds": { "x": -0.08, "y": 0.12, "w": 0.84, "h": 0.34 }
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("unusable normalized bounds"))
    }
}
