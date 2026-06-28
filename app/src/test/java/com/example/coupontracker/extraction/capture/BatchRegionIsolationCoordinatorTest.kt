package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.util.MultiEngineOCR
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRegionIsolationCoordinatorTest {

    private val coordinator = BatchRegionIsolationCoordinator()

    @Test
    fun `ocr failure returns review-safe crop isolation coupon`() = runTest {
        val result = coordinator.extract(
            uri = uri(),
            bitmap = bitmap(),
            runOcr = { MultiEngineOCR.OCRResult.Error("failed") },
            detectRegions = { _, _ -> error("detector should not run") },
            extractIsolatedRegions = { error("extractor should not run") }
        )

        val coupon = result.single()
        assertTrue(coupon.needsAttention)
        assertNull(coupon.redeemCode)
        assertEquals("BATCH_CROP_ISOLATION_FAILED", coupon.extractionSource)
        assertTrue(coupon.cleanupError.orEmpty().contains("ocr_failed_before_region_detection"))
    }

    @Test
    fun `fallback and full image regions are filtered before extraction`() = runTest {
        val extractedRegions = mutableListOf<List<HybridCouponDetector.CouponRegion>>()
        val isolated = region(Rect(20, 20, 160, 180), HybridCouponDetector.DetectionSource.OCR_ANCHOR_ONLY)
        val fallback = region(Rect(0, 0, 200, 200), HybridCouponDetector.DetectionSource.FALLBACK)

        val result = coordinator.extract(
            uri = uri(),
            bitmap = bitmap(),
            runOcr = { ocrSuccess() },
            detectRegions = { _, _ -> listOf(fallback, isolated) },
            extractIsolatedRegions = { regions ->
                extractedRegions += regions
                listOf(coupon("Isolated Store"))
            }
        )

        assertEquals("Isolated Store", result.single().storeName)
        assertEquals(listOf(listOf(isolated)), extractedRegions)
    }

    @Test
    fun `only fallback regions return review coupon instead of full image extraction`() = runTest {
        var extractorCalled = false

        val result = coordinator.extract(
            uri = uri(),
            bitmap = bitmap(),
            runOcr = { ocrSuccess() },
            detectRegions = { _, _ ->
                listOf(region(Rect(0, 0, 200, 200), HybridCouponDetector.DetectionSource.FALLBACK))
            },
            extractIsolatedRegions = {
                extractorCalled = true
                emptyList()
            }
        )

        assertEquals(1, result.size)
        assertTrue(result.single().needsAttention)
        assertTrue(result.single().cleanupError.orEmpty().contains("no_isolated_coupon_regions"))
        assertTrue(!extractorCalled)
    }

    @Test
    fun `empty isolated extraction returns review coupon`() = runTest {
        val result = coordinator.extract(
            uri = uri(),
            bitmap = bitmap(),
            runOcr = { ocrSuccess() },
            detectRegions = { _, _ ->
                listOf(region(Rect(20, 20, 160, 180), HybridCouponDetector.DetectionSource.FUSED))
            },
            extractIsolatedRegions = { emptyList() }
        )

        assertTrue(result.single().needsAttention)
        assertTrue(result.single().cleanupError.orEmpty().contains("isolated_region_extraction_failed"))
    }

    @Test
    fun `detector exception returns review coupon`() = runTest {
        val result = coordinator.extract(
            uri = uri(),
            bitmap = bitmap(),
            runOcr = { ocrSuccess() },
            detectRegions = { _, _ -> error("detector failed") },
            extractIsolatedRegions = { error("extractor should not run") }
        )

        assertTrue(result.single().needsAttention)
        assertTrue(result.single().cleanupError.orEmpty().contains("region_detection_exception"))
    }

    private fun bitmap(): Bitmap {
        return mockk {
            every { width } returns 200
            every { height } returns 200
        }
    }

    private fun uri(): Uri {
        return mockk<Uri>().also {
            every { it.toString() } returns "content://batch/coupon.png"
        }
    }

    private fun ocrSuccess(): MultiEngineOCR.OCRResult.Success {
        return MultiEngineOCR.OCRResult.Success("Store\nCode SAVE20", emptyMap())
    }

    private fun region(
        rect: Rect,
        source: HybridCouponDetector.DetectionSource
    ): HybridCouponDetector.CouponRegion {
        return HybridCouponDetector.CouponRegion(
            boundingBox = rect,
            ocrText = "Store\nCode SAVE20",
            confidence = 0.9f,
            source = source
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
}
