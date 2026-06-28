package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchCaptureItemProcessorTest {

    private val processor = BatchCaptureItemProcessor()

    @Test
    fun `pdf item routes to pdf processor without bitmap work`() = runTest {
        var decoded = false
        val coupon = coupon("PDF Store")

        val result = processor.process(
            input = input(mimeType = "application/pdf"),
            decodeBitmap = {
                decoded = true
                null
            },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { coupon },
            extractImageCoupons = { _, _ -> emptyList() }
        )

        assertTrue(result.success)
        assertEquals(1, result.couponsFound)
        assertSame(coupon, result.coupons.single())
        assertFalse(decoded)
    }

    @Test
    fun `unsupported item fails before decode`() = runTest {
        var decoded = false

        val result = processor.process(
            input = input(mimeType = "text/plain"),
            decodeBitmap = {
                decoded = true
                null
            },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { coupon("PDF Store") },
            extractImageCoupons = { _, _ -> emptyList() }
        )

        assertFalse(result.success)
        assertEquals("Unsupported file type", result.message)
        assertFalse(decoded)
    }

    @Test
    fun `decode failure returns unable to open image`() = runTest {
        val result = processor.process(
            input = input(mimeType = "image/png"),
            decodeBitmap = { null },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { coupon("PDF Store") },
            extractImageCoupons = { _, _ -> emptyList() }
        )

        assertFalse(result.success)
        assertEquals("Unable to open image", result.message)
    }

    @Test
    fun `image item releases bitmap after successful extraction`() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val extracted = listOf(coupon("Image Store"), coupon("Second Store"))
        val tracked = mutableListOf<Bitmap>()
        val released = mutableListOf<Bitmap>()

        val result = processor.process(
            input = input(mimeType = "image/jpeg"),
            decodeBitmap = { bitmap },
            trackBitmap = { tracked += it },
            releaseBitmap = { released += it },
            processPdf = { coupon("PDF Store") },
            extractImageCoupons = { _, imageBitmap ->
                assertSame(bitmap, imageBitmap)
                extracted
            }
        )

        assertTrue(result.success)
        assertEquals(2, result.couponsFound)
        assertEquals(extracted, result.coupons)
        assertEquals(listOf(bitmap), tracked)
        assertEquals(listOf(bitmap), released)
    }

    @Test
    fun `image item releases bitmap when extraction throws`() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val released = mutableListOf<Bitmap>()

        runCatching {
            processor.process(
                input = input(mimeType = "image/*"),
                decodeBitmap = { bitmap },
                trackBitmap = {},
                releaseBitmap = { released += it },
                processPdf = { coupon("PDF Store") },
                extractImageCoupons = { _, _ -> error("boom") }
            )
        }

        assertEquals(listOf(bitmap), released)
    }

    private fun input(mimeType: String): BatchCaptureInput {
        return BatchCaptureInput(
            uri = uri("content://batch/item"),
            displayName = "item",
            mimeType = mimeType
        )
    }

    private fun coupon(storeName: String): Coupon {
        return Coupon(
            storeName = storeName,
            description = "Description",
            redeemCode = null,
            imageUri = null
        )
    }

    private fun uri(value: String): Uri {
        return mockk<Uri>().also {
            every { it.toString() } returns value
        }
    }
}
