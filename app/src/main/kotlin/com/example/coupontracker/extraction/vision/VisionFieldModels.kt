package com.example.coupontracker.extraction.vision

import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon

data class VisionFieldExtraction(
    val cards: List<VisionCouponCard>,
    val activeCard: VisionCouponCard?,
    val confidence: Float,
    val rawEvidence: String
)

data class VisionCouponCard(
    val storeName: String?,
    val description: String?,
    val redeemCode: String?,
    val expiryText: String?,
    val codeState: String,
    val expiryState: String,
    val layoutState: String,
    val confidence: Float,
    val evidence: String?,
    val bounds: Rect?,
    val active: Boolean
)

internal val VALID_CODE_STATES = setOf(
    Coupon.CodeState.PRESENT,
    Coupon.CodeState.NO_CODE_NEEDED,
    Coupon.CodeState.NOT_VISIBLE,
    Coupon.CodeState.UNKNOWN
)

internal val VALID_EXPIRY_STATES = setOf(
    Coupon.ExpiryState.PRESENT,
    Coupon.ExpiryState.NOT_VISIBLE,
    Coupon.ExpiryState.UNKNOWN
)

internal val VALID_LAYOUT_STATES = setOf(
    Coupon.LayoutState.COMPLETE,
    Coupon.LayoutState.PARTIAL,
    Coupon.LayoutState.MODAL_FOREGROUND,
    Coupon.LayoutState.MULTI_CARD,
    Coupon.LayoutState.LOW_CONFIDENCE
)
