package com.example.coupontracker.domain.usecase

import javax.inject.Inject

class SingleScanRoutingUseCase @Inject constructor() {

    fun decideAfterCropDetection(
        detectorAvailable: Boolean,
        detectedCropCount: Int
    ): SingleScanRouteDecision {
        if (!detectorAvailable) {
            return SingleScanRouteDecision.TryLayoutThenGuardedFallback(
                reason = "coupon_detector_unavailable"
            )
        }
        return when (detectedCropCount) {
            0 -> SingleScanRouteDecision.TryLayoutThenGuardedFallback(
                reason = "no_coupon_crop_detected"
            )
            1 -> SingleScanRouteDecision.ProcessSingleCrop
            else -> SingleScanRouteDecision.ShowMultiCouponSelection
        }
    }
}

sealed class SingleScanRouteDecision {
    data class TryLayoutThenGuardedFallback(val reason: String) : SingleScanRouteDecision()
    data object ProcessSingleCrop : SingleScanRouteDecision()
    data object ShowMultiCouponSelection : SingleScanRouteDecision()
}
