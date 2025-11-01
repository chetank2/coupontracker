package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLlmResponseDetectorHeuristicsTest {

    @Test
    fun `flags stub metadata with low confidence`() {
        val stubCoupon = CouponInfo(
            storeName = "Retail Demo",
            description = "Limited time placeholder offer",
            redeemCode = "SAVE10",
            needsAttention = true,
            storeNameSource = "stub_runtime",
            storeNameEvidence = emptyList()
        )

        assertTrue(MockLlmResponseDetector.isMockResponse(stubCoupon))
    }

    @Test
    fun `flags identical store and code pairs`() {
        val duplicatedCoupon = CouponInfo(
            storeName = "MOCK100",
            description = "Copy this code to save",
            redeemCode = "MOCK100"
        )

        assertTrue(MockLlmResponseDetector.isMockResponse(duplicatedCoupon))
    }

    @Test
    fun `allows real coupon samples`() {
        val realCoupon = CouponInfo(
            storeName = "Flipkart",
            description = "Extra 15% off on electronics above ₹999",
            redeemCode = "FLIP15",
            needsAttention = false,
            storeNameEvidence = listOf("Flipkart app header")
        )

        assertFalse(MockLlmResponseDetector.isMockResponse(realCoupon))
    }
}
