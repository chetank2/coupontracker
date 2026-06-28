package com.example.coupontracker.domain.usecase

import android.content.Context
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.debug.ExtractionDebugRepository
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.worker.VerifyCouponWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject

class SaveScannedCouponUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val debugRepository: ExtractionDebugRepository,
    private val analyticsTracker: AnalyticsTracker
) {

    suspend operator fun invoke(
        coupon: Coupon,
        normalizedDescription: String,
        llmStatusName: String,
        debugSnapshot: ExtractionDebugSnapshot?
    ): SaveScannedCouponResult {
        val savedCouponId = couponRepository.saveOrMergeCoupon(
            coupon = coupon,
            normalizedDescription = normalizedDescription,
            imagePhash = null,
            imageSignature = null
        )
        val savedCoupon = couponRepository.getCouponById(savedCouponId)

        debugSnapshot?.let { snapshot ->
            debugRepository.updateSnapshot(savedCouponId, snapshot)
        }

        val isDuplicate = savedCoupon?.createdAt?.before(coupon.createdAt) == true
        val couponForUi = savedCoupon ?: coupon.copy(id = savedCouponId)
        val persisted = !isDuplicate
        val analyticsResult = if (isDuplicate) "duplicate" else "created"

        analyticsTracker.trackEvent(
            AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
            mapOf(
                "persisted" to persisted,
                "result" to analyticsResult,
                "llm_status" to llmStatusName
            )
        )

        if (persisted || couponForUi.requiresLayoutOwnershipVerification()) {
            maybeQueueAutomaticVerification(couponForUi)
        }

        return SaveScannedCouponResult(
            savedCouponId = savedCouponId,
            couponForUi = couponForUi,
            kind = if (isDuplicate) SaveScannedCouponResult.Kind.ALREADY_SAVED else SaveScannedCouponResult.Kind.SAVED,
            analyticsResult = analyticsResult,
            persisted = persisted
        )
    }

    private suspend fun maybeQueueAutomaticVerification(coupon: Coupon) {
        val assessment = CouponExtractionConfidenceScorer.score(coupon, coupon.rawOcrText)
        val layoutOwnershipVerification = coupon.requiresLayoutOwnershipVerification()
        if (assessment.recommendation != ExtractionRecommendation.VERIFY_WITH_VISION &&
            !layoutOwnershipVerification
        ) {
            return
        }
        if (coupon.cleanupStatus == Coupon.CleanupStatus.RUNNING ||
            coupon.hasTrustedCleanup()
        ) {
            return
        }

        couponRepository.updateCoupon(
            coupon.copy(
                cleanupStatus = Coupon.CleanupStatus.PENDING,
                cleanupError = null,
                cleanupStartedAt = null,
                cleanupFinishedAt = null,
                updatedAt = Date()
            )
        )

        VerifyCouponWorker.enqueueAutomaticVerification(context, coupon.id)
    }

    private fun Coupon.hasTrustedCleanup(): Boolean {
        return cleanupStatus == Coupon.CleanupStatus.CLEANED &&
            !needsAttention &&
            extractionSource in setOf(
                Coupon.ExtractionSource.VISION_VERIFIED,
                Coupon.ExtractionSource.QWEN_CLEANED,
                Coupon.ExtractionSource.OCR_VERIFIED
            )
    }

    private fun Coupon.requiresLayoutOwnershipVerification(): Boolean {
        return layoutState == Coupon.LayoutState.LOW_CONFIDENCE &&
            needsAttention &&
            (
                debugVisionEvidence.orEmpty().contains("layout_source=heuristic") ||
                    cleanupError.orEmpty().contains("layout ownership", ignoreCase = true)
                )
    }
}

data class SaveScannedCouponResult(
    val savedCouponId: Long,
    val couponForUi: Coupon,
    val kind: Kind,
    val analyticsResult: String,
    val persisted: Boolean
) {
    enum class Kind {
        SAVED,
        ALREADY_SAVED
    }
}
