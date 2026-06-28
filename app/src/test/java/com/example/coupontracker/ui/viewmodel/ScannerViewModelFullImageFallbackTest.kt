package com.example.coupontracker.ui.viewmodel

import android.graphics.Bitmap
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.decideFullImageFallback
import com.example.coupontracker.extraction.capture.shouldBlockFullImageFallback
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.util.MultiEngineOCR
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScannerViewModelFullImageFallbackTest {

    private val probe = FullImageFallbackProbe()

    @Test
    fun `full image fallback probe uses OCR text from extracted info when OCR text is blank`() = runTest {
        val result = probe.evaluate(testBitmap()) {
            MultiEngineOCR.OCRResult.Success(
                text = "",
                extractedInfo = mapOf(
                    "store" to "Big Store",
                    "description" to "Flat 20% off",
                    "code" to "Code SAVE20"
                )
            )
        }

        assertEquals("Big Store\nFlat 20% off\nCode SAVE20", result.rawOcrText)
        assertTrue(result.decision.allowDirectOcr)
        assertEquals("likely_single_coupon", result.decision.reason)
    }

    @Test
    fun `full image fallback probe routes OCR errors to blank review decision`() = runTest {
        val result = probe.evaluate(testBitmap()) {
            MultiEngineOCR.OCRResult.Error("OCR failed")
        }

        assertEquals("", result.rawOcrText)
        assertEquals("OCR failed", result.ocrErrorMessage)
        assertFalse(result.decision.allowDirectOcr)
        assertEquals("blank_ocr_classification", result.decision.reason)
    }

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

    private fun testBitmap(): Bitmap {
        return Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
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
