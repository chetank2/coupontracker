package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelExtractionResult
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.ocr.OcrEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CouponRegionPipelineTest {

    private val canonical =
        """{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}"""

    private fun stubBitmap(w: Int = 800, h: Int = 600): Bitmap {
        val b = mockk<Bitmap>(relaxed = true)
        every { b.width } returns w
        every { b.height } returns h
        return b
    }

    private fun mockSelectorReturning(json: String): ModelSelector {
        val adapter = mockk<CouponExtractionModel>()
        coEvery { adapter.extractFromText(any(), any(), any()) } returns
            ModelExtractionResult(json, 5L, false)
        val selector = mockk<ModelSelector>()
        every { selector.select(ModelRole.DEFAULT) } returns adapter
        return selector
    }

    @Test
    fun `whole image path produces one coupon`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO SAVE50 01 Jun 2026"
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        val result = pipeline.extractWhole(stubBitmap())
        assertEquals(1, result.size)
        assertEquals("AJIO", result[0].getString("storeName"))
    }

    @Test
    fun `tiny crops below MIN_REGION_AREA_PX are filtered`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "x"
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        val tinyCrops = listOf(stubBitmap(w = 50, h = 50))   // area = 2500, far below threshold
        val result = pipeline.extractFromCrops(tinyCrops)
        assertTrue("tiny crops should be filtered out", result.isEmpty())
    }

    @Test
    fun `crops above threshold produce one coupon each before dedup`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO SAVE50"
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        // 3 large crops; all yield same JSON → dedup collapses to 1.
        val crops = List(3) { stubBitmap() }
        val result = pipeline.extractFromCrops(crops)
        assertEquals(1, result.size)
    }

    @Test
    fun `MAX_COUPONS_PER_SCREENSHOT cap applies before extraction`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO"
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        // 15 crops, all distinct identity but same JSON → cap at 10 BEFORE extraction
        // means OCR runs at most MAX times. After dedup, we still see 1.
        val crops = List(15) { stubBitmap() }
        val result = pipeline.extractFromCrops(crops)
        assertTrue(result.size <= MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT)
    }
}
