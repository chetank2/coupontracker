package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class VisionOcrMergePolicyTest {
    private val mergePolicy = VisionOcrMergePolicy()

    @Test
    fun `accepts IDFC no-code modal state`() {
        val base = Coupon(
            storeName = Coupon.Defaults.UNKNOWN_STORE,
            description = "scratch rewards background copy",
            redeemCode = "SCRATCH",
            imageUri = null,
            rawOcrText = "IDFC FIRST Bank\nMonthly Interest\nNo code needed"
        )
        val vision = extraction(
            card = VisionCouponCard(
                storeName = "IDFC FIRST Bank",
                description = "Monthly Interest",
                redeemCode = null,
                expiryText = null,
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
                confidence = 0.94f,
                evidence = "Foreground modal says No code needed",
                bounds = null,
                active = true
            )
        )

        val merged = mergePolicy.merge(base, vision, base.rawOcrText)

        assertEquals("IDFC FIRST Bank", merged.storeName)
        assertEquals("Monthly Interest", merged.description)
        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, merged.codeState)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, merged.expiryState)
        assertFalse(merged.needsAttention)
    }

    @Test
    fun `rejects hallucinated VLM codes without OCR evidence`() {
        val base = Coupon(
            storeName = "CRED",
            description = "Save on membership",
            redeemCode = null,
            imageUri = null,
            rawOcrText = "CRED\nSave on membership\nNo code needed"
        )
        val vision = extraction(
            card = VisionCouponCard(
                storeName = "CRED",
                description = "Save on membership",
                redeemCode = "SAVE999",
                expiryText = null,
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                layoutState = Coupon.LayoutState.COMPLETE,
                confidence = 0.8f,
                evidence = "Model guessed a code",
                bounds = null,
                active = true
            )
        )

        val merged = mergePolicy.merge(base, vision, base.rawOcrText)

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
    }

    @Test
    fun `preserves OCR code when VLM incorrectly says no code needed`() {
        val base = Coupon(
            storeName = "Foxtale",
            description = "Buy 2 Get 2 FREE",
            redeemCode = "FOXPPB2G2BIY5WIV8",
            imageUri = null,
            rawOcrText = "Foxtale\nBuy 2 Get 2 FREE\nCode: FOXPPB2G2BIY5WIV8"
        )
        val vision = extraction(
            card = VisionCouponCard(
                storeName = "Foxtale",
                description = "Buy 2 Get 2 FREE",
                redeemCode = null,
                expiryText = null,
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                layoutState = Coupon.LayoutState.COMPLETE,
                confidence = 0.8f,
                evidence = "Model missed the code row",
                bounds = null,
                active = true
            )
        )

        val merged = mergePolicy.merge(base, vision, base.rawOcrText)

        assertEquals("FOXPPB2G2BIY5WIV8", merged.redeemCode)
        assertEquals(Coupon.CodeState.PRESENT, merged.codeState)
    }

    @Test
    fun `filters Apple legal boilerplate from offer description`() {
        val base = Coupon(
            storeName = "AGEasy",
            description = "Extra 20% off",
            redeemCode = null,
            imageUri = null,
            rawOcrText = "AGEasy\nExtra 20% off\nApple or Google is not a sponsor"
        )
        val vision = extraction(
            card = VisionCouponCard(
                storeName = "AGEasy",
                description = "Apple or Google is not a sponsor",
                redeemCode = null,
                expiryText = null,
                codeState = Coupon.CodeState.NOT_VISIBLE,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                layoutState = Coupon.LayoutState.COMPLETE,
                confidence = 0.85f,
                evidence = "Legal text visible",
                bounds = null,
                active = true
            )
        )

        val merged = mergePolicy.merge(base, vision, base.rawOcrText)

        assertEquals("Extra 20% off", merged.description)
    }

    @Test
    fun `marks low confidence unknown vision states for review`() {
        val base = Coupon(
            storeName = "CRED",
            description = "Save 20% on subscription",
            redeemCode = null,
            imageUri = null,
            rawOcrText = "CRED\nSave 20% on subscription"
        )
        val vision = extraction(
            card = VisionCouponCard(
                storeName = "CRED",
                description = "Save 20% on subscription",
                redeemCode = null,
                expiryText = null,
                codeState = Coupon.CodeState.UNKNOWN,
                expiryState = Coupon.ExpiryState.UNKNOWN,
                layoutState = Coupon.LayoutState.LOW_CONFIDENCE,
                confidence = 0.28f,
                evidence = "Fields are unclear",
                bounds = null,
                active = true
            )
        )

        val merged = mergePolicy.merge(base, vision, base.rawOcrText)

        assertTrue(merged.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertEquals("vision_field_state_requires_review", merged.cleanupError)
        assertNotEquals(Coupon.ExtractionSource.VISION_VERIFIED, merged.extractionSource)
    }

    private fun extraction(card: VisionCouponCard): VisionFieldExtraction {
        return VisionFieldExtraction(
            cards = listOf(card),
            activeCard = card,
            confidence = card.confidence,
            rawEvidence = "{}"
        )
    }
}
