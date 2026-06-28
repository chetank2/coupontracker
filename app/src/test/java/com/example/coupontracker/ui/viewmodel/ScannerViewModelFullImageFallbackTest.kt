package com.example.coupontracker.ui.viewmodel

import com.example.coupontracker.ml.ScreenshotClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerViewModelFullImageFallbackTest {

    @Test
    fun `full image fallback allows direct ocr for likely single coupon`() {
        val decision = ScannerViewModel.decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT),
            rawOcrText = """
                Big Store
                Flat 20% off
                Code: SAVE20
                Copy code
                Terms and conditions apply
            """.trimIndent()
        )

        assertTrue(decision.allowDirectOcr)
        assertEquals("likely_single_coupon", decision.reason)
    }

    @Test
    fun `full image fallback blocks classified multi coupon screenshot`() {
        val decision = ScannerViewModel.decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP),
            rawOcrText = """
                Offer one cashback copy code
                Offer two discount apply code
                Offer three sale coupon
            """.trimIndent()
        )

        assertFalse(decision.allowDirectOcr)
        assertEquals("classified_multi_coupon", decision.reason)
    }

    @Test
    fun `full image fallback blocks multiple detected regions even with single classification`() {
        val decision = ScannerViewModel.decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT),
            rawOcrText = "Big Store\nCode: SAVE20\nCopy code",
            detectedRegionCount = 2
        )

        assertFalse(decision.allowDirectOcr)
        assertEquals("multiple_regions_detected", decision.reason)
    }

    @Test
    fun `full image fallback blocks blank classifier text`() {
        val decision = ScannerViewModel.decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT),
            rawOcrText = ""
        )

        assertFalse(decision.allowDirectOcr)
        assertEquals("blank_ocr_classification", decision.reason)
    }

    private fun classification(
        type: ScreenshotClassifier.ScreenshotType
    ): ScreenshotClassifier.ClassificationResult {
        return ScreenshotClassifier.ClassificationResult(
            type = type,
            confidence = 0.9f,
            indicators = emptyMap()
        )
    }
}
