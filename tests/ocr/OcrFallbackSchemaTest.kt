package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFallbackSchemaTest {

    @Test
    fun `tesseract extraction yields valid coupon info`() {
        val text = """
            Example Store
            Save 20% on your next order
            Code: SAVE20
            Cashback: 20
            Valid till 31/12/2025
        """.trimIndent()

        val extractor = TextExtractor()
        val info = extractor.extractCouponInfoSync(text)

        assertTrue("primary extraction should produce valid coupon info", info.isValid())
        assertEquals("Example Store", info.storeName)
        assertEquals("SAVE20", info.redeemCode)
    }

    @Test
    fun `fallback schema preserves populated fields`() {
        val text = """
            Example Store
            Use code SAVE20 to earn ₹200 cashback on electronics
            Offer valid through 31/12/2025
        """.trimIndent()

        val extractor = TextExtractor()
        val primary = extractor.extractCouponInfoSync(text)

        val fallback = CouponInfo(
            storeName = "Example Store",
            description = "Use code SAVE20 to earn ₹200 cashback on electronics",
            cashbackAmount = 200.0,
            redeemCode = "SAVE20",
            expiryDate = primary.expiryDate,
            category = primary.category
        )

        val primaryFields = presentFields(primary)
        val fallbackFields = presentFields(fallback)

        assertEquals(primaryFields, fallbackFields)
    }

    private fun presentFields(info: CouponInfo): Set<String> {
        val fields = mutableSetOf<String>()
        if (info.storeName.isNotBlank()) fields.add("storeName")
        if (info.description.isNotBlank()) fields.add("description")
        if (info.cashbackAmount != null) fields.add("cashbackAmount")
        if (!info.redeemCode.isNullOrBlank()) fields.add("redeemCode")
        if (info.expiryDate != null) fields.add("expiryDate")
        if (!info.category.isNullOrBlank()) fields.add("category")
        return fields
    }
}
