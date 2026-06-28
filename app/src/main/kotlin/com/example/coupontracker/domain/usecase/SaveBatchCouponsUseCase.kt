package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import javax.inject.Inject

class SaveBatchCouponsUseCase @Inject constructor(
    private val repository: CouponRepository
) {
    suspend operator fun invoke(coupons: List<Coupon>) {
        coupons.forEach { coupon ->
            repository.insertCoupon(coupon)
        }
    }
}
