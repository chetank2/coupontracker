package com.example.coupontracker.debug

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.universal.UniversalExtractionResult
import com.example.coupontracker.ui.viewmodel.FieldExtractionResult
import com.example.coupontracker.ui.viewmodel.MiniCpmProgress
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.ExtractionSignals
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.QualityReason
import com.example.coupontracker.util.RunPath
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionDebugScorerTest {

    private fun sampleCouponInfo(): CouponInfo {
        return CouponInfo(
            storeName = "Sample Store",
            description = "20% off",
            expiryDate = Date(),
            cashbackAmount = 20.0,
            redeemCode = "SAVE20"
        )
    }

    private fun sampleSignals(
        stage: ExtractionStage,
        quality: Int = 88,
        coverage: Float = 0.82f
    ): ExtractionSignals {
        return ExtractionSignals(
            qualityScore = quality,
            fieldConfidences = mapOf("ocrCoverage" to coverage),
            processingTimeMs = 500,
            memoryUsageMB = 12f,
            stage = stage,
            nativeAvailable = true,
            modelVersion = "test"
        )
    }

    @Test
    fun `LLM success keeps stages healthy`() {
        val result = ExtractResult.Good(
            info = sampleCouponInfo(),
            signals = sampleSignals(ExtractionStage.LLM, quality = 92)
        )

        val snapshot = ExtractionDebugScorer.fromLlmResult(result, source = "llm_first")

        assertEquals(92, snapshot.overallScore)
        assertEquals("llm_first", snapshot.source)
        assertNull(snapshot.primaryCause)
        val llmStage = snapshot.scoreFor(ExtractionComponent.LLM)
        assertEquals(StageStatus.HEALTHY, llmStage?.status)
        assertTrue(llmStage?.notes?.first()?.contains("LLM quality score") == true)
    }

    @Test
    fun `LLM low quality highlights OCR culprit`() {
        val result = ExtractResult.LowQuality(
            info = sampleCouponInfo(),
            reason = QualityReason.LOW_QUALITY_EXTRACTION,
            signals = sampleSignals(ExtractionStage.MLKIT, quality = 38, coverage = 0.3f)
        )

        val snapshot = ExtractionDebugScorer.fromLlmResult(result, source = "llm_first")

        assertEquals(ExtractionComponent.OCR, snapshot.primaryCause)
        val ocrStage = snapshot.scoreFor(ExtractionComponent.OCR)
        assertEquals(StageStatus.FAILED, ocrStage?.status)
        val llmStage = snapshot.scoreFor(ExtractionComponent.LLM)
        assertEquals(StageStatus.DEGRADED, llmStage?.status)
    }

    @Test
    fun `two stage fallback marks LLM as culprit`() {
        val runPath = RunPath(
            strategy = "LLM_FIRST",
            tried = mutableListOf("LLM", "FALLBACK_OCR"),
            final = "FALLBACK_OCR",
            totalTimeMs = 800
        )
        val baseResult = FieldExtractionResult(
            fields = mapOf("storeName" to "Sample Store"),
            miniCpmStatus = MiniCpmProgress.FALLBACK,
            runPath = runPath
        )

        val snapshot = ExtractionDebugScorer.fromFieldExtraction(baseResult, runPath)

        assertEquals(ExtractionComponent.LLM, snapshot.primaryCause)
        val detectorStage = snapshot.scoreFor(ExtractionComponent.DETECTOR)
        assertEquals(StageStatus.HEALTHY, detectorStage?.status)
        val llmStage = snapshot.scoreFor(ExtractionComponent.LLM)
        assertEquals(StageStatus.FAILED, llmStage?.status)
    }

    @Test
    fun `universal extraction with empty OCR text flags OCR`() {
        val coupon = Coupon(
            id = 1,
            storeName = "Sample Store",
            description = "20% off",
            cashbackAmount = 10.0,
            redeemCode = "SAVE",
            imageUri = null
        )
        val result = UniversalExtractionResult(
            coupon = coupon,
            confidence = 0.42f,
            extractedFields = emptyMap(),
            allCandidates = emptyMap(),
            success = true
        )

        val snapshot = ExtractionDebugScorer.fromUniversalResult(result, ocrTextEmpty = true)

        assertEquals(ExtractionComponent.OCR, snapshot.primaryCause)
        val ocrStage = snapshot.scoreFor(ExtractionComponent.OCR)
        assertEquals(StageStatus.FAILED, ocrStage?.status)
    }

    @Test
    fun `traditional OCR failure marks OCR stage`() {
        val snapshot = ExtractionDebugScorer.fromTraditionalOcr(emptyMap())

        assertEquals(ExtractionComponent.OCR, snapshot.primaryCause)
        val ocrStage = snapshot.scoreFor(ExtractionComponent.OCR)
        assertEquals(StageStatus.FAILED, ocrStage?.status)
        val llmStage = snapshot.scoreFor(ExtractionComponent.LLM)
        assertEquals(StageStatus.FAILED, llmStage?.status)
    }
}
