package com.example.coupontracker.extraction.vision

import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class VisionEvidenceMergePolicyTest {
    private val policy = VisionEvidenceMergePolicy()

    @Test
    fun `hallucinated code is rejected without exact OCR and evidence support`() {
        val base = coupon(
            storeName = "AJIO",
            description = "Flat 50% off on fashion",
            redeemCode = null,
            rawOcrText = """
                AJIO
                Flat 50% off on fashion
                Details
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "AJIO",
                description = "Flat 50% off on fashion",
                redeemCode = "SAVE50",
                codeState = Coupon.CodeState.PRESENT,
                evidence = """
                    store: AJIO
                    offer: Flat 50% off on fashion
                """.trimIndent()
            )
        )

        val merged = policy.merge(base, vision, base.rawOcrText)

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertTrue(merged.needsAttention)
    }

    @Test
    fun `blank crop OCR cannot prove current or vision code`() {
        val base = coupon(
            storeName = "MakeMyTrip",
            description = "Flat 15% off",
            redeemCode = "BACKGROUND123",
            rawOcrText = ""
        )
        val vision = extraction(
            card = card(
                storeName = "MakeMyTrip",
                description = "Flat 15% off",
                redeemCode = "BACKGROUND123",
                codeState = Coupon.CodeState.PRESENT,
                evidence = """
                    store: MakeMyTrip
                    offer: Flat 15% off
                    code: BACKGROUND123
                """.trimIndent()
            )
        )

        val merged = policy.merge(base, vision, rawOcrText = "")

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertTrue(merged.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
    }

    @Test
    fun `BigBasket previous 06 days and active 39 days resolves to crop evidence expiry`() {
        val captureDate = localDate(2025, 5, 2)
        val base = coupon(
            storeName = "Bigbasket",
            description = "you won flat 150 off on orders above 400 on Bigbasket",
            redeemCode = "BBNOWCRED3-GZGEZF7BAHEXFY",
            expiryDate = localDate(2025, 5, 8),
            extractionTimestamp = captureDate,
            rawOcrText = """
                vouchers
                Beardo
                O EXPIRES IN 06 DAYS
                you won flat 150 off on orders above 400 on Bigbasket
                code: BBNOWCRED3-GZGEZF7BAHEXFY
                O EXPIRES IN 39 DAYS
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "Bigbasket",
                description = "you won flat 150 off on orders above 400 on Bigbasket",
                redeemCode = "BBNOWCRED3-GZGEZF7BAHEXFY",
                expiryText = "EXPIRES IN 39 DAYS",
                evidence = """
                    store: Bigbasket
                    offer: you won flat 150 off on orders above 400 on Bigbasket
                    code: BBNOWCRED3-GZGEZF7BAHEXFY
                    previous: O EXPIRES IN 06 DAYS
                    expiry: O EXPIRES IN 39 DAYS
                """.trimIndent()
            )
        )

        val merged = policy.merge(base, vision, base.rawOcrText)

        assertEquals(LocalDate.of(2025, 6, 10), merged.expiryDate!!.toLocalDate())
        assertEquals(Coupon.ExpiryState.PRESENT, merged.expiryState)
    }

    @Test
    fun `IDFC no-code persists state`() {
        val base = coupon(
            storeName = "IDFC FIRST Bank",
            description = "No cost EMI on credit card",
            redeemCode = null,
            rawOcrText = """
                IDFC FIRST Bank
                No cost EMI on credit card
                No coupon code required
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "IDFC FIRST Bank",
                description = "No cost EMI on credit card",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = """
                    store: IDFC FIRST Bank
                    offer: No cost EMI on credit card
                    codeState: NO_CODE_NEEDED
                    expiryState: NOT_VISIBLE
                """.trimIndent()
            )
        )

        val merged = policy.merge(base, vision, base.rawOcrText)

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, merged.codeState)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, merged.expiryState)
        assertEquals(false, merged.needsAttention)
    }

    @Test
    fun `noise evidence is not saved as coupon data`() {
        val base = coupon(
            storeName = "Needs review",
            description = "Needs review",
            redeemCode = null,
            rawOcrText = """
                PreviousStore
                code: OLD999
                Details Redeem Now
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "PreviousStore",
                description = "Details Redeem Now",
                redeemCode = "OLD999",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = """
                    noise: PreviousStore
                    noise: code: OLD999
                    noise: Details Redeem Now
                """.trimIndent()
            )
        )

        val merged = policy.merge(base, vision, base.rawOcrText)

        assertEquals("Needs review", merged.description)
        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertTrue(merged.needsAttention)
    }

    @Test
    fun `crop evidence rejects unsupported vision store code and expiry`() {
        val base = coupon(
            storeName = "CRED",
            description = "Flat 20% off on bill payments",
            redeemCode = null,
            rawOcrText = """
                CRED
                Flat 20% off on bill payments
                Tap to claim
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "Amazon",
                description = "Flat 20% off on bill payments",
                redeemCode = "SAVE20",
                expiryText = "EXPIRES IN 7 DAYS",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.PRESENT,
                evidence = """
                    store: Amazon
                    offer: Flat 20% off on bill payments
                    code: SAVE20
                    expiry: EXPIRES IN 7 DAYS
                """.trimIndent()
            )
        )

        val merged = policy.mergeFieldLabels(
            current = base,
            vision = vision,
            rawOcr = base.rawOcrText,
            visionInput = cropInput(),
            captureTimestamp = localDate(2026, 6, 28)
        )

        assertEquals("CRED", merged.storeName)
        assertNull(merged.redeemCode)
        assertNull(merged.expiryDate)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
    }

    @Test
    fun `no-code modal state is not trusted without visible no-code evidence`() {
        val base = coupon(
            storeName = "IDFC FIRST Bank",
            description = "No cost EMI on credit card",
            redeemCode = null,
            rawOcrText = """
                IDFC FIRST Bank
                No cost EMI on credit card
                Terms apply
            """.trimIndent()
        )
        val vision = extraction(
            card = card(
                storeName = "IDFC FIRST Bank",
                description = "No cost EMI on credit card",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = """
                    store: IDFC FIRST Bank
                    offer: No cost EMI on credit card
                """.trimIndent()
            )
        )

        val merged = policy.mergeFieldLabels(
            current = base,
            vision = vision,
            rawOcr = base.rawOcrText,
            visionInput = cropInput(),
            captureTimestamp = localDate(2026, 6, 28)
        )

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
    }

    @Test
    fun `full image vision labels without crop evidence stay review only`() {
        val base = coupon(
            storeName = Coupon.Defaults.UNKNOWN_STORE,
            description = "Needs review",
            redeemCode = null,
            rawOcrText = null
        )
        val vision = extraction(
            card = card(
                storeName = "AJIO",
                description = "Flat 50% off on fashion",
                redeemCode = "SAVE50",
                expiryText = "EXPIRES IN 7 DAYS",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.PRESENT,
                layoutState = Coupon.LayoutState.MULTI_CARD,
                evidence = """
                    store: AJIO
                    offer: Flat 50% off on fashion
                    code: SAVE50
                    expiry: EXPIRES IN 7 DAYS
                """.trimIndent()
            )
        )

        val merged = policy.mergeFieldLabels(
            current = base,
            vision = vision,
            rawOcr = null,
            visionInput = fullImageInput(),
            captureTimestamp = localDate(2026, 6, 28)
        )

        assertEquals(Coupon.Defaults.UNKNOWN_STORE, merged.storeName)
        assertEquals("Needs review", merged.description)
        assertNull(merged.redeemCode)
        assertNull(merged.expiryDate)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, merged.expiryState)
        assertEquals(Coupon.LayoutState.MULTI_CARD, merged.layoutState)
        assertTrue(merged.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertNotEquals(Coupon.ExtractionSource.VISION_VERIFIED, merged.extractionSource)
        assertTrue(merged.debugVisionEvidence.orEmpty().contains("\"source\":\"full_image\""))
        assertFalse(merged.debugVisionEvidence.orEmpty().contains("\"pixelCrop\""))
    }

    private fun coupon(
        storeName: String,
        description: String,
        redeemCode: String?,
        expiryDate: Date? = null,
        extractionTimestamp: Date? = null,
        rawOcrText: String? = null
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            redeemCode = redeemCode,
            imageUri = null,
            rawOcrText = rawOcrText,
            extractionTimestamp = extractionTimestamp
        )
    }

    private fun extraction(card: VisionCouponCard): VisionFieldExtraction {
        return VisionFieldExtraction(
            cards = listOf(card),
            activeCard = card,
            confidence = 0.95f,
            rawEvidence = card.evidence.orEmpty()
        )
    }

    private fun card(
        storeName: String,
        description: String,
        redeemCode: String? = null,
        expiryText: String? = null,
        codeState: String = Coupon.CodeState.PRESENT,
        expiryState: String = Coupon.ExpiryState.PRESENT,
        layoutState: String = Coupon.LayoutState.MODAL_FOREGROUND,
        evidence: String
    ): VisionCouponCard {
        return VisionCouponCard(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            expiryText = expiryText,
            codeState = codeState,
            expiryState = expiryState,
            layoutState = layoutState,
            confidence = 0.95f,
            evidence = evidence,
            bounds = null,
            active = true
        )
    }

    private fun cropInput(): VisionFieldMergeInput {
        return VisionFieldMergeInput(
            usedTargetedCrop = true,
            source = "layout",
            normalizedBoundsJson = null,
            pixelCrop = Rect(0, 0, 200, 120),
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

    private fun localDate(year: Int, month: Int, day: Int): Date {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    private fun Date.toLocalDate(): LocalDate {
        return toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
