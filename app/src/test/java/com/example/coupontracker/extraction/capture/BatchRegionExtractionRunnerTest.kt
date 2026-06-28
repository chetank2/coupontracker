package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchRegionExtractionRunnerTest {

    private val runner = BatchRegionExtractionRunner()

    @Test
    fun `pipeline path receives isolated crops and releases them`() = runTest {
        val tracked = mutableListOf<Bitmap>()
        val released = mutableListOf<Bitmap>()
        var singleCalled = false

        val result = runner.extract(
            bitmap = bitmap(),
            couponRegions = listOf(region(Rect(10, 20, 40, 60))),
            usePipeline = true,
            trackBitmap = { tracked += it },
            releaseBitmap = { released += it },
            extractPipeline = { crops ->
                assertEquals(1, crops.size)
                assertEquals(30, crops.single().width)
                assertEquals(40, crops.single().height)
                listOf(coupon("Pipeline Store"))
            },
            extractSingleRegion = {
                singleCalled = true
                coupon("Single Store")
            }
        )

        assertEquals("Pipeline Store", result.single().storeName)
        assertFalse(singleCalled)
        assertEquals(1, tracked.size)
        assertEquals(tracked, released)
    }

    @Test
    fun `empty pipeline output falls back to per-region extraction`() = runTest {
        val tracked = mutableListOf<Bitmap>()
        val released = mutableListOf<Bitmap>()
        val singleCrops = mutableListOf<Bitmap>()

        val result = runner.extract(
            bitmap = bitmap(),
            couponRegions = listOf(region(Rect(0, 0, 25, 30))),
            usePipeline = true,
            trackBitmap = { tracked += it },
            releaseBitmap = { released += it },
            extractPipeline = { emptyList() },
            extractSingleRegion = { crop ->
                singleCrops += crop
                coupon("Fallback Store")
            }
        )

        assertEquals("Fallback Store", result.single().storeName)
        assertEquals(1, singleCrops.size)
        assertEquals(25, singleCrops.single().width)
        assertEquals(30, singleCrops.single().height)
        assertEquals(2, tracked.size)
        assertEquals(tracked, released)
    }

    @Test
    fun `invalid crop region does not invoke extractors`() = runTest {
        val tracked = mutableListOf<Bitmap>()
        var pipelineCalled = false
        var singleCalled = false

        val result = runner.extract(
            bitmap = bitmap(),
            couponRegions = listOf(region(Rect(20, 20, 20, 50))),
            usePipeline = false,
            trackBitmap = { tracked += it },
            releaseBitmap = {},
            extractPipeline = {
                pipelineCalled = true
                emptyList()
            },
            extractSingleRegion = {
                singleCalled = true
                coupon("Unexpected")
            }
        )

        assertTrue(result.isEmpty())
        assertTrue(tracked.isEmpty())
        assertFalse(pipelineCalled)
        assertFalse(singleCalled)
    }

    @Test
    fun `per-region exception releases crop and continues`() = runTest {
        val tracked = mutableListOf<Bitmap>()
        val released = mutableListOf<Bitmap>()
        val regions = listOf(
            region(Rect(0, 0, 20, 20)),
            region(Rect(25, 25, 45, 45))
        )

        val result = runner.extract(
            bitmap = bitmap(),
            couponRegions = regions,
            usePipeline = false,
            trackBitmap = { tracked += it },
            releaseBitmap = { released += it },
            extractPipeline = { emptyList() },
            extractSingleRegion = { crop ->
                if (crop === tracked.first()) error("first crop failed")
                coupon("Second Store")
            }
        )

        assertEquals("Second Store", result.single().storeName)
        assertEquals(2, tracked.size)
        assertEquals(2, released.size)
        assertSame(tracked[0], released[0])
        assertSame(tracked[1], released[1])
    }

    private fun bitmap(): Bitmap {
        return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    }

    private fun region(rect: Rect): HybridCouponDetector.CouponRegion {
        return HybridCouponDetector.CouponRegion(
            boundingBox = rect,
            ocrText = "Store\nCode SAVE20",
            confidence = 0.9f,
            source = HybridCouponDetector.DetectionSource.FUSED
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
