package com.example.coupontracker.ml

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TwoStageDetectorPaddingTest {

    private lateinit var detector: TwoStageDetector

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = TwoStageDetector(context, isDebugBuild = true, initializeOnCreate = false)
    }

    @After
    fun tearDown() {
        detector.cleanup()
    }

    @Test
    fun cropBitmap_appliesPaddingWithinImageBounds() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val detectionBox = RectF(2f, 2f, 40f, 40f)

        val cropResult = detector.cropBitmap(bitmap, detectionBox)

        val cropOriginX = detectionBox.left - cropResult.padding.left
        val cropOriginY = detectionBox.top - cropResult.padding.top
        val cropRight = detectionBox.right + cropResult.padding.right
        val cropBottom = detectionBox.bottom + cropResult.padding.bottom

        assertTrue("Crop should start within bitmap bounds", cropOriginX >= 0f && cropOriginY >= 0f)
        assertTrue(
            "Crop should end within bitmap bounds",
            cropRight <= bitmap.width.toFloat() && cropBottom <= bitmap.height.toFloat()
        )

        val expectedWidth = (cropRight - cropOriginX).roundToInt()
        val expectedHeight = (cropBottom - cropOriginY).roundToInt()
        assertEquals(expectedWidth, cropResult.bitmap.width)
        assertEquals(expectedHeight, cropResult.bitmap.height)
    }

    @Test
    fun adjustFieldCoordinates_removesPaddingOffsets() {
        val couponBox = RectF(20f, 20f, 60f, 80f)
        val padding = CropPadding(left = 12f, top = 8f, right = 6f, bottom = 4f)
        val fields = listOf(
            FieldDetection(
                fieldType = FieldType.CODE_REGION,
                boundingBox = RectF(10f, 15f, 30f, 35f),
                confidence = 0.9f
            )
        )

        val adjustedFields = detector.adjustFieldCoordinates(fields, couponBox, padding)
        val adjustedBox = adjustedFields.first().boundingBox

        val expectedLeft = couponBox.left - padding.left + fields.first().boundingBox.left
        val expectedTop = couponBox.top - padding.top + fields.first().boundingBox.top
        val expectedRight = couponBox.left - padding.left + fields.first().boundingBox.right
        val expectedBottom = couponBox.top - padding.top + fields.first().boundingBox.bottom

        assertEquals(expectedLeft, adjustedBox.left, 0.001f)
        assertEquals(expectedTop, adjustedBox.top, 0.001f)
        assertEquals(expectedRight, adjustedBox.right, 0.001f)
        assertEquals(expectedBottom, adjustedBox.bottom, 0.001f)

        assertTrue(adjustedBox.left >= couponBox.left - padding.left)
        assertTrue(adjustedBox.top >= couponBox.top - padding.top)
    }
}
