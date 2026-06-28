package com.example.coupontracker.domain.usecase

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.ExtractionValidator
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.FullImageFallbackDecision
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.FullImageFallbackProbeResult
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.OcrFirstExtractionResult
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.CouponStatus
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.util.MultiEngineOCR
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ExtractCouponUseCaseSingleScanRouteTest {

    private val extractor = mockk<OcrFirstCouponExtractor>()
    private val multiCouponExtractionService = mockk<MultiCouponExtractionService>()
    private val fallbackProbe = mockk<FullImageFallbackProbe>()
    private val useCase = ExtractCouponUseCase(
        extractor = extractor,
        multiCouponExtractionService = multiCouponExtractionService,
        routingUseCase = SingleScanRoutingUseCase(),
        fullImageFallbackProbe = fallbackProbe
    )

    @Test
    fun `single detected crop routes to crop processing without layout or full image ocr`() = runTest {
        val crop = couponInstance()

        val outcome = useCase.routeSingleScan(
            request(
                detectorAvailable = true,
                detectCouponCrops = { listOf(crop) }
            )
        )

        assertTrue(outcome is SingleScanExtractionOutcome.ProcessSingleCrop)
        assertSame(crop, (outcome as SingleScanExtractionOutcome.ProcessSingleCrop).couponInstance)
        assertEquals(
            listOf(SingleScanRouteEvent("ocr_first_card_crop", "single_coupon_crop_detected")),
            outcome.events
        )
        coVerify(exactly = 0) {
            multiCouponExtractionService.extractMultipleCoupons(any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            extractor.extract(any(), any(), any())
        }
    }

    @Test
    fun `zero detector crops returns layout coupons before guarded full image fallback`() = runTest {
        val timestamp = Date(1_735_689_600_000L)
        val layoutResult = multiCouponResult(
            coupons = listOf(
                MultiCouponExtractionService.CouponWithConfidence(
                    coupon = Coupon(
                        storeName = "Store",
                        description = "Offer",
                        redeemCode = null,
                        imageUri = "content://coupon"
                    ),
                    confidence = 0.8f,
                    extractionQuality = ExtractionValidator.ExtractionQuality.GOOD,
                    warnings = emptyList()
                )
            ),
            totalDetected = 1
        )
        coEvery {
            multiCouponExtractionService.extractMultipleCoupons(
                bitmap = any(),
                imageUri = "content://coupon",
                captureTimestamp = timestamp,
                allowProgressiveFallback = false
            )
        } returns layoutResult

        val outcome = useCase.routeSingleScan(
            request(
                timestamp = timestamp,
                detectorAvailable = true,
                detectCouponCrops = { emptyList() }
            )
        )

        assertTrue(outcome is SingleScanExtractionOutcome.LayoutCoupons)
        assertSame(layoutResult, (outcome as SingleScanExtractionOutcome.LayoutCoupons).multiResult)
        assertEquals(
            listOf(
                SingleScanRouteEvent("layout_multi_coupon_probe", "no_coupon_crop_detected"),
                SingleScanRouteEvent("layout_single_coupon_extraction", "no_coupon_crop_detected_layout_detected_1")
            ),
            outcome.events
        )
        coVerify(exactly = 0) {
            fallbackProbe.evaluate(any(), any())
        }
    }

    @Test
    fun `blocked layout result returns review fallback without full image ocr`() = runTest {
        coEvery {
            multiCouponExtractionService.extractMultipleCoupons(any(), any(), any(), any())
        } returns multiCouponResult(
            coupons = emptyList(),
            screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
            totalDetected = 1
        )

        val outcome = useCase.routeSingleScan(
            request(
                detectorAvailable = false,
                detectCouponCrops = { error("Detector should not run when unavailable") }
            )
        )

        assertTrue(outcome is SingleScanExtractionOutcome.FullImageReviewFallback)
        outcome as SingleScanExtractionOutcome.FullImageReviewFallback
        assertEquals("", outcome.rawOcrText)
        assertEquals(
            "coupon_detector_unavailable_layout_MULTI_COUPON_APP_detected_1",
            outcome.reason
        )
        coVerify(exactly = 0) {
            fallbackProbe.evaluate(any(), any())
        }
        coVerify(exactly = 0) {
            extractor.extract(any(), any(), any())
        }
    }

    @Test
    fun `guarded fallback direct ocr routes through extractor after layout has no candidates`() = runTest {
        val expected = OcrFirstExtractionResult(
            coupon = Coupon(
                storeName = "Store",
                description = "Offer",
                redeemCode = null,
                imageUri = "content://coupon"
            ),
            rawOcrText = "Store\nOffer",
            confidence = 0.9f,
            success = true,
            failureReason = null
        )
        coEvery {
            multiCouponExtractionService.extractMultipleCoupons(any(), any(), any(), any())
        } returns multiCouponResult(coupons = emptyList(), totalDetected = 0)
        coEvery {
            fallbackProbe.evaluate(any(), any())
        } returns FullImageFallbackProbeResult(
            rawOcrText = "Store\nOffer",
            decision = FullImageFallbackDecision(
                allowDirectOcr = true,
                reason = "likely_single_coupon"
            )
        )
        coEvery {
            extractor.extract(
                bitmap = any(),
                imageUri = "content://coupon",
                captureTimestamp = null
            )
        } returns expected

        val outcome = useCase.routeSingleScan(
            request(
                detectorAvailable = true,
                detectCouponCrops = { emptyList() },
                processFullImageOcr = { MultiEngineOCR.OCRResult.Success("Store\nOffer", emptyMap()) }
            )
        )

        assertTrue(outcome is SingleScanExtractionOutcome.FullImageOcr)
        assertSame(expected, (outcome as SingleScanExtractionOutcome.FullImageOcr).extraction)
        assertEquals(
            listOf(
                SingleScanRouteEvent("layout_multi_coupon_probe", "no_coupon_crop_detected"),
                SingleScanRouteEvent("ocr_first_manual_clean", "no_coupon_crop_detected_layout_no_candidates"),
                SingleScanRouteEvent("ocr_first_full_image_guarded", "no_coupon_crop_detected_likely_single_coupon")
            ),
            outcome.events
        )
        coVerify {
            extractor.extract(
                bitmap = any(),
                imageUri = "content://coupon",
                captureTimestamp = null
            )
        }
    }

    private fun request(
        timestamp: Date? = null,
        detectorAvailable: Boolean,
        detectCouponCrops: suspend (Bitmap) -> List<CouponInstance>,
        processFullImageOcr: suspend (Bitmap) -> MultiEngineOCR.OCRResult = {
            MultiEngineOCR.OCRResult.Error("should not run")
        }
    ): SingleScanExtractionRequest = SingleScanExtractionRequest(
        bitmap = mockk(relaxed = true),
        imageUri = "content://coupon",
        captureTimestamp = timestamp,
        detectorAvailable = detectorAvailable,
        detectCouponCrops = detectCouponCrops,
        processFullImageOcr = processFullImageOcr
    )

    private fun couponInstance(): CouponInstance = CouponInstance(
        id = "coupon-1",
        boundingBox = RectF(0f, 0f, 100f, 100f),
        status = CouponStatus.COMPLETE,
        confidence = 0.95f,
        fields = emptyList(),
        cropBitmap = mockk(relaxed = true)
    )

    private fun multiCouponResult(
        coupons: List<MultiCouponExtractionService.CouponWithConfidence>,
        screenshotType: ScreenshotClassifier.ScreenshotType = ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT,
        totalDetected: Int
    ): MultiCouponExtractionService.MultiCouponResult {
        return MultiCouponExtractionService.MultiCouponResult(
            coupons = coupons,
            screenshotType = screenshotType,
            totalDetected = totalDetected,
            totalExtracted = coupons.size,
            totalFiltered = 0
        )
    }
}
