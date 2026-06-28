package com.example.coupontracker.ui.viewmodel

import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.decideFullImageFallback
import com.example.coupontracker.extraction.capture.shouldBlockFullImageFallback
import com.example.coupontracker.ml.ScreenshotClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerViewModelFullImageFallbackTest {

    @Test
    fun `full image fallback allows direct ocr for likely single coupon`() {
        val decision = decideFullImageFallback(
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
        val decision = decideFullImageFallback(
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
        val decision = decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT),
            rawOcrText = "Big Store\nCode: SAVE20\nCopy code",
            detectedRegionCount = 2
        )

        assertFalse(decision.allowDirectOcr)
        assertEquals("multiple_regions_detected", decision.reason)
    }

    @Test
    fun `full image fallback blocks blank classifier text`() {
        val decision = decideFullImageFallback(
            classification = classification(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT),
            rawOcrText = ""
        )

        assertFalse(decision.allowDirectOcr)
        assertEquals("blank_ocr_classification", decision.reason)
    }

    @Test
    fun `layout result blocks full image fallback for multi coupon screenshot`() {
        val result = multiCouponResult(
            screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
            totalDetected = 1
        )

        assertTrue(shouldBlockFullImageFallback(result))
    }

    @Test
    fun `layout result blocks full image fallback when multiple cards detected`() {
        val result = multiCouponResult(
            screenshotType = ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT,
            totalDetected = 2
        )

        assertTrue(shouldBlockFullImageFallback(result))
    }

    @Test
    fun `layout result allows fallback for null or single screenshot result`() {
        val result = multiCouponResult(
            screenshotType = ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT,
            totalDetected = 1
        )

        assertFalse(shouldBlockFullImageFallback(null))
        assertFalse(shouldBlockFullImageFallback(result))
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

    private fun multiCouponResult(
        screenshotType: ScreenshotClassifier.ScreenshotType,
        totalDetected: Int
    ): MultiCouponExtractionService.MultiCouponResult {
        return MultiCouponExtractionService.MultiCouponResult(
            coupons = emptyList(),
            screenshotType = screenshotType,
            totalDetected = totalDetected,
            totalExtracted = 0,
            totalFiltered = 0
        )
    }
}
