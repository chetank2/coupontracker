package com.example.coupontracker.extraction.capture

import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchCaptureOrchestratorTest {

    private val orchestrator = BatchCaptureOrchestrator(BatchCaptureItemProcessor())

    @Test
    fun `process returns all coupons and no error when every input succeeds`() = runTest {
        val pdfCoupon = coupon("PDF Store")
        val imageCoupon = coupon("Image Store")
        val progressUpdates = mutableListOf<BatchCaptureProgress>()

        val result = orchestrator.process(
            inputs = listOf(
                input("content://batch/doc.pdf", "doc.pdf", "application/pdf"),
                input("content://batch/image.png", "image.png", "image/png")
            ),
            decodeBitmap = { mockk(relaxed = true) },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { pdfCoupon },
            extractImageCoupons = { _, _ -> listOf(imageCoupon) },
            onItemFinished = { progressUpdates += it }
        )

        assertEquals(listOf(pdfCoupon, imageCoupon), result.coupons)
        assertNull(result.errorMessage)
        assertEquals(2, result.itemStatuses.size)
        assertTrue(result.itemStatuses.all { it.success })
        assertEquals(listOf(1, 2), progressUpdates.map { it.processedCount })
    }

    @Test
    fun `process preserves partial failure summary with failed display names`() = runTest {
        val coupon = coupon("PDF Store")

        val result = orchestrator.process(
            inputs = listOf(
                input("content://batch/doc.pdf", "doc.pdf", "application/pdf"),
                input("content://batch/missing.png", "missing.png", "image/png")
            ),
            decodeBitmap = { null },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { coupon },
            extractImageCoupons = { _, _ -> emptyList() }
        )

        assertEquals(listOf(coupon), result.coupons)
        assertEquals("Processed 1 of 2 files. Issues with: missing.png", result.errorMessage)
        assertEquals(2, result.itemStatuses.size)
        assertTrue(result.itemStatuses[0].success)
        assertFalse(result.itemStatuses[1].success)
        assertEquals("Unable to open image", result.itemStatuses[1].message)
    }

    @Test
    fun `process converts item exceptions into failed statuses and all failed summary`() = runTest {
        val result = orchestrator.process(
            inputs = listOf(
                input("content://batch/broken.pdf", "broken.pdf", "application/pdf")
            ),
            decodeBitmap = { null },
            trackBitmap = {},
            releaseBitmap = {},
            processPdf = { error("pdf failed") },
            extractImageCoupons = { _, _ -> emptyList() }
        )

        assertEquals(emptyList<Coupon>(), result.coupons)
        assertEquals("Failed to process any files.", result.errorMessage)
        assertEquals(1, result.itemStatuses.size)
        assertFalse(result.itemStatuses.single().success)
        assertEquals("pdf failed", result.itemStatuses.single().message)
    }

    private fun input(uriValue: String, displayName: String, mimeType: String): BatchCaptureInput {
        return BatchCaptureInput(
            uri = uri(uriValue),
            displayName = displayName,
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
