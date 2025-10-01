package com.example.coupontracker.universal

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.universal.ExtractionSource.VISUAL_REGION
import com.example.coupontracker.universal.RegionType.CODE
import com.example.coupontracker.universal.RegionType.LOGO
import io.mockk.any
import io.mockk.coEvery
import io.mockk.firstArg
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalFieldDetectorTest {

    @Test
    fun detectFields_includesRegionOcrResults() = runTest {
        val patternLearner = mockk<PatternLearningEngine> {
            coEvery { getRelevantPatterns(any(), any()) } returns emptyList()
        }
        val layoutAnalyzer = mockk<UniversalLayoutAnalyzer> {
            coEvery { analyzeCouponStructure(any()) } returns CouponLayout(
                logoRegion = Region(RectF(0f, 0f, 40f, 20f), 0.9f, LOGO),
                codeRegion = Region(RectF(50f, 60f, 90f, 90f), 0.9f, CODE)
            )
        }
        val confidenceScorer = mockk<AdaptiveConfidenceScorer> {
            coEvery { scoreCandidate(any(), any()) } answers { firstArg<ExtractionCandidate>().confidence }
        }
        val ocrEngine = FakeOcrEngine()
        val detector = UniversalFieldDetector(patternLearner, layoutAnalyzer, confidenceScorer, ocrEngine)

        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)

        val results = detector.detectFields(bitmap, "", ExtractionContext())

        val storeCandidates = results[FieldType.STORE_NAME]
        val codeCandidates = results[FieldType.COUPON_CODE]

        assertTrue(
            "Expected store candidates to include normalized logo text",
            storeCandidates?.any { it.text == "Example Store" && it.source == VISUAL_REGION } == true
        )

        assertTrue(
            "Expected code candidates to include normalized code text",
            codeCandidates?.any { it.text == "SAVE20" && it.source == VISUAL_REGION } == true
        )
    }

    private class FakeOcrEngine : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): String {
            return when {
                bitmap.width == 40 && bitmap.height == 20 -> "  Example\nStore  "
                bitmap.width == 40 && bitmap.height == 30 -> " save20 "
                else -> ""
            }
        }

        override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> = emptyList()

        override fun isReady(): Boolean = true

        override fun release() {
            // No-op
        }
    }
}
