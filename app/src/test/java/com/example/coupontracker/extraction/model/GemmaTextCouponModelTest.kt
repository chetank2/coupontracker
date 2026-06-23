package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.llm.gemma.GemmaRuntime
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaTextCouponModelTest {

    private val canonical =
        """{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}"""

    @Test
    fun `happy path returns canonical JSON from runtime`() = runBlocking {
        val runtime = mockk<GemmaRuntime>()
        coEvery { runtime.runTextInference(any(), any(), any()) } returns canonical

        val model = GemmaTextCouponModel(runtime)
        val result = model.extractFromText("ocr text", "prompt", grammar = null)

        assertEquals(ModelMode.TEXT_GEMMA, model.mode)
        JSONObject(result.canonicalJson).let { json ->
            assertEquals("AJIO", json.getString("storeName"))
            assertEquals("SAVE50", json.getString("redeemCode"))
            assertEquals("2026-06-01", json.getString("expiryDate"))
            assertEquals("ocr", json.getString("storeNameSource"))
        }
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `runtime returning null throws`() = runBlocking {
        val runtime = mockk<GemmaRuntime>()
        coEvery { runtime.runTextInference(any(), any(), any()) } returns null

        val model = GemmaTextCouponModel(runtime)
        val thrown = runCatching { model.extractFromText("ocr", "prompt", null) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `extractFromImage not supported`() = runBlocking {
        val model = GemmaTextCouponModel(mockk())
        model.extractFromImage(mockk<Bitmap>(relaxed = true), null, "prompt")
        Unit
    }
}
