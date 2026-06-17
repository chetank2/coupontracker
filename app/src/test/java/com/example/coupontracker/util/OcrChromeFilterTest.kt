package com.example.coupontracker.util

import android.graphics.Rect
import com.example.coupontracker.ocr.OcrTextSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrChromeFilterTest {

    private fun span(text: String, left: Int, top: Int): OcrTextSpan {
        return OcrTextSpan(
            text = text,
            boundingBox = Rect(left, top, left + 80, top + 24),
            confidence = 0.9f
        )
    }

    @Test
    fun `removes notification row when top region has Android chrome evidence`() {
        val spans = listOf(
            span("8:05", 10, 20),
            span("Pautm", 80, 20),
            span("Gmail", 150, 20),
            span("5G", 900, 20),
            span("38%", 980, 20),
            span("you", 80, 920),
            span("won", 130, 920),
            span("₹16,500", 190, 920),
            span("off", 300, 920),
            span("on", 350, 920),
            span("Toothsi", 390, 920),
            span("aligners", 490, 920),
            span("code:", 80, 1180),
            span("CREDJACKAPR252C1KQC", 170, 1180)
        )

        val text = OcrChromeFilter.filterAndFlatten(width = fakeBitmapWidth, height = fakeBitmapHeight, spans = spans)

        assertFalse(text.contains("Pautm"))
        assertFalse(text.contains("Gmail"))
        assertFalse(text.contains("5G"))
        assertTrue(text.contains("Toothsi"))
        assertTrue(text.contains("CREDJACKAPR252C1KQC"))
    }

    @Test
    fun `keeps Paytm when it appears in coupon body without top chrome evidence`() {
        val spans = listOf(
            span("Paytm", 80, 520),
            span("Flat", 80, 600),
            span("₹100", 140, 600),
            span("cashback", 220, 600),
            span("code:", 80, 760),
            span("PAYTM100", 170, 760)
        )

        val text = OcrChromeFilter.filterAndFlatten(width = fakeBitmapWidth, height = fakeBitmapHeight, spans = spans)

        assertTrue(text.contains("Paytm"))
        assertTrue(text.contains("PAYTM100"))
    }

    @Test
    fun `keeps coupon content near top below status row`() {
        val spans = listOf(
            span("8:05", 10, 20),
            span("Gmail", 150, 20),
            span("5G", 900, 20),
            span("38%", 980, 20),
            span("Paytm", 80, 170),
            span("Flat", 80, 230),
            span("₹100", 140, 230),
            span("cashback", 220, 230),
            span("code:", 80, 330),
            span("PAYTM100", 170, 330)
        )

        val text = OcrChromeFilter.filterAndFlatten(width = fakeBitmapWidth, height = fakeBitmapHeight, spans = spans)

        assertFalse(text.contains("Gmail"))
        assertFalse(text.contains("5G"))
        assertTrue(text.contains("Paytm"))
        assertTrue(text.contains("PAYTM100"))
    }

    private companion object {
        const val fakeBitmapWidth = 1080
        const val fakeBitmapHeight = 2400
    }
}
