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

    @Test
    fun sanitizeDescription_deduplicatesAndRemovesNoise() {
        val description = "Minimalist Minimalist Radiance Kit Pastm Uconn lioht fuid sof beminimalist.co"

        val sanitized = helper.sanitizeDescription(description)

        val minimalOccurrences = sanitized.split(Regex("\\s+")).count { it.equals("Minimalist", ignoreCase = true) }
        assertEquals("Minimalist should appear only once", 1, minimalOccurrences)
        assertTrue("Radiance should be preserved", sanitized.contains("Radiance"))
        assertTrue("Kit should be preserved", sanitized.contains("Kit"))
        assertTrue("Domain should be preserved", sanitized.contains("beminimalist.co"))
        assertTrue("Noise tokens should be removed", listOf("Pastm", "Uconn", "lioht", "fuid", "sof").none { sanitized.contains(it, ignoreCase = true) })
    }

    @Test
    fun extractCouponInfo_returnsSanitizedDescription() {
        val text = """
            BRAND: Minimalist
            Offer: Flat ₹100 Off + ₹50 Cashback
            Description: Minimalist Minimalist Radiance Kit Pastm Uconn lioht fuid sof beminimalist.co
        """.trimIndent()

        val result = helper.extractCouponInfo(text)

        val description = result["description"] ?: ""
        assertTrue("Description should mention Radiance Kit", description.contains("Radiance Kit"))
        assertTrue("Description should not include noise tokens", listOf("Pastm", "Uconn", "lioht", "fuid", "sof").none { description.contains(it, ignoreCase = true) })
    }
}
