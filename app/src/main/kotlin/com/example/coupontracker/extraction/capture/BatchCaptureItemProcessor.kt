package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import javax.inject.Inject

/**
 * Routes one selected batch item through the correct capture path while keeping
 * bitmap ownership explicit for callers.
 */
class BatchCaptureItemProcessor @Inject constructor() {

    suspend fun process(
        input: BatchCaptureInput,
        decodeBitmap: (Uri) -> Bitmap?,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit,
        processPdf: suspend (Uri) -> Coupon,
        extractImageCoupons: suspend (Uri, Bitmap) -> List<Coupon>
    ): BatchCaptureItemResult {
        if (input.isPdf()) {
            val coupon = processPdf(input.uri)
            return BatchCaptureItemResult.success(listOf(coupon))
        }

        if (!input.isImage()) {
            return BatchCaptureItemResult.failure("Unsupported file type")
        }

        var bitmap: Bitmap? = null
        try {
            bitmap = decodeBitmap(input.uri)
            if (bitmap == null) {
                return BatchCaptureItemResult.failure("Unable to open image")
            }

            trackBitmap(bitmap)
            val coupons = extractImageCoupons(input.uri, bitmap)
            return if (coupons.isNotEmpty()) {
                BatchCaptureItemResult.success(coupons)
            } else {
                BatchCaptureItemResult.failure("No coupons detected")
            }
        } finally {
            bitmap?.let(releaseBitmap)
        }
    }
}

data class BatchCaptureInput(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
) {
    fun isImage(): Boolean = mimeType.startsWith("image/") || mimeType == "image/*"
    fun isPdf(): Boolean = mimeType == "application/pdf"
}

data class BatchCaptureItemResult(
    val coupons: List<Coupon>,
    val success: Boolean,
    val message: String?,
    val couponsFound: Int
) {
    companion object {
        fun success(coupons: List<Coupon>): BatchCaptureItemResult {
            return BatchCaptureItemResult(
                coupons = coupons,
                success = true,
                message = null,
                couponsFound = coupons.size
            )
        }

        fun failure(message: String): BatchCaptureItemResult {
            return BatchCaptureItemResult(
                coupons = emptyList(),
                success = false,
                message = message,
                couponsFound = 0
            )
        }
    }
}
