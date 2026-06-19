package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class ShareCouponUseCase @Inject constructor() {
    operator fun invoke(coupon: Coupon): String {
        val lines = mutableListOf<String>()
        lines += coupon.storeName
        lines += coupon.description
        coupon.redeemCode?.takeIf { it.isNotBlank() }?.let { lines += "Code: $it" }
        coupon.expiryDate?.let { expiry ->
            lines += "Expires: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(expiry)}"
        }
        return lines.joinToString(separator = "\n")
    }
}
