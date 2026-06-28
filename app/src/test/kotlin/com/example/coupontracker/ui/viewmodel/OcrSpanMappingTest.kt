package com.example.coupontracker.ui.viewmodel

import android.graphics.Rect
import com.example.coupontracker.ocr.OcrTextSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrSpanMappingTest {

    @Test
    fun `maps OCR span boxes to extraction text blocks`() {
        val blocks = ocrSpansToTextBlocks(
            listOf(
                OcrTextSpan(
                    text = "SAVE50",
                    boundingBox = Rect(10, 20, 110, 60),
                    confidence = 0.91f
                )
            )
        )

        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertEquals("SAVE50", block.text)
        assertEquals(10f, block.bounds.left, 0.0f)
        assertEquals(20f, block.bounds.top, 0.0f)
        assertEquals(110f, block.bounds.right, 0.0f)
        assertEquals(60f, block.bounds.bottom, 0.0f)
        assertEquals(0.91f, block.confidence, 0.0f)
    }
}
