package com.example.coupontracker.domain.usecase

import android.content.Context
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.worker.VerifyCouponWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject

class CleanCouponUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CouponRepository
) {
    suspend operator fun invoke(couponId: Long): Boolean {
        val coupon = repository.getCouponById(couponId) ?: return false
        repository.updateCoupon(
            coupon.copy(
                cleanupStatus = Coupon.CleanupStatus.PENDING,
                cleanupError = null,
                cleanupStartedAt = null,
                cleanupFinishedAt = null,
                updatedAt = Date()
            )
        )
        VerifyCouponWorker.enqueueAutomaticVerification(context, couponId)
        return true
    }
}
