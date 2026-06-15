package com.example.coupontracker.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrMergerTest {

    private fun span(text: String, conf: Float, y: Int): OcrTextSpan =
        OcrTextSpan(text = text, boundingBox = Rect(0, y, 100, y + 20), confidence = conf)

    @Test
    fun `primary spans kept when no overlap`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("AJIO", 0.9f, 0)),
            secondary = listOf(span("SAVE50", 0.8f, 30))
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `overlapping spans prefer higher confidence`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("AJ10", 0.5f, 0)),
            secondary = listOf(span("AJIO", 0.9f, 2))
        )
        assertEquals(1, merged.size)
        assertEquals("AJIO", merged.single().text)
    }

    @Test
    fun `overlap heuristic uses vertical proximity within 10px`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("line1", 0.9f, 0)),
            secondary = listOf(span("line1 alt", 0.95f, 50))
        )
        assertEquals(2, merged.size)
    }
}
