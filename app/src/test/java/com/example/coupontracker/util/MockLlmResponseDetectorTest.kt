package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLlmResponseDetectorTest {

    @Test
    fun `detects mlc stub placeholder payload`() {
        val placeholderCoupon = CouponInfo(
            storeName = "Mock Store",
            description = "Mock coupon offer - 50% off",
            cashbackAmount = 100.0,
            redeemCode = "MOCK50",
            minimumPurchase = 1000.0
        )

        assertTrue(MockLlmResponseDetector.isMockResponse(placeholderCoupon))
    }

    @Test
    fun `does not flag legitimate coupon data`() {
        val realCoupon = CouponInfo(
            storeName = "Amazon",
            description = "Flat 50% OFF on electronics above ₹999",
            cashbackAmount = 50.0,
            redeemCode = "AMZ50",
            minimumPurchase = 999.0
        )

        assertFalse(MockLlmResponseDetector.isMockResponse(realCoupon))
    }
}

