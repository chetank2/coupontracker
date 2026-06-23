package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap

interface CouponLayoutDetector {
    val name: String

    suspend fun detectLayout(
        bitmap: Bitmap,
        context: LayoutDetectionContext
    ): CouponLayoutDetection
}
