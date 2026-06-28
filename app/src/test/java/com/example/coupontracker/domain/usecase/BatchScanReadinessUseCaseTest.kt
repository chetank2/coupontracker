package com.example.coupontracker.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchScanReadinessUseCaseTest {

    private val useCase = BatchScanReadinessUseCase()

    @Test
    fun `ready when two stage detector is available`() {
        val decision = useCase.decide(
            twoStageDetectorAvailable = true,
            fallbackDetectorAvailable = false,
            detectorInitErrorMessage = "ignored"
        )

        assertEquals(BatchScanReadinessDecision.Ready, decision)
    }

    @Test
    fun `uses ocr anchor fallback when only fallback detector is available`() {
        val decision = useCase.decide(
            twoStageDetectorAvailable = false,
            fallbackDetectorAvailable = true,
            detectorInitErrorMessage = "model missing"
        )

        assertTrue(decision is BatchScanReadinessDecision.UseOcrAnchorFallback)
        decision as BatchScanReadinessDecision.UseOcrAnchorFallback
        assertEquals("ocr_anchor_fallback", decision.telemetryReason)
        assertEquals("Multi-coupon detection disabled – using OCR anchor fallback", decision.notice)
    }

    @Test
    fun `aborts when no detector path is available`() {
        val decision = useCase.decide(
            twoStageDetectorAvailable = false,
            fallbackDetectorAvailable = false,
            detectorInitErrorMessage = "detector init failed"
        )

        assertTrue(decision is BatchScanReadinessDecision.Abort)
        decision as BatchScanReadinessDecision.Abort
        assertEquals("no_detector_or_fallback", decision.telemetryReason)
        assertEquals("detector init failed", decision.message)
    }
}
