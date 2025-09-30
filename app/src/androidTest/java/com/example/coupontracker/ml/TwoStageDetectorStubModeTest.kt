package com.example.coupontracker.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwoStageDetectorStubModeTest {

    @Test
    fun stubModeProductionBuildThrows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val exception = assertThrows(IllegalStateException::class.java) {
            TwoStageDetector(context, isDebugBuild = false)
        }

        val message = exception.message ?: ""
        assertTrue(
            "Expected error message to mention stub_mode flag",
            message.contains("stub_mode", ignoreCase = true)
        )
    }

    @Test
    fun initializeModelsThrowsWhenInvokedManuallyInProduction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val detector = TwoStageDetector(
            context = context,
            isDebugBuild = false,
            initializeOnCreate = false
        )

        val initializeMethod = TwoStageDetector::class.java.getDeclaredMethod("initializeModels")
        initializeMethod.isAccessible = true

        val exception = assertThrows(IllegalStateException::class.java) {
            try {
                initializeMethod.invoke(detector)
            } catch (invocationException: java.lang.reflect.InvocationTargetException) {
                val cause = invocationException.cause
                if (cause is IllegalStateException) {
                    throw cause
                }
                throw invocationException
            }
        }

        val message = exception.message ?: ""
        assertTrue(
            "Expected manual initialization to fail for stub_mode manifest",
            message.contains("stub_mode", ignoreCase = true)
        )
    }
}
