package com.example.coupontracker.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrFallbackPredicateTest {

    private fun result(text: String, conf: Float = 0.9f) = OcrResult(text, emptyList(), conf)

    @Test
    fun `short text triggers TOO_LITTLE_TEXT`() {
        assertEquals(OcrFallbackReason.TOO_LITTLE_TEXT,
            OcrFallbackPredicates.TOO_LITTLE_TEXT.evaluate(result("hi")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.TOO_LITTLE_TEXT.evaluate(
                result("Flipkart Big Saving Days code FLIP100")))
    }

    @Test
    fun `missing code triggers NO_CODE_REGION`() {
        assertEquals(OcrFallbackReason.NO_CODE_REGION,
            OcrFallbackPredicates.NO_CODE_REGION.evaluate(result("just prose no codes here")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.NO_CODE_REGION.evaluate(result("use SAVE50 today")))
    }

    @Test
    fun `missing date triggers NO_DATE_REGION`() {
        assertEquals(OcrFallbackReason.NO_DATE_REGION,
            OcrFallbackPredicates.NO_DATE_REGION.evaluate(result("SAVE50 forever")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.NO_DATE_REGION.evaluate(result("valid till 31 Dec 2026")))
    }

    @Test
    fun `low confidence fires`() {
        assertEquals(OcrFallbackReason.LOW_CONFIDENCE,
            OcrFallbackPredicates.LOW_CONFIDENCE.evaluate(result("any text", conf = 0.3f)))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.LOW_CONFIDENCE.evaluate(result("any text", conf = 0.9f)))
    }
}
