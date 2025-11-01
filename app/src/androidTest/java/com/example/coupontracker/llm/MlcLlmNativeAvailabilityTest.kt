package com.example.coupontracker.llm

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.ui.activity.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlcLlmNativeAvailabilityTest {

    @Test
    fun packagedNativeLibraryIsAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext.applicationContext

        MlcLlmNative.resetForTests()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val loadResult = try {
                MlcLlmNative.loadLibrary(appContext)
            } catch (error: Throwable) {
                throw AssertionError(
                    "Failed to load mlc_llm_android native library from the packaged application",
                    error
                )
            }

            assertTrue("Native LLM library must load successfully", loadResult)

            if (!BuildConfig.DEBUG) {
                assertTrue(
                    "Native LLM library must be reported as available in production builds",
                    MlcLlmNative.isAvailable()
                )
            }
        } finally {
            scenario.close()
        }
    }
}
