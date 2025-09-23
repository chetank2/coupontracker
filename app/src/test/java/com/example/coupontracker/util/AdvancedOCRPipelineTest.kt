package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdvancedOCRPipelineTest {

    private val context: Context = mockk(relaxed = true)
    private val modelService: ModelBasedOCRService = mockk()
    private val imagePreprocessor: ImagePreprocessor = mockk()
    private val bitmap: Bitmap = mockk(relaxed = true)

    init {
        every { bitmap.width } returns 1200
        every { bitmap.height } returns 800
        every { imagePreprocessor.preprocess(any()) } returns bitmap
    }

    @Before
    fun setupLogging() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @Test
    fun `processCouponImage returns valid data when model service succeeds`() = runTest {
        val couponInfo = CouponInfo(
            storeName = "Demo Store",
            description = "Flat discount",
            cashbackAmount = 15.0,
            redeemCode = "SAVE15"
        )
        coEvery { modelService.processCouponImage(any()) } returns couponInfo

        val pipeline = AdvancedOCRPipeline(context, modelService, imagePreprocessor)

        val result = pipeline.processCouponImage(bitmap)

        assertTrue(result.isValid())
        assertEquals("Demo Store", result.merchantName)
        assertEquals("SAVE15", result.code)
        assertEquals("15.0", result.amount)
    }

    @Test
    fun `processCouponImage falls back to error coupon when model returns invalid data`() = runTest {
        val invalidCouponInfo = CouponInfo(
            storeName = "",
            description = "",
            cashbackAmount = null,
            redeemCode = null
        )
        coEvery { modelService.processCouponImage(any()) } returns invalidCouponInfo

        val pipeline = AdvancedOCRPipeline(context, modelService, imagePreprocessor)

        val result = pipeline.processCouponImage(bitmap)

        assertEquals("Extraction Failed", result.merchantName)
        assertEquals("RETRY", result.code)
        assertTrue(result.description?.contains("Failed to extract coupon") == true)
    }

    @Test
    fun `processCouponImage surfaces OCR failure message`() = runTest {
        coEvery { modelService.processCouponImage(any()) } throws OCRProcessingException("model unreachable")

        val pipeline = AdvancedOCRPipeline(context, modelService, imagePreprocessor)

        val result = pipeline.processCouponImage(bitmap)

        assertEquals("Extraction Failed", result.merchantName)
        assertTrue(result.description?.contains("model unreachable") == true)
    }
}
