package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CleanupStatusFreshnessTest {

    @Test
    fun `fresh running cleanup is in progress`() {
        val now = Date(10 * 60 * 1000L)
        val coupon = coupon(
            status = Coupon.CleanupStatus.RUNNING,
            startedAt = Date(now.time - 60 * 1000L)
        )

        assertTrue(CleanupStatusFreshness.isFreshInProgress(coupon, now))
    }

    @Test
    fun `stale running cleanup is retryable`() {
        val now = Date(10 * 60 * 1000L)
        val coupon = coupon(
            status = Coupon.CleanupStatus.RUNNING,
            startedAt = Date(now.time - 5 * 60 * 1000L)
        )

        assertFalse(CleanupStatusFreshness.isFreshInProgress(coupon, now))
    }

    @Test
    fun `failed cleanup is not in progress`() {
        val now = Date(10 * 60 * 1000L)
        val coupon = coupon(
            status = Coupon.CleanupStatus.FAILED,
            startedAt = Date(now.time)
        )

        assertFalse(CleanupStatusFreshness.isFreshInProgress(coupon, now))
    }

    private fun coupon(status: String, startedAt: Date): Coupon {
        return Coupon(
            storeName = "Sample Store",
            description = "Save 10%",
            redeemCode = null,
            imageUri = null,
            cleanupStatus = status,
            cleanupStartedAt = startedAt,
            updatedAt = startedAt
        )
    }
}
