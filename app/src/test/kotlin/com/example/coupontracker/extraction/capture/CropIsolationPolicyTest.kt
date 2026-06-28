package com.example.coupontracker.extraction.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropIsolationPolicyTest {

    private val policy = CropIsolationPolicy()

    @Test
    fun `isolated crop is allowed when exactly one crop region is available`() {
        val decision = policy.decide(
            input(
                detectedRegionCount = 1,
                candidateRegionType = CandidateRegionType.ISOLATED_CROP
            )
        )

        assertEquals(CropIsolationMode.ISOLATED_CROP, decision.mode)
        assertEquals(CropIsolationReason.ISOLATED_CROP_AVAILABLE, decision.reason)
        assertEquals(ReviewTarget.NONE, decision.reviewTarget)
        assertFalse(decision.provisional)
    }

    @Test
    fun `multiple regions go to review multi selection`() {
        val decision = policy.decide(
            input(
                detectedRegionCount = 3,
                candidateRegionType = CandidateRegionType.ISOLATED_CROP,
                screenshotType = CaptureScreenshotType.MULTI_COUPON_APP
            )
        )

        assertEquals(CropIsolationMode.REVIEW_ONLY, decision.mode)
        assertEquals(CropIsolationReason.MULTIPLE_REGIONS_DETECTED, decision.reason)
        assertEquals(ReviewTarget.MULTI_SELECTION, decision.reviewTarget)
    }

    @Test
    fun `classified multi coupon screenshot is review only without isolated crop`() {
        val decision = policy.decide(
            input(
                detectedRegionCount = 0,
                candidateRegionType = CandidateRegionType.FULL_IMAGE_FALLBACK,
                screenshotType = CaptureScreenshotType.MULTI_COUPON_APP,
                likelySingleCoupon = false
            )
        )

        assertEquals(CropIsolationMode.REVIEW_ONLY, decision.mode)
        assertEquals(CropIsolationReason.CLASSIFIED_MULTI_COUPON, decision.reason)
        assertEquals(ReviewTarget.MULTI_SELECTION, decision.reviewTarget)
    }

    @Test
    fun `blank ocr is review only`() {
        val decision = policy.decide(
            input(
                detectedRegionCount = 1,
                candidateRegionType = CandidateRegionType.ISOLATED_CROP,
                rawOcrText = "   "
            )
        )

        assertEquals(CropIsolationMode.REVIEW_ONLY, decision.mode)
        assertEquals(CropIsolationReason.BLANK_OCR, decision.reason)
        assertEquals(ReviewTarget.SINGLE_SCAN, decision.reviewTarget)
    }

    @Test
    fun `likely single full image fallback is allowed as provisional guarded ocr`() {
        val decision = policy.decide(
            input(
                detectedRegionCount = 0,
                candidateRegionType = CandidateRegionType.FULL_IMAGE_FALLBACK,
                likelySingleCoupon = true
            )
        )

        assertEquals(CropIsolationMode.GUARDED_FULL_IMAGE_OCR, decision.mode)
        assertEquals(CropIsolationReason.LIKELY_SINGLE_FULL_IMAGE_FALLBACK, decision.reason)
        assertEquals(ReviewTarget.NONE, decision.reviewTarget)
        assertTrue(decision.provisional)
    }

    private fun input(
        detectedRegionCount: Int,
        candidateRegionType: CandidateRegionType,
        screenshotType: CaptureScreenshotType = CaptureScreenshotType.SINGLE_SCREENSHOT,
        rawOcrText: String = """
            Big Store
            Flat 20% off
            Code: SAVE20
        """.trimIndent(),
        likelySingleCoupon: Boolean = true
    ): CropIsolationInput {
        return CropIsolationInput(
            detectedRegionCount = detectedRegionCount,
            candidateRegionType = candidateRegionType,
            screenshotType = screenshotType,
            rawOcrText = rawOcrText,
            likelySingleCoupon = likelySingleCoupon,
            layoutConfidence = 0.8f,
            layoutSource = LayoutSignalSource.HEURISTIC
        )
    }
}
