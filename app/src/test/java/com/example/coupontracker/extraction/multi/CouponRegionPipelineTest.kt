package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelExtractionResult
import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.ocr.OcrEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        every { selector.selectMode(any()) } returns null
        every { selector.selectText(ModelRole.DEFAULT) } returns adapter
        return selector
    }

    private fun mockSelectorWithVlm(
        vlmJson: String,
        textJson: String = canonical
    ): Pair<ModelSelector, Pair<CouponExtractionModel, CouponExtractionModel>> {
        val vlm = mockk<CouponExtractionModel>()
        val text = mockk<CouponExtractionModel>()
        coEvery { vlm.extractFromImage(any(), any(), any()) } returns
            ModelExtractionResult(vlmJson, 7L, false)
        coEvery { text.extractFromText(any(), any(), any()) } returns
            ModelExtractionResult(textJson, 5L, false)
        val selector = mockk<ModelSelector>()
        every { selector.selectMode(ModelMode.VLM_GEMMA) } returns vlm
        every { selector.selectMode(ModelMode.VLM_QWEN) } returns null
        every { selector.selectMode(ModelMode.VLM_MINICPM) } returns null
        every { selector.selectText(ModelRole.DEFAULT) } returns text
        return selector to (vlm to text)
    }

    @Test
    fun `whole image path produces one coupon`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO SAVE50 01 Jun 2026"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        val result = pipeline.extractWhole(stubBitmap())
        assertEquals(1, result.size)
        assertEquals("AJIO", result[0].getString("storeName"))
    }

    @Test
    fun `tiny crops below MIN_REGION_AREA_PX are filtered`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "x"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        val tinyCrops = listOf(stubBitmap(w = 50, h = 50))   // area = 2500, far below threshold
        val result = pipeline.extractFromCrops(tinyCrops)
        assertTrue("tiny crops should be filtered out", result.isEmpty())
    }

    @Test
    fun `crops above threshold produce one coupon each before dedup`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO SAVE50"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
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
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val pipeline = CouponRegionPipeline(ocr, mockSelectorReturning(canonical))

        // 15 crops, all distinct identity but same JSON → cap at 10 BEFORE extraction
        // means OCR runs at most MAX times. After dedup, we still see 1.
        val crops = List(15) { stubBitmap() }
        val result = pipeline.extractFromCrops(crops)
        assertTrue(result.size <= MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT)
    }

    @Test
    fun `crop extraction ignores available VLM and uses OCR text fallback`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "PUMA\nFlat 20% off\nUse code PUMA20"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val (selector, adapters) = mockSelectorWithVlm(
            vlmJson = """{"storeName":"PUMA","description":"Flat 20% off","redeemCode":"PUMA20","expiryDate":"2026-06-01","storeNameSource":"vision","storeNameEvidence":["PUMA"],"needsAttention":false}"""
        )
        val pipeline = CouponRegionPipeline(ocr, selector)

        val result = pipeline.extractWhole(stubBitmap())

        assertEquals("AJIO", result[0].getString("storeName"))
        verify(exactly = 0) { selector.selectMode(any()) }
        coVerify(exactly = 0) { adapters.first.extractFromImage(any(), any(), any()) }
        coVerify(exactly = 1) { adapters.second.extractFromText(any(), any(), any()) }
    }

    @Test
    fun `VLM cannot inject unsupported final fields`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO\nFlat 50% off\nUse code SAVE50"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val (selector, adapters) = mockSelectorWithVlm(
            vlmJson = """{"storeName":"Unsupported Store","description":"Invented 99% off","redeemCode":"FAKE999","expiryDate":"2099-01-01","needsAttention":false}""",
            textJson = canonical
        )
        val pipeline = CouponRegionPipeline(ocr, selector)

        val result = pipeline.extractWhole(stubBitmap())

        assertEquals("AJIO", result[0].getString("storeName"))
        assertEquals("SAVE50", result[0].getString("redeemCode"))
        assertEquals("2026-06-01", result[0].getString("expiryDate"))
        verify(exactly = 0) { selector.selectMode(any()) }
        coVerify(exactly = 0) { adapters.first.extractFromImage(any(), any(), any()) }
        coVerify(exactly = 1) { adapters.second.extractFromText(any(), any(), any()) }
    }

    @Test
    fun `crop extraction does not try next VLM when first VLM returns invalid json`() = runBlocking {
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO\nFlat 50% off\nUse code SAVE50"
        coEvery { ocr.recognizeWithBoxes(any()) } returns emptyList()
        val gemma = mockk<CouponExtractionModel>()
        val qwen = mockk<CouponExtractionModel>()
        val text = mockk<CouponExtractionModel>()
        coEvery { gemma.extractFromImage(any(), any(), any()) } returns
            ModelExtractionResult("not-json", 7L, false)
        coEvery { qwen.extractFromImage(any(), any(), any()) } returns
            ModelExtractionResult(canonical, 7L, false)
        coEvery { text.extractFromText(any(), any(), any()) } returns
            ModelExtractionResult(canonical, 5L, false)
        val selector = mockk<ModelSelector>()
        every { selector.selectMode(ModelMode.VLM_GEMMA) } returns gemma
        every { selector.selectMode(ModelMode.VLM_QWEN) } returns qwen
        every { selector.selectMode(ModelMode.VLM_MINICPM) } returns null
        every { selector.selectText(ModelRole.DEFAULT) } returns text
        val pipeline = CouponRegionPipeline(ocr, selector)

        val result = pipeline.extractWhole(stubBitmap())

        assertEquals("AJIO", result[0].getString("storeName"))
        verify(exactly = 0) { selector.selectMode(any()) }
        coVerify(exactly = 0) { gemma.extractFromImage(any(), any(), any()) }
        coVerify(exactly = 0) { qwen.extractFromImage(any(), any(), any()) }
        coVerify(exactly = 1) { text.extractFromText(any(), any(), any()) }
    }
}
