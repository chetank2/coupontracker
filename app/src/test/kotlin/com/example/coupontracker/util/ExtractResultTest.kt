package com.example.coupontracker.util

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for ExtractResult and ExtractionPolicy
 */
class ExtractResultTest {

    @Test
    fun `policy should route low quality LLM to MLKit`() {
        val lowQualityResult = ExtractResult.LowQuality(
            info = createMockCouponInfo(),
            reason = QualityReason.ALL_GENERIC_CONTENT,
            signals = createMockSignals(ExtractionStage.LLM)
        )
        
        val availableStages = setOf(ExtractionStage.MLKIT, ExtractionStage.TFLITE, ExtractionStage.REGEX)
        val nextStage = ExtractionPolicy.decideNext(lowQualityResult, availableStages)
        
        assertEquals(ExtractionStage.MLKIT, nextStage)
    }
    
    @Test
    fun `policy should route failed LLM to MLKit`() {
        val failedResult = ExtractResult.Failed(
            stage = ExtractionStage.LLM,
            error = Exception("Model timeout"),
            signals = createMockSignals(ExtractionStage.LLM)
        )
        
        val availableStages = setOf(ExtractionStage.MLKIT, ExtractionStage.TFLITE, ExtractionStage.REGEX)
        val nextStage = ExtractionPolicy.decideNext(failedResult, availableStages)
        
        assertEquals(ExtractionStage.MLKIT, nextStage)
    }
    
    @Test
    fun `policy should return null for good results`() {
        val goodResult = ExtractResult.Good(
            info = createMockCouponInfo(),
            signals = createMockSignals(ExtractionStage.LLM)
        )
        
        val availableStages = setOf(ExtractionStage.MLKIT, ExtractionStage.TFLITE, ExtractionStage.REGEX)
        val nextStage = ExtractionPolicy.decideNext(goodResult, availableStages)
        
        assertNull(nextStage)
    }
    
    @Test
    fun `policy should accept good results`() {
        val goodResult = ExtractResult.Good(
            info = createMockCouponInfo(),
            signals = createMockSignals(ExtractionStage.LLM)
        )
        
        assertTrue(ExtractionPolicy.isAcceptable(goodResult))
    }
    
    @Test
    fun `policy should accept low quality with useful info`() {
        val lowQualityResult = ExtractResult.LowQuality(
            info = createMockCouponInfo(storeName = "Amazon", redeemCode = "SAVE20"),
            reason = QualityReason.LOW_QUALITY_EXTRACTION,
            signals = createMockSignals(ExtractionStage.LLM)
        )
        
        assertTrue(ExtractionPolicy.isAcceptable(lowQualityResult))
    }
    
    @Test
    fun `policy should reject failed results`() {
        val failedResult = ExtractResult.Failed(
            stage = ExtractionStage.LLM,
            error = Exception("Complete failure")
        )
        
        assertFalse(ExtractionPolicy.isAcceptable(failedResult))
    }
    
    private fun createMockCouponInfo(
        storeName: String = "Test Store",
        redeemCode: String? = "TEST20"
    ): CouponInfo {
        return CouponInfo(
            storeName = storeName,
            description = "Test coupon",
            redeemCode = redeemCode,
            cashbackAmount = 100.0
        )
    }
    
    private fun createMockSignals(stage: ExtractionStage): ExtractionSignals {
        return ExtractionSignals(
            qualityScore = 75,
            fieldConfidences = mapOf("storeName" to 0.9f, "redeemCode" to 0.8f),
            processingTimeMs = 1000L,
            memoryUsageMB = 512f,
            stage = stage,
            nativeAvailable = true
        )
    }
}
