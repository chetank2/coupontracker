package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedOCRHelperTest {

    private val helper = EnhancedOCRHelper()

    @Test
    fun normalizeCouponCode_replacesAmbiguousCharacters() {
        val rawCode = "MNPRRK10UAPR255QYSGZA"
        val normalized = helper.normalizeCouponCode(rawCode)
        assertEquals("MNPRRK10OUAPR255QYSG7A", normalized)
    }

    @Test
    fun extractCouponInfo_preservesLegitimateHighValueOffer() {
        val text = "Mega Festival: Grab flat 7000 off on electronics"
        val result = helper.extractCouponInfo(text)

        assertEquals("₹7000", result["amount"])
    }

    @Test
    fun extractCouponInfo_correctsStrayLeadingSevenInSplitAmounts() {
        val text = "Special vouchers: 7100/750 cashback on groceries"
        val result = helper.extractCouponInfo(text)

        assertEquals("₹100", result["amount"])
    }

    @Test
    fun sanitizeDescription_preservesMixedAlphanumericTokens() {
        val description = "New arrivals: B12 energy shots with SPF50 protection"

        val sanitized = helper.sanitizeDescription(description)

        assertTrue("Expected B12 to remain in sanitized description", sanitized.contains("B12"))
        assertTrue("Expected SPF50 to remain in sanitized description", sanitized.contains("SPF50"))
    }
}
