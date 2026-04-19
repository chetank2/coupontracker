package com.example.coupontracker.extraction.retry

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelExtractionResult
import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.ModelSelectorException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRetryRunnerTest {

    private val complete =
        """{"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}"""

    private val missingCode =
        """{"storeName":"AJIO","description":"Flat 50% off","redeemCode":"unknown","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":true}"""

    private val vlmRecovers =
        """{"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}"""

    private fun newSelector(returns: CouponExtractionModel? = null): ModelSelector {
        val selector = mockk<ModelSelector>()
        if (returns == null) {
            every { selector.select(ModelRole.LOW_CONFIDENCE_RETRY) } throws
                ModelSelectorException(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN)
        } else {
            every { selector.select(ModelRole.LOW_CONFIDENCE_RETRY) } returns returns
        }
        return selector
    }

    @Test
    fun `complete JSON skips retry`() = runBlocking {
        val selector = mockk<ModelSelector>(relaxed = true)
        val runner = VlmRetryRunner(VlmRetryEvaluator(), selector)
        val (out, triggers) = runner.maybeRetry(complete, mockk(relaxed = true), "AJIO SAVE50 valid 01 Jun 2026", "p")
        assertEquals(complete, out)
        assertTrue(triggers.isEmpty())
        coVerify(exactly = 0) { selector.select(any()) }
    }

    @Test
    fun `triggers fire and merger swaps in VLM redeem code anchored in OCR`() = runBlocking {
        val adapter = mockk<CouponExtractionModel>()
        coEvery { adapter.extractFromImage(any(), any(), any()) } returns
            ModelExtractionResult(vlmRecovers, 5L, false)
        val runner = VlmRetryRunner(VlmRetryEvaluator(), newSelector(returns = adapter))

        val (out, triggers) = runner.maybeRetry(
            canonicalJson = missingCode,
            bitmap = mockk<Bitmap>(relaxed = true),
            ocrText = "AJIO SAVE50 deal",
            prompt = "p"
        )
        assertTrue("triggers fired", triggers.isNotEmpty())
        assertTrue(
            "merged JSON should adopt SAVE50",
            org.json.JSONObject(out).getString("redeemCode") == "SAVE50"
        )
    }

    @Test
    fun `unconfigured retry slot preserves original JSON`() = runBlocking {
        val runner = VlmRetryRunner(VlmRetryEvaluator(), newSelector(returns = null))

        val (out, triggers) = runner.maybeRetry(
            canonicalJson = missingCode,
            bitmap = mockk<Bitmap>(relaxed = true),
            ocrText = "anything",
            prompt = "p"
        )
        assertEquals(missingCode, out)
        assertTrue("triggers were still reported", triggers.isNotEmpty())
    }

    @Test
    fun `adapter throwing preserves original JSON`() = runBlocking {
        val adapter = mockk<CouponExtractionModel>()
        coEvery { adapter.extractFromImage(any(), any(), any()) } throws
            IllegalStateException("VLM crashed")
        val runner = VlmRetryRunner(VlmRetryEvaluator(), newSelector(returns = adapter))

        val (out, _) = runner.maybeRetry(missingCode, mockk(relaxed = true), "ocr", "p")
        assertEquals(missingCode, out)
    }
}
