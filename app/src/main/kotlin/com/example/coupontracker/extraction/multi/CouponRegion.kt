package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import android.graphics.Rect

data class CouponRegion(
    val bounds: Rect,
    val crop: Bitmap,
    val detectionConfidence: Float
)
