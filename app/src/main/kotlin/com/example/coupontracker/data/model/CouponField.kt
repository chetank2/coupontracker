package com.example.coupontracker.data.model

import android.graphics.RectF

/**
 * Represents a field detected in a coupon image
 */
data class CouponField(
    val type: FieldType,
    val boundingBox: RectF,
    val confidence: Float,
    val text: String = ""
)

/**
 * Types of fields that can be detected in a coupon
 */
enum class FieldType {
    STORE_NAME,
    COUPON_CODE,
    EXPIRY_DATE,
    DESCRIPTION,
    AMOUNT,
    MIN_PURCHASE,
    OTHER
}
