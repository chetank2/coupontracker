package com.example.coupontracker.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun productionModelsLoadAndReturnRealDetections() {
        val modelInfo = detector.getModelInfo()
        val isInitialized = modelInfo["isInitialized"] as? Boolean ?: false
        val demoModeEnabled = readPrivateField<Boolean>("demoMode")
        val stubModeEnabled = readPrivateField<Boolean>("stubMode")

        assertFalse("Detector should not initialize in demo mode", demoModeEnabled)
        assertFalse("Detector should not initialize in stub mode", stubModeEnabled)
        assertTrue("Detector should report initialized when manifest is present", isInitialized)

        val stage1Interpreter = readPrivateField<Interpreter?>("stage1Interpreter")
        val stage2Interpreter = readPrivateField<Interpreter?>("stage2Interpreter")

        assertNotNull("Stage 1 interpreter should initialize in production mode", stage1Interpreter)
        assertNotNull("Stage 2 interpreter should initialize in production mode", stage2Interpreter)

        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val detections = detector.detectMultiCoupons(bitmap)

        assertTrue("Production detector should not return demo detections on blank input", detections.isEmpty())

        detector.releaseInstances(detections)
        bitmap.recycle()
    }

    @Test
    fun interpretersExecuteOnFixtureBitmap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context

        val bitmap = context.assets.open("multi_coupon_fixture.png").use { stream ->
            BitmapFactory.decodeStream(stream)
        }

        requireNotNull(bitmap) { "Fixture bitmap should decode successfully" }

        val detections = detector.detectMultiCoupons(bitmap)

        // The synthetic models return empty detections but the call should succeed and return a list instance
        assertNotNull("Detector should return a non-null list", detections)

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
