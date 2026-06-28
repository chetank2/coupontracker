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
