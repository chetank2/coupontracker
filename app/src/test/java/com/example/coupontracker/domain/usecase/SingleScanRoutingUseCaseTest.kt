package com.example.coupontracker.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleScanRoutingUseCaseTest {

    private val useCase = SingleScanRoutingUseCase()

    @Test
    fun `missing detector routes through layout then guarded fallback`() {
        val decision = useCase.decideAfterCropDetection(
            detectorAvailable = false,
            detectedCropCount = 0
        )

        assertTrue(decision is SingleScanRouteDecision.TryLayoutThenGuardedFallback)
        assertEquals(
            "coupon_detector_unavailable",
            (decision as SingleScanRouteDecision.TryLayoutThenGuardedFallback).reason
        )
    }

    @Test
    fun `single crop plan carries crop execution telemetry`() {
        val action = useCase.planAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 1
        )

        assertTrue(action is SingleScanRouteAction.ProcessSingleCrop)
        assertEquals("ocr_first_card_crop", action.executedStrategy)
        assertEquals("single_coupon_crop_detected", action.reason)
    }

    @Test
    fun `multi crop plan carries selection execution telemetry`() {
        val action = useCase.planAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 3
        )

        assertTrue(action is SingleScanRouteAction.ShowMultiCouponSelection)
        assertEquals("multi_coupon_selection", action.executedStrategy)
        assertEquals("multiple_coupon_crops_detected", action.reason)
    }

    @Test
    fun `fallback plan preserves guarded fallback reason`() {
        val action = useCase.planAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 0
        )

        assertTrue(action is SingleScanRouteAction.TryLayoutThenGuardedFallback)
        assertEquals("layout_multi_coupon_probe", action.executedStrategy)
        assertEquals("no_coupon_crop_detected", action.reason)
    }

    @Test
    fun `zero detected crops routes through layout then guarded fallback`() {
        val decision = useCase.decideAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 0
        )

        assertTrue(decision is SingleScanRouteDecision.TryLayoutThenGuardedFallback)
        assertEquals(
            "no_coupon_crop_detected",
            (decision as SingleScanRouteDecision.TryLayoutThenGuardedFallback).reason
        )
    }

    @Test
    fun `one detected crop routes to single crop processing`() {
        val decision = useCase.decideAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 1
        )

        assertEquals(SingleScanRouteDecision.ProcessSingleCrop, decision)
    }

    @Test
    fun `multiple detected crops route to multi coupon selection`() {
        val decision = useCase.decideAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = 2
        )

        assertEquals(SingleScanRouteDecision.ShowMultiCouponSelection, decision)
    }
}
