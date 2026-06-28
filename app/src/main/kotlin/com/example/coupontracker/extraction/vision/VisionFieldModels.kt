package com.example.coupontracker.extraction.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon

data class VisionNormalizedBounds(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun isUsable(): Boolean {
        if (x !in 0f..1f || y !in 0f..1f) return false
        if (w <= MIN_SIZE || h <= MIN_SIZE) return false
        if (right > 1f || bottom > 1f) return false
        if (w > MAX_SIZE || h > MAX_SIZE) return false
        val area = w * h
        if (area < MIN_AREA || area > MAX_AREA) return false
        return true
    }

    fun toPixelRect(bitmap: Bitmap, paddingRatio: Float = DEFAULT_PADDING_RATIO): Rect {
        val padX = w * paddingRatio
        val padY = h * paddingRatio
        val left = ((x - padX).coerceAtLeast(0f) * bitmap.width).toInt()
        val top = ((y - padY).coerceAtLeast(0f) * bitmap.height).toInt()
        val right = ((right + padX).coerceAtMost(1f) * bitmap.width).toInt()
        val bottom = ((bottom + padY).coerceAtMost(1f) * bitmap.height).toInt()
        return Rect(left, top, right.coerceAtLeast(left + 1), bottom.coerceAtLeast(top + 1))
    }

    companion object {
        private const val MIN_SIZE = 0.05f
        private const val MAX_SIZE = 0.98f
        private const val MIN_AREA = 0.01f
        private const val MAX_AREA = 0.95f
        const val DEFAULT_PADDING_RATIO = 0.07f
    }
}

data class VisionLayoutResult(
    val cards: List<VisionLayoutCard>,
    val activeCard: VisionLayoutCard?,
    val confidence: Float,
    val layoutState: String,
    val rawEvidence: String
)

data class VisionLayoutCard(
    val id: String?,
    val bounds: VisionNormalizedBounds,
    val confidence: Float,
    val layoutState: String,
    val active: Boolean,
    val modal: Boolean
)

data class VisionFieldLabelResult(
    val fields: VisionCouponFields,
    val noise: List<String>,
    val layoutState: String,
    val confidence: Float,
    val rawEvidence: String
)

data class VisionCouponFields(
    val store: VisionFieldLabel,
    val description: VisionFieldLabel,
    val code: VisionFieldLabel,
    val expiry: VisionFieldLabel
)

data class VisionFieldLabel(
    val state: String,
    val text: String?,
    val evidence: List<String>,
    val confidence: Float
)

data class VisionFieldExtraction(
    val cards: List<VisionCouponCard>,
    val activeCard: VisionCouponCard?,
    val confidence: Float,
    val rawEvidence: String
)

data class VisionFieldMergeInput(
    val usedTargetedCrop: Boolean,
    val source: String,
    val normalizedBoundsJson: String?,
    val pixelCrop: Rect?,
    val layoutState: String?,
    val debugEvidence: String?
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
    val active: Boolean,
    val normalizedBounds: VisionNormalizedBounds? = null,
    val fieldEvidence: Map<String, List<String>> = emptyMap(),
    val noise: List<String> = emptyList()
)

internal val VALID_TEXT_FIELD_STATES = setOf(
    "PRESENT",
    "NOT_VISIBLE",
    "UNKNOWN"
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
