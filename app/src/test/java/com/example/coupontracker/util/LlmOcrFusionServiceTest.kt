package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import com.example.coupontracker.ocr.OcrEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmOcrFusionServiceTest {

    @Test
    fun `fusion keeps OCR prefix extension of LLM code`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val ocrEngine = mockk<OcrEngine>()
        val service = LlmOcrFusionService(context, ocrEngine)

        coEvery { ocrEngine.recognize(any()) } returns "Use code PPVIRGIO"

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val llmResult = CouponInfo(redeemCode = "VIRGIO")

        val fused = service.fuseResults(bitmap, llmResult, brand = null)

        assertEquals("PPVIRGIO", fused.redeemCode)
    }
}
