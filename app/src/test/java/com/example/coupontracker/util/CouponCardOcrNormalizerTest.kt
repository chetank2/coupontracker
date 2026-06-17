package com.example.coupontracker.util

import android.graphics.Rect
import com.example.coupontracker.ocr.OcrTextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponCardOcrNormalizerTest {

    @Test
    fun `normalize keeps coupon body and drops hero product text`() {
        val spans = spansOf(
            60 to listOf("EXPIRES", "IN", "14", "DAYS"),
            130 to listOf("Ubtan", "Face", "Wash"),
            165 to listOf("HAIR", "FALL", "CONTROL"),
            200 to listOf("Vitamin", "C", "Daily", "Glow", "Sunscreen"),
            430 to listOf("you", "won", "100%", "cashback", "on"),
            466 to listOf("skincare", "essentials", "from"),
            502 to listOf("Mamaearth"),
            548 to listOf("Mamaearth", "4.27"),
            650 to listOf("code:", "C1C4A129JPB7OBE"),
            725 to listOf("Details"),
            725 to listOf("Redeem", "now")
        )

        val result = CouponCardOcrNormalizer.normalize(720, 900, spans)

        assertTrue(result.contains("EXPIRES IN 14 DAYS"))
        assertTrue(result.contains("you won 100% cashback on"))
        assertTrue(result.contains("skincare essentials from"))
        assertTrue(result.contains("Mamaearth"))
        assertTrue(result.contains("code: C1C4A129JPB7OBE"))
        assertFalse(result.contains("Ubtan Face Wash"))
        assertFalse(result.contains("HAIR FALL CONTROL"))
        assertFalse(result.contains("Vitamin C Daily Glow Sunscreen"))
    }

    @Test
    fun `normalize drops previous card code before selected coupon expiry`() {
        val spans = spansOf(
            25 to listOf("code:", "CRDLUKES799"),
            75 to listOf("Details"),
            75 to listOf("Redeem", "Now"),
            180 to listOf("EXPIRES", "IN", "14", "HOURS"),
            450 to listOf("you", "won", "5", "products", "at", "₹999", "+", "₹150"),
            488 to listOf("cashback", "via", "CRED", "pay", "on", "XYXX"),
            540 to listOf("XYXX"),
            650 to listOf("Details"),
            650 to listOf("Redeem", "Now")
        )

        val result = CouponCardOcrNormalizer.normalize(720, 900, spans)

        assertEquals(
            """
                EXPIRES IN 14 HOURS
                you won 5 products at ₹999 + ₹150
                cashback via CRED pay on XYXX
                XYXX
            """.trimIndent(),
            result
        )
    }

    private fun spansOf(vararg rows: Pair<Int, List<String>>): List<OcrTextSpan> {
        return rows.flatMap { (y, words) ->
            words.mapIndexed { index, word ->
                val left = 40 + index * 72
                OcrTextSpan(
                    text = word,
                    boundingBox = Rect(left, y, left + 62, y + 28),
                    confidence = 0.9f
                )
            }
        }
    }
}
