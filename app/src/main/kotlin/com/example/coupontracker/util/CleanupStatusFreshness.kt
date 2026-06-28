package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import java.util.Date

object CleanupStatusFreshness {
    private const val STALE_IN_PROGRESS_MS = 4 * 60 * 1000L

    fun isFreshInProgress(coupon: Coupon, now: Date = Date()): Boolean {
        if (coupon.cleanupStatus != Coupon.CleanupStatus.PENDING &&
            coupon.cleanupStatus != Coupon.CleanupStatus.RUNNING
        ) {
            return false
        }
        val startedAt = coupon.cleanupStartedAt ?: coupon.updatedAt
        return now.time - startedAt.time <= STALE_IN_PROGRESS_MS
    }
}
