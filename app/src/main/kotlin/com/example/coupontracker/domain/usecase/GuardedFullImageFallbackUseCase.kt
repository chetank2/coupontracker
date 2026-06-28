package com.example.coupontracker.domain.usecase

import android.content.Context
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.extraction.capture.FullImageFallbackReviewCouponFactory
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.UriPersistenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class GuardedFullImageFallbackUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reviewCouponFactory: FullImageFallbackReviewCouponFactory,
    private val saveScannedCouponUseCase: SaveScannedCouponUseCase
) {
    private val uriPersistenceManager = UriPersistenceManager(context)

    suspend operator fun invoke(
        imageUri: Uri,
        rawOcrText: String,
        reason: String,
        persistImmediately: Boolean
    ): GuardedFullImageFallbackResult {
        val captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
        val persistedUri = uriPersistenceManager.persistUri(imageUri)
        val finalImageUri = (persistedUri ?: imageUri).toString()
        val coupon = reviewCouponFactory.create(
            imageUri = finalImageUri,
            rawOcrText = rawOcrText,
            reason = reason,
            captureTimestamp = captureTimestamp
        )
        val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)

        if (!persistImmediately) {
            return GuardedFullImageFallbackResult.Preview(
                coupon = coupon,
                normalizedDescription = normalizedDescription
            )
        }

        val saveResult = saveScannedCouponUseCase(
            coupon = coupon,
            normalizedDescription = normalizedDescription,
            llmStatusName = REVIEW_STATUS_NAME,
            debugSnapshot = null
        )
        return GuardedFullImageFallbackResult.Persisted(
            saveResult = saveResult,
            coupon = coupon,
            normalizedDescription = normalizedDescription
        )
    }

    companion object {
        const val REVIEW_STATUS_NAME = "NEEDS_REVIEW"
    }
}

sealed class GuardedFullImageFallbackResult {
    abstract val coupon: Coupon
    abstract val normalizedDescription: String

    data class Preview(
        override val coupon: Coupon,
        override val normalizedDescription: String
    ) : GuardedFullImageFallbackResult()

    data class Persisted(
        val saveResult: SaveScannedCouponResult,
        override val coupon: Coupon,
        override val normalizedDescription: String
    ) : GuardedFullImageFallbackResult()
}
