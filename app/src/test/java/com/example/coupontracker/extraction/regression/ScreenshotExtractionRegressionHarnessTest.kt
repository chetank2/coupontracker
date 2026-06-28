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
    fun `fixture MakeMyTrip crop code wins over hallucinated no-code state`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = "MakeMyTrip",
                description = "Flat 15% off on domestic flights",
                redeemCode = "MMTFLY",
                rawOcrText = """
                    MakeMyTrip
                    Flat 15% off on domestic flights
                    Use code MMTFLY
                    Valid till 30 Jun 2026
                """.trimIndent()
            ),
            visionJson = """
                {
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.91,
                  "fields": {
                    "store": {
                      "state": "PRESENT",
                      "text": "MakeMyTrip",
                      "evidence": ["MakeMyTrip"],
                      "confidence": 0.94
                    },
                    "description": {
                      "state": "PRESENT",
                      "text": "Flat 15% off on domestic flights",
                      "evidence": ["Flat 15% off on domestic flights"],
                      "confidence": 0.9
                    },
                    "code": {
                      "state": "NO_CODE_NEEDED",
                      "text": null,
                      "evidence": ["No code needed"],
                      "confidence": 0.72
                    },
                    "expiry": {
                      "state": "PRESENT",
                      "text": "Valid till 30 Jun 2026",
                      "evidence": ["Valid till 30 Jun 2026"],
                      "confidence": 0.88
                    }
                  }
                }
            """.trimIndent(),
            mergeInput = cropInput(),
            expected = ExpectedCouponContract(
                storeName = "MakeMyTrip",
                description = "Flat 15% off on domestic flights",
                redeemCode = "MMTFLY",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.PRESENT,
                layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
                cleanupStatus = Coupon.CleanupStatus.CLEANED,
                needsAttention = false,
                trustedVision = true
            )
        )

        val merged = runFixture(fixture)

        assertExpected(fixture.expected, merged)
        assertEquals(LocalDate.of(2026, 6, 30), merged.expiryDate!!.toLocalDate())
        assertFalse(merged.debugVisionEvidence.orEmpty().contains("BACKGROUND123"))
    }

    @Test
    fun `fixture BigBasket crop expiry wins over previous card expiry conflict`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = "Bigbasket",
                description = "you won flat 150 off on orders above 400 on Bigbasket",
                redeemCode = "BBNOWCRED3-GZGEZF7BAHEXFY",
                expiryDate = localDate(2025, 5, 8),
                extractionTimestamp = localDate(2025, 5, 2),
                rawOcrText = """
                    vouchers
                    Beardo
                    O EXPIRES IN 06 DAYS
                    you won flat 150 off on orders above 400 on Bigbasket
                    code: BBNOWCRED3-GZGEZF7BAHEXFY
                    O EXPIRES IN 39 DAYS
                """.trimIndent()
            ),
            visionJson = """
                {
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.94,
                  "fields": {
                    "store": {
                      "state": "PRESENT",
                      "text": "Bigbasket",
                      "evidence": ["Bigbasket"],
                      "confidence": 0.95
                    },
                    "description": {
                      "state": "PRESENT",
                      "text": "you won flat 150 off on orders above 400 on Bigbasket",
                      "evidence": ["you won flat 150 off on orders above 400 on Bigbasket"],
                      "confidence": 0.92
                    },
                    "code": {
                      "state": "PRESENT",
                      "text": "BBNOWCRED3-GZGEZF7BAHEXFY",
                      "evidence": ["BBNOWCRED3-GZGEZF7BAHEXFY"],
                      "confidence": 0.93
                    },
                    "expiry": {
                      "state": "PRESENT",
                      "text": "EXPIRES IN 39 DAYS",
                      "evidence": ["O EXPIRES IN 39 DAYS"],
                      "confidence": 0.91
                    }
                  },
                  "noise": ["Beardo", "O EXPIRES IN 06 DAYS"]
                }
            """.trimIndent(),
            mergeInput = cropInput(),
            captureTimestamp = localDate(2025, 5, 2),
            expected = ExpectedCouponContract(
                storeName = "Bigbasket",
                description = "you won flat 150 off on orders above 400 on Bigbasket",
                redeemCode = "BBNOWCRED3-GZGEZF7BAHEXFY",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.PRESENT,
                layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
                cleanupStatus = Coupon.CleanupStatus.CLEANED,
                needsAttention = false,
                trustedVision = true
            )
        )

        val merged = runFixture(fixture)

        assertExpected(fixture.expected, merged)
        assertEquals(LocalDate.of(2025, 6, 10), merged.expiryDate!!.toLocalDate())
    }

    @Test
    fun `fixture Lenskart no-code modal persists explicit absence without a model code`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = "Needs review",
                redeemCode = null,
                rawOcrText = """
                    Lenskart
                    Free Gold membership upgrade
                    No code needed
                    Tap to claim
                """.trimIndent()
            ),
            visionJson = """
                {
                  "layoutState": "MODAL_FOREGROUND",
                  "confidence": 0.93,
                  "fields": {
                    "store": {
                      "state": "PRESENT",
                      "text": "Lenskart",
                      "evidence": ["Lenskart"],
                      "confidence": 0.94
                    },
                    "description": {
                      "state": "PRESENT",
                      "text": "Free Gold membership upgrade",
                      "evidence": ["Free Gold membership upgrade"],
                      "confidence": 0.9
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
                      "evidence": ["No expiry shown"],
                      "confidence": 0.86
                    }
                  }
                }
            """.trimIndent(),
            mergeInput = cropInput(),
            expected = ExpectedCouponContract(
                storeName = "Lenskart",
                description = "Free Gold membership upgrade",
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
    }

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
    fun `fixture full-image MakeMyTrip evidence is rejected without crop authority`() {
        val fixture = RegressionFixture(
            base = coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = "Needs review",
                redeemCode = null,
                rawOcrText = """
                    Wallet rewards
                    MakeMyTrip
                    Flat 15% off on domestic flights
                    Use code MMTFLY
                    Grocery card below
                    Bigbasket
                    EXPIRES IN 39 DAYS
                """.trimIndent()
            ),
            visionJson = """
                {
                  "layoutState": "MULTI_CARD",
                  "confidence": 0.9,
                  "fields": {
                    "store": {
                      "state": "PRESENT",
                      "text": "MakeMyTrip",
                      "evidence": ["MakeMyTrip"],
                      "confidence": 0.91
                    },
                    "description": {
                      "state": "PRESENT",
                      "text": "Flat 15% off on domestic flights",
                      "evidence": ["Flat 15% off on domestic flights"],
                      "confidence": 0.9
                    },
                    "code": {
                      "state": "PRESENT",
                      "text": "MMTFLY",
                      "evidence": ["Use code MMTFLY"],
                      "confidence": 0.9
                    },
                    "expiry": {
                      "state": "PRESENT",
                      "text": "EXPIRES IN 39 DAYS",
                      "evidence": ["EXPIRES IN 39 DAYS"],
                      "confidence": 0.86
                    }
                  },
                  "noise": ["Bigbasket", "Grocery card below"]
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
        assertNull(merged.expiryDate)
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
            captureTimestamp = fixture.captureTimestamp
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
        expiryDate: Date? = null,
        extractionTimestamp: Date? = captureDate,
        rawOcrText: String?
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            expiryDate = expiryDate,
            imageUri = null,
            rawOcrText = rawOcrText,
            extractionTimestamp = extractionTimestamp
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
        val captureTimestamp: Date = captureDate,
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

        private fun localDate(year: Int, month: Int, day: Int): Date {
            return Date.from(
                LocalDate.of(year, month, day)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            )
        }
    }

    private fun Date.toLocalDate(): LocalDate {
        return toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
