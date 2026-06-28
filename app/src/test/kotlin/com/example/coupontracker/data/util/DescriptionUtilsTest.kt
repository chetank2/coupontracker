package com.example.coupontracker.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescriptionUtilsTest {

    @Test
    fun `format cashback ignores standalone percent confidence`() {
        assertNull(DescriptionUtils.formatCashbackDetail("82%"))
    }

    @Test
    fun `append details drops standalone percent line`() {
        val description = DescriptionUtils.appendDetails("Flat offer on groceries", "82%")

        assertEquals("Flat offer on groceries", description)
    }

    @Test
    fun `append details uses review-safe text when no offer detail remains`() {
        val description = DescriptionUtils.appendDetails("Coupon offer", "82%")

        assertEquals("Needs review: offer details not extracted", description)
    }

    @Test
    fun `append details drops zero cashback line`() {
        val description = DescriptionUtils.appendDetails(
            "Get 33% off on Domino's",
            "cashback: 0.0 cashback 0.0"
        )

        assertEquals("Get 33% off on Domino's", description)
    }

    @Test
    fun `format cashback ignores zero cashback text`() {
        assertNull(DescriptionUtils.formatCashbackDetail("cashback: 0.0 cashback 0.0"))
    }

    @Test
    fun `extract cashback line ignores zero value savings`() {
        assertNull(DescriptionUtils.extractCashbackLine("Offer details\nCashback: 0.0"))
    }

    @Test
    fun `format cashback keeps percent with offer context`() {
        assertEquals(
            "Cashback: 20% off",
            DescriptionUtils.formatCashbackDetail("20% off")
        )
    }

    @Test
    fun `display description collapses noisy domino ocr lines`() {
        val rawDescription = """
            TM
            Domino's
            Get
            33%
            Off*
            750
            Cashback
            for
            New
            Users
            on
            Domino's
            Code:
            PHP300SE1XYZ
            ORDER
            NOW
            Offer
            GV062%
            Expires
            on
            30
            Jun,
            2026,
            pm
            About
            Domino's
            Copy
            33%
        """.trimIndent()

        assertEquals(
            "Get 33% off 750 Cashback for New Users on Domino's",
            DescriptionUtils.formatDisplayDescription(
                description = rawDescription,
                storeName = "Domino",
                redeemCode = "PHP300SE1XYZ"
            )
        )
    }

    @Test
    fun `display description preserves normal readable text`() {
        assertEquals(
            "Flat 20% off on groceries\nCashback: ₹100 off",
            DescriptionUtils.formatDisplayDescription(
                description = "Flat 20% off on groceries\nCashback: ₹100 off",
                storeName = "Store",
                redeemCode = "SAVE100"
            )
        )
    }

    @Test
    fun `display description does not surface placeholder as offer`() {
        assertEquals(
            "Needs review: offer details not extracted",
            DescriptionUtils.formatDisplayDescription("Coupon offer")
        )
    }
}
