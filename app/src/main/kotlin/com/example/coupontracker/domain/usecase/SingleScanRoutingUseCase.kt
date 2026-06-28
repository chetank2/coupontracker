package com.example.coupontracker.domain.usecase

import javax.inject.Inject

class SingleScanRoutingUseCase @Inject constructor() {

    fun planAfterCropDetection(
        detectorAvailable: Boolean,
        detectedCropCount: Int
    ): SingleScanRouteAction {
        return when (val decision = decideAfterCropDetection(detectorAvailable, detectedCropCount)) {
            is SingleScanRouteDecision.TryLayoutThenGuardedFallback -> {
                SingleScanRouteAction.TryLayoutThenGuardedFallback(
                    reason = decision.reason,
                    executedStrategy = "layout_multi_coupon_probe"
                )
            }
            SingleScanRouteDecision.ProcessSingleCrop -> SingleScanRouteAction.ProcessSingleCrop(
                reason = "single_coupon_crop_detected",
                executedStrategy = "ocr_first_card_crop"
            )
            SingleScanRouteDecision.ShowMultiCouponSelection -> SingleScanRouteAction.ShowMultiCouponSelection(
                reason = "multiple_coupon_crops_detected",
                executedStrategy = "multi_coupon_selection"
            )
        }
    }

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

sealed class SingleScanRouteAction {
    abstract val reason: String
    abstract val executedStrategy: String

    data class TryLayoutThenGuardedFallback(
        override val reason: String,
        override val executedStrategy: String
    ) : SingleScanRouteAction()

    data class ProcessSingleCrop(
        override val reason: String,
        override val executedStrategy: String
    ) : SingleScanRouteAction()

    data class ShowMultiCouponSelection(
        override val reason: String,
        override val executedStrategy: String
    ) : SingleScanRouteAction()
}
