package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import javax.inject.Inject

class SaveCouponUseCase @Inject constructor(
    private val repository: CouponRepository
) {
    suspend operator fun invoke(coupon: Coupon): Long {
        return repository.saveOrMergeCoupon(
            coupon = coupon,
            normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description),
            imagePhash = coupon.imagePhash,
            imageSignature = coupon.imageSignature
        )
    }
}
