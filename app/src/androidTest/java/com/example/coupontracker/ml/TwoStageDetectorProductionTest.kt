package com.example.coupontracker.ml

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter

@RunWith(AndroidJUnit4::class)
class TwoStageDetectorProductionTest {

    private lateinit var detector: TwoStageDetector

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        detector = TwoStageDetector(context)
    }

    @After
    fun tearDown() {
        detector.cleanup()
    }

    @Test
    fun demoManifestInitializesWithoutInterpretersButProvidesDetections() {
        val modelInfo = detector.getModelInfo()
        val isInitialized = modelInfo["isInitialized"] as? Boolean ?: false
        assertTrue("Detector should report initialized when manifest is present", isInitialized)

        val stage1Interpreter = readPrivateField<Interpreter?>("stage1Interpreter")
        val stage2Interpreter = readPrivateField<Interpreter?>("stage2Interpreter")

        assertNull("Stage 1 interpreter should not initialize in stub/demo mode", stage1Interpreter)
        assertNull("Stage 2 interpreter should not initialize in stub/demo mode", stage2Interpreter)

        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val detections = detector.detectMultiCoupons(bitmap)

        assertTrue("Demo detections should be returned when demo_mode is true", detections.isNotEmpty())
        assertTrue(
            "Each detection should include code and benefit regions",
            detections.all { instance ->
                val fields = instance.fields
                fields.any { it.fieldType == FieldType.CODE_REGION } &&
                    fields.any { it.fieldType == FieldType.BENEFIT_REGION }
            }
        )

        detector.releaseInstances(detections)
        bitmap.recycle()
    }

    private fun <T> readPrivateField(fieldName: String): T {
        val field = TwoStageDetector::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(detector) as T
    }
}
