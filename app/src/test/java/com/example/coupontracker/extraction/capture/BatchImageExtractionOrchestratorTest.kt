package com.example.coupontracker.extraction.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.extraction.multi.BatchPipelineFeatureFlag
import com.example.coupontracker.extraction.multi.CouponRegionPipeline
import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.MultiEngineOCR
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchImageExtractionOrchestratorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ocrFirstCouponExtractor = mockk<OcrFirstCouponExtractor>(relaxed = true)
    private val analyticsTracker = mockk<AnalyticsTracker>(relaxed = true)
    private val regionPipeline = mockk<CouponRegionPipeline>()
    private val batchPipelineFlag = BatchPipelineFeatureFlag(context).also {
        it.setEnabled(true)
    }
    private val orchestrator = BatchImageExtractionOrchestrator(
        context = context,
        ocrFirstCouponExtractor = ocrFirstCouponExtractor,
        analyticsTracker = analyticsTracker,
        regionPipeline = regionPipeline,
        batchPipelineFlag = batchPipelineFlag,
        batchRegionIsolationCoordinator = BatchRegionIsolationCoordinator(),
        batchRegionExtractionRunner = BatchRegionExtractionRunner()
    )

    @Test
    fun `extract sends isolated crop to pipeline before converting coupons`() = runTest {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val uri = Uri.parse("content://batch/image.png")
        val tracked = mutableListOf<Bitmap>()
        val released = mutableListOf<Bitmap>()
        var detectedBitmap: Bitmap? = null
        var pipelineCropSize: Pair<Int, Int>? = null

        coEvery { regionPipeline.extractFromCrops(any()) } coAnswers {
            val crops = firstArg<List<Bitmap>>()
            pipelineCropSize = crops.single().width to crops.single().height
            listOf(
                JSONObject()
                    .put(CouponSchemaKeys.STORE_NAME, "Crop Store")
                    .put(CouponSchemaKeys.DESCRIPTION, "Crop offer")
                    .put(CouponSchemaKeys.REDEEM_CODE, "CROP20")
            )
        }

        val result = orchestrator.extract(
            uri = uri,
            bitmap = bitmap,
            runOcr = {
                MultiEngineOCR.OCRResult.Success(
                    text = "Crop Store\nCode CROP20",
                    extractedInfo = emptyMap()
                )
            },
            detectRegions = { sourceBitmap, _ ->
                detectedBitmap = sourceBitmap
                listOf(
                    HybridCouponDetector.CouponRegion(
                        boundingBox = Rect(10, 20, 40, 55),
                        ocrText = "Crop Store\nCode CROP20",
                        confidence = 0.92f,
                        source = HybridCouponDetector.DetectionSource.FUSED
                    )
                )
            },
            trackBitmap = { tracked += it },
            releaseBitmap = { released += it }
        )

        assertEquals(1, result.size)
        assertEquals("Crop Store", result.single().storeName)
        assertEquals("Crop offer", result.single().description)
        assertEquals("CROP20", result.single().redeemCode)
        assertEquals(uri.toString(), result.single().imageUri)
        assertSame(bitmap, detectedBitmap)
        assertEquals(30 to 35, pipelineCropSize)
        assertEquals(1, tracked.size)
        assertEquals(tracked, released)
        assertTrue(tracked.single() !== bitmap)
        coVerify(exactly = 0) { ocrFirstCouponExtractor.extract(any(), any(), any()) }
    }
}
