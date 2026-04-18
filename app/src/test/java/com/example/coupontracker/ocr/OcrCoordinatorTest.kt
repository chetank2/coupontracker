package com.example.coupontracker.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCoordinatorTest {

    private val bitmap = mockk<Bitmap>(relaxed = true)

    private fun span(t: String) =
        OcrTextSpan(t, Rect(0, 0, 10, 10), 0.9f)

    @Test
    fun `MLKit result passes predicates so Tesseract never runs`() = runBlocking {
        val mlkit = mockk<MlKitOcrEngine>()
        coEvery { mlkit.recognize(bitmap) } returns "SAVE50 expires 01 Jun 2026 at Flipkart"
        coEvery { mlkit.recognizeWithBoxes(bitmap) } returns listOf(span("SAVE50"))

        val tesseract = mockk<TesseractOcrEngine>()
        val coordinator = OcrCoordinator(mlkit, tesseract, OcrFallbackPredicates.DEFAULT_CHAIN)

        val spans = coordinator.recognizeWithBoxes(bitmap)
        assertEquals(1, spans.size)
        coVerify(exactly = 0) { tesseract.recognize(any()) }
    }

    @Test
    fun `short MLKit text triggers Tesseract and merges`() = runBlocking {
        val mlkit = mockk<MlKitOcrEngine>()
        coEvery { mlkit.recognize(bitmap) } returns "hi"
        coEvery { mlkit.recognizeWithBoxes(bitmap) } returns listOf(span("hi"))

        val tesseract = mockk<TesseractOcrEngine>()
        coEvery { tesseract.recognize(bitmap) } returns "Flipkart SAVE50 01 Jun 2026"
        coEvery { tesseract.recognizeWithBoxes(bitmap) } returns listOf(span("Flipkart"))

        val coordinator = OcrCoordinator(mlkit, tesseract, OcrFallbackPredicates.DEFAULT_CHAIN)

        val spans = coordinator.recognizeWithBoxes(bitmap)
        assertTrue("Tesseract span must be merged in", spans.any { it.text == "Flipkart" })
        coVerify(exactly = 1) { tesseract.recognizeWithBoxes(any()) }
    }

    @Test
    fun `isReady is true if MLKit is ready`() {
        val mlkit = mockk<MlKitOcrEngine>().also { every { it.isReady() } returns true }
        val tesseract = mockk<TesseractOcrEngine>().also { every { it.isReady() } returns false }
        val coordinator = OcrCoordinator(mlkit, tesseract, emptyList())
        assertTrue(coordinator.isReady())
    }
}
