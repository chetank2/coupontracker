package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CouponExtractionConfidenceScorerTest {

    @Test
    fun `score recommends saving high confidence OCR result directly`() {
        val ocr = """
            Lenskart
            you won Lenskart Gold Max membership at just ₹49
            code: AFFLCRDG-OKTYZX6-6TOZ
            EXPIRES IN 29 DAYS
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Lenskart",
            description = "you won Lenskart Gold Max membership at just ₹49",
            expiryDate = Date(),
            redeemCode = "AFFLCRDG-OKTYZX6-6TOZ",
            imageUri = null,
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.PRESENT,
            layoutState = Coupon.LayoutState.COMPLETE
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, ocr)

        assertEquals(ExtractionConfidenceBand.HIGH, result.band)
        assertEquals(ExtractionRecommendation.SAVE_DIRECTLY, result.recommendation)
        assertTrue(result.score >= 90)
    }

    @Test
    fun `score recommends vision verification for wallet OCR with multiple codes`() {
        val ocr = """
            vouchers
            active : 25 lifetime : 279
            code: PBXWOF110K
            EXPIRES IN 29 DAYS
            you won Lenskart Gold Max membership at just ₹49
            Lenskart
            code: AFFLCRDG-OKTYZX6-6TOZ
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Lenskart",
            description = "you won Lenskart Gold Max membership at just ₹49",
            expiryDate = Date(),
            redeemCode = "AFFLCRDG-OKTYZX6-6TOZ",
            imageUri = null,
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.PRESENT,
            layoutState = Coupon.LayoutState.MULTI_CARD
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, ocr)

        assertEquals(ExtractionRecommendation.VERIFY_WITH_VISION, result.recommendation)
        assertTrue(result.issues.contains("multiple_coupon_codes_in_ocr"))
        assertTrue(result.issues.contains("multi_coupon_wallet_screen"))
    }

    @Test
    fun `score recommends manual review when core fields are missing`() {
        val coupon = Coupon(
            storeName = Coupon.Defaults.UNKNOWN_STORE,
            description = "",
            expiryDate = null,
            redeemCode = null,
            imageUri = null
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, "")

        assertEquals(ExtractionConfidenceBand.LOW, result.band)
        assertEquals(ExtractionRecommendation.MANUAL_REVIEW, result.recommendation)
    }

    @Test
    fun `score saves no-code modal when state explains missing code and expiry`() {
        val ocr = """
            IDFC FIRST Bank
            Monthly Interest
            No code needed
        """.trimIndent()
        val coupon = Coupon(
            storeName = "IDFC FIRST Bank",
            description = "Monthly Interest",
            expiryDate = null,
            redeemCode = null,
            imageUri = null,
            codeState = Coupon.CodeState.NO_CODE_NEEDED,
            expiryState = Coupon.ExpiryState.NOT_VISIBLE,
            layoutState = Coupon.LayoutState.MODAL_FOREGROUND
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, ocr)

        assertEquals(ExtractionConfidenceBand.HIGH, result.band)
        assertEquals(ExtractionRecommendation.SAVE_DIRECTLY, result.recommendation)
        assertEquals(1f, result.fieldConfidences["redeemCode"])
        assertEquals(1f, result.fieldConfidences["expiryDate"])
    }

    @Test
    fun `score accepts not visible expiry without punishing missing date`() {
        val ocr = """
            Minimalist
            Flat ₹100 Off + ₹50 Cashback
            code: MNPPRK100UAPR255QYSG7A
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Minimalist",
            description = "Flat ₹100 Off + ₹50 Cashback",
            expiryDate = null,
            redeemCode = "MNPPRK100UAPR255QYSG7A",
            imageUri = null,
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.NOT_VISIBLE,
            layoutState = Coupon.LayoutState.COMPLETE
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, ocr)

        assertEquals(ExtractionRecommendation.SAVE_DIRECTLY, result.recommendation)
        assertEquals(1f, result.fieldConfidences["expiryDate"])
    }

    @Test
    fun `score never saves partial layout directly`() {
        val coupon = highConfidenceCoupon(layoutState = Coupon.LayoutState.PARTIAL)
        val result = CouponExtractionConfidenceScorer.score(coupon, highConfidenceOcr)

        assertEquals(ExtractionRecommendation.VERIFY_WITH_VISION, result.recommendation)
        assertTrue(result.issues.contains("layout_partial"))
    }

    @Test
    fun `score sends low confidence layout to review even with strong text`() {
        val coupon = highConfidenceCoupon(layoutState = Coupon.LayoutState.LOW_CONFIDENCE)
        val result = CouponExtractionConfidenceScorer.score(coupon, highConfidenceOcr)

        assertEquals(ExtractionRecommendation.VERIFY_WITH_VISION, result.recommendation)
        assertTrue(result.issues.contains("layout_low_confidence"))
        assertTrue(result.score < 90)
    }

    @Test
    fun `score caps Leaf weak low-confidence extraction below high confidence`() {
        val ocr = """
            Leaf
            you won 16099 off on Leaf Halo Smart Ring
            code: CREDJP70
            Expires in 13 days
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Leaf",
            description = "you won 16099 off on Leaf Halo Smart Ring",
            expiryDate = Date(),
            redeemCode = "CREDJP70",
            imageUri = null,
            needsAttention = true,
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.PRESENT,
            layoutState = Coupon.LayoutState.LOW_CONFIDENCE
        )

        val result = CouponExtractionConfidenceScorer.score(coupon, ocr)

        assertEquals(ExtractionRecommendation.VERIFY_WITH_VISION, result.recommendation)
        assertTrue(result.score < 90)
        assertTrue(result.issues.contains("layout_low_confidence"))
        assertTrue(result.issues.contains("description_needs_attention"))
    }

    @Test
    fun `score rejects hallucinated present code without OCR support`() {
        val coupon = highConfidenceCoupon(redeemCode = "SCRATCH999")
        val result = CouponExtractionConfidenceScorer.score(coupon, highConfidenceOcr)

        assertEquals(ExtractionRecommendation.VERIFY_WITH_VISION, result.recommendation)
        assertTrue(result.issues.contains("unsupported_coupon_code"))
        assertTrue(result.issues.contains("ocr_contradiction"))
        assertTrue(result.fieldConfidences["redeemCode"]!! < 0.5f)
    }

    private val highConfidenceOcr = """
        Lenskart
        you won Lenskart Gold Max membership at just ₹49
        code: AFFLCRDG-OKTYZX6-6TOZ
        EXPIRES IN 29 DAYS
    """.trimIndent()

    private fun highConfidenceCoupon(
        redeemCode: String = "AFFLCRDG-OKTYZX6-6TOZ",
        layoutState: String = Coupon.LayoutState.COMPLETE
    ): Coupon {
        return Coupon(
            storeName = "Lenskart",
            description = "you won Lenskart Gold Max membership at just ₹49",
            expiryDate = Date(),
            redeemCode = redeemCode,
            imageUri = null,
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.PRESENT,
            layoutState = layoutState
        )
    }
}
