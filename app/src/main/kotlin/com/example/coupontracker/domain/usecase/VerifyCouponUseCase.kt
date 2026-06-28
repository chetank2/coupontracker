package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.ExtractionRecommendation
import java.util.Date

class VerifyCouponUseCase(
    private val now: () -> Date = { Date() }
) {

    fun markRunning(coupon: Coupon): Coupon {
        return coupon.copy(
            cleanupStatus = Coupon.CleanupStatus.RUNNING,
            cleanupStartedAt = now(),
            cleanupFinishedAt = null,
            cleanupError = null,
            updatedAt = now()
        )
    }

    fun markDeterministicBaselineRunning(deterministicCleaned: Coupon): Coupon {
        return deterministicCleaned.copy(
            cleanupStatus = Coupon.CleanupStatus.RUNNING,
            cleanupStartedAt = now(),
            cleanupFinishedAt = null,
            cleanupError = null,
            lastCleanedBy = null,
            updatedAt = now()
        )
    }

    fun shouldRunVisionVerification(
        userRequested: Boolean,
        automaticVerification: Boolean,
        deterministicCleaned: Coupon?,
        rawOcr: String,
        gemmaEnabled: Boolean,
        gemmaInstalled: Boolean
    ): Boolean {
        if (!gemmaEnabled || !gemmaInstalled) return false
        if (userRequested) return true
        return automaticVerification &&
            deterministicCleaned?.needsVisionReviewAfterDeterministicCleanup(rawOcr) == true
    }

    fun mergeLatestCouponState(baseline: Coupon, latest: Coupon): Coupon {
        val cleanupBaseline = baseline.copy(
            cleanupStatus = latest.cleanupStatus,
            cleanupStartedAt = latest.cleanupStartedAt,
            cleanupFinishedAt = latest.cleanupFinishedAt,
            cleanupError = latest.cleanupError,
            updatedAt = latest.updatedAt
        )
        return if (latest.extractionSource == Coupon.ExtractionSource.USER_EDITED) {
            latest
        } else {
            cleanupBaseline
        }
    }

    private fun Coupon.needsVisionReviewAfterDeterministicCleanup(rawOcr: String): Boolean {
        val assessment = CouponExtractionConfidenceScorer.score(this, rawOcr)
        val missingCodeState = redeemCode.isNullOrBlank() && codeState == Coupon.CodeState.UNKNOWN
        val missingExpiryState = expiryDate == null && expiryState == Coupon.ExpiryState.UNKNOWN
        return needsAttention ||
            cleanupStatus == Coupon.CleanupStatus.FAILED ||
            assessment.recommendation == ExtractionRecommendation.VERIFY_WITH_VISION ||
            assessment.recommendation == ExtractionRecommendation.MANUAL_REVIEW ||
            missingCodeState ||
            missingExpiryState
    }
}
