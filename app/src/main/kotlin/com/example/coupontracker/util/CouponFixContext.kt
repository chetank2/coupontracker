package com.example.coupontracker.util

import java.util.Date

data class CouponFixContext(
    val ocrText: String? = null,
    val captureTimestamp: Date? = null
)

