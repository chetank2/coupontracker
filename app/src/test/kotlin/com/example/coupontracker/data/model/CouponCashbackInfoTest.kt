package com.example.coupontracker.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CouponCashbackInfoTest {

    @Test
    fun `coupon cashback info retains usd currency`() {
        val coupon = Coupon(
            storeName = "Global Store",
            description = "Cashback: ${'$'}40 off",
            redeemCode = null,
            imageUri = null
        )

        val info = coupon.getCashbackInfo()

        assertEquals(CashbackType.AMOUNT, info.type)
        assertEquals(40.0, info.valueNum, 0.0)
        assertEquals("\$", info.currency)
        assertEquals("\$40", info.getDisplayText())
    }

    @Test
    fun `coupon cashback info retains eur currency`() {
        val coupon = Coupon(
            storeName = "Euro Shop",
            description = "Holiday Deals\nCashback: €25 off",
            redeemCode = null,
            imageUri = null
        )

        val info = coupon.getCashbackInfo()

        assertEquals(CashbackType.AMOUNT, info.type)
        assertEquals(25.0, info.valueNum, 0.0)
        assertEquals("€", info.currency)
        assertEquals("€25", info.getDisplayText())
    }

    @Test
    fun `coupon cashback info defaults to text when no currency`() {
        val coupon = Coupon(
            storeName = "Mystery Store",
            description = "Exclusive reward",
            redeemCode = null,
            imageUri = null
        )

        val info = coupon.getCashbackInfo()

        assertEquals(CashbackType.TEXT, info.type)
        assertTrue(info.getDisplayText().isNotEmpty())
    }
}

