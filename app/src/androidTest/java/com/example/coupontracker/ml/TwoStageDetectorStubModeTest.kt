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
}
