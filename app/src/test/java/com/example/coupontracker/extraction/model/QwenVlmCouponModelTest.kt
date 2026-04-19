package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.llm.LlmRuntimeManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QwenVlmCouponModelTest {

    private val canonical =
        """{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"vision","storeNameEvidence":["AJIO logo"],"needsAttention":false}"""

    @Test
    fun `happy path forwards to runtime and enforces contract`() = runBlocking {
        val runtime = mockk<LlmRuntimeManager>()
        coEvery { runtime.runInference(any(), any()) } returns canonical

        val model = QwenVlmCouponModel(runtime)
        val result = model.extractFromImage(mockk<Bitmap>(relaxed = true), "ocr SAVE50", "prompt")

        assertEquals(ModelMode.VLM_QWEN, model.mode)
        assertEquals(canonical, result.canonicalJson)
    }

    @Test(expected = NotImplementedError::class)
    fun `extractFromText throws`() = runBlocking {
        val model = QwenVlmCouponModel(mockk())
        model.extractFromText("ocr", "prompt", null)
        Unit
    }
}
