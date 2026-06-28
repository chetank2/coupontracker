package com.example.coupontracker.extraction.regression

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.capture.CandidateRegionType
import com.example.coupontracker.extraction.capture.CaptureScreenshotType
import com.example.coupontracker.extraction.capture.CropIsolationInput
import com.example.coupontracker.extraction.capture.CropIsolationMode
import com.example.coupontracker.extraction.capture.CropIsolationPolicy
import com.example.coupontracker.extraction.capture.CropIsolationReason
import com.example.coupontracker.extraction.capture.LayoutSignalSource
import com.example.coupontracker.extraction.capture.ReviewTarget
import com.example.coupontracker.extraction.vision.VisionEvidenceMergePolicy
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionFieldMergeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class ScreenshotExtractionRegressionHarnessTest {
    private val parser = VisionFieldJsonParser()
    private val mergePolicy = VisionEvidenceMergePolicy()
    private val cropPolicy = CropIsolationPolicy()

    @Test
    fun `fixture no-code modal persists explicit absent code and expiry states`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = "Needs review",
                redeemCode = "SCRATCH",
                rawOcrText = """
                    IDFC FIRST Bank
                    Monthly Interest
                    No code needed
                """.trimIndent()
            ),
            visionJson = """
                {
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.94,
                  "fields": {
                    "store": {
                      "state": "PRESENT",
                      "text": "IDFC FIRST Bank",
                      "evidence": ["IDFC FIRST Bank"],
                      "confidence": 0.95
                    },
                    "description": {
                      "state": "PRESENT",
                      "text": "Monthly Interest",
                      "evidence": ["Monthly Interest"],
                      "confidence": 0.93
                    },
                    "code": {
                      "state": "NO_CODE_NEEDED",
                      "text": null,
                      "evidence": ["No code needed"],
                      "confidence": 0.95
                    },
                    "expiry": {
                      "state": "NOT_VISIBLE",
                      "text": null,
                      "evidence": [],
                      "confidence": 0.9
                    }
                  },
                  "noise": ["background scratch card copy"]
                }
            """.trimIndent(),
            mergeInput = cropInput(),
            expected = ExpectedCouponContract(
                storeName = "IDFC FIRST Bank",
                description = "Monthly Interest",
                redeemCode = null,
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
                cleanupStatus = Coupon.CleanupStatus.CLEANED,
                needsAttention = false,
                trustedVision = true
            )
        )

        val merged = runFixture(fixture)

        assertExpected(fixture.expected, merged)
        assertNull(merged.expiryDate)
        assertFalse(merged.debugVisionEvidence.orEmpty().contains("SCRATCH"))
    }

    @Test
    fun `fixture full image multi-card labels are review-safe without targeted crop`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = "Needs review",
                redeemCode = null,
                rawOcrText = """
                    Wallet rewards
                    AJIO
                    Flat 50% off on fashion
                    Code SAVE50
                    EXPIRES IN 7 DAYS
                    Grocery card below
                    BIGBASKET
                    Flat 150 off
                """.trimIndent()
            ),
            visionJson = """
                {
                  "cards": [
                    {
                      "storeName": "AJIO",
                      "description": "Flat 50% off on fashion",
                      "redeemCode": "SAVE50",
                      "expiryText": "EXPIRES IN 7 DAYS",
                      "codeState": "PRESENT",
                      "expiryState": "PRESENT",
                      "layoutState": "MULTI_CARD",
                      "confidence": 0.92,
                      "evidence": "store: AJIO; offer: Flat 50% off on fashion; code: SAVE50; expiry: EXPIRES IN 7 DAYS",
                      "active": true
                    }
                  ],
                  "confidence": 0.92
                }
            """.trimIndent(),
            mergeInput = fullImageInput(),
            expected = ExpectedCouponContract(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = "Needs review",
                redeemCode = null,
                codeState = Coupon.CodeState.UNKNOWN,
                expiryState = Coupon.ExpiryState.UNKNOWN,
                layoutState = Coupon.LayoutState.MULTI_CARD,
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                needsAttention = true,
                trustedVision = false
            )
        )

        val merged = runFixture(fixture)

        assertExpected(fixture.expected, merged)
        assertNotEquals(Coupon.ExtractionSource.VISION_VERIFIED, merged.extractionSource)
        assertTrue(merged.debugVisionEvidence.orEmpty().contains("\"source\":\"full_image\""))
        assertFalse(merged.debugVisionEvidence.orEmpty().contains("\"pixelCrop\""))
        assertEquals("Vision verification needs review", merged.cleanupError)
    }

    @Test
    fun `fixture full image multi-card input routes to review before field extraction`() {
        val decision = cropPolicy.decide(
            CropIsolationInput(
                detectedRegionCount = 0,
                candidateRegionType = CandidateRegionType.FULL_IMAGE_FALLBACK,
                screenshotType = CaptureScreenshotType.MULTI_COUPON_APP,
                rawOcrText = """
                    AJIO
                    Flat 50% off on fashion
                    Code SAVE50
                    BIGBASKET
                    Flat 150 off
                """.trimIndent(),
                likelySingleCoupon = false,
                layoutConfidence = 0.92f,
                layoutSource = LayoutSignalSource.VLM
            )
        )

        assertEquals(CropIsolationMode.REVIEW_ONLY, decision.mode)
        assertEquals(CropIsolationReason.CLASSIFIED_MULTI_COUPON, decision.reason)
        assertEquals(ReviewTarget.MULTI_SELECTION, decision.reviewTarget)
        assertFalse(decision.provisional)
    }

    private fun runFixture(fixture: RegressionFixture): Coupon {
        val vision = parser.parse(fixture.visionJson)
        return mergePolicy.mergeFieldLabels(
            current = fixture.base,
            vision = vision,
            rawOcr = fixture.base.rawOcrText,
            visionInput = fixture.mergeInput,
            captureTimestamp = captureDate
        )
    }

    private fun assertExpected(expected: ExpectedCouponContract, actual: Coupon) {
        assertEquals(expected.storeName, actual.storeName)
        assertEquals(expected.description, actual.description)
        assertEquals(expected.redeemCode, actual.redeemCode)
        assertEquals(expected.codeState, actual.codeState)
        assertEquals(expected.expiryState, actual.expiryState)
        assertEquals(expected.layoutState, actual.layoutState)
        assertEquals(expected.cleanupStatus, actual.cleanupStatus)
        assertEquals(expected.needsAttention, actual.needsAttention)
        if (expected.trustedVision) {
            assertEquals(Coupon.ExtractionSource.VISION_VERIFIED, actual.extractionSource)
        } else {
            assertNotEquals(Coupon.ExtractionSource.VISION_VERIFIED, actual.extractionSource)
        }
    }

    private fun coupon(
        storeName: String,
        description: String,
        redeemCode: String?,
        rawOcrText: String?
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            imageUri = null,
            rawOcrText = rawOcrText,
            extractionTimestamp = captureDate
        )
    }

    private fun cropInput(): VisionFieldMergeInput {
        return VisionFieldMergeInput(
            usedTargetedCrop = true,
            source = "layout",
            normalizedBoundsJson = null,
            pixelCrop = android.graphics.Rect(12, 20, 312, 220),
            layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
            debugEvidence = null
        )
    }

    private fun fullImageInput(): VisionFieldMergeInput {
        return VisionFieldMergeInput(
            usedTargetedCrop = false,
            source = "full_image",
            normalizedBoundsJson = null,
            pixelCrop = null,
            layoutState = Coupon.LayoutState.MULTI_CARD,
            debugEvidence = null
        )
    }

    private data class RegressionFixture(
        val base: Coupon,
        val visionJson: String,
        val mergeInput: VisionFieldMergeInput,
        val expected: ExpectedCouponContract
    )

    private data class ExpectedCouponContract(
        val storeName: String,
        val description: String,
        val redeemCode: String?,
        val codeState: String,
        val expiryState: String,
        val layoutState: String,
        val cleanupStatus: String,
        val needsAttention: Boolean,
        val trustedVision: Boolean
    )

    private companion object {
        private val captureDate: Date = Date.from(
            LocalDate.of(2026, 6, 28)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
        )
    }
}
