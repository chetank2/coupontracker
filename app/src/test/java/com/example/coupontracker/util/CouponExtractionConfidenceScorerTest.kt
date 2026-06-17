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
            imageUri = null
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
            imageUri = null
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
}
