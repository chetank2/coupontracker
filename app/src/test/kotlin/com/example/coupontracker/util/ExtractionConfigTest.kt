package com.example.coupontracker.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.llm.MlcLlmNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class ExtractionConfigTest {

    private lateinit var context: Context
    private lateinit var originalLoader: MlcLlmNative.Companion.NativeLibraryLoader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalLoader = MlcLlmNative.libraryLoader
        clearPreferences()
        MlcLlmNative.resetForTests()
        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { false }
    }

    @After
    fun tearDown() {
        MlcLlmNative.libraryLoader = originalLoader
        MlcLlmNative.resetForTests()
        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { false }
        clearPreferences()
    }

    @Test
    fun defaultStrategy_isOcrFirst() {
        ExtractionConfig.init(context)

        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())
        assertFalse(ExtractionConfig.areAdvancedStrategiesEnabled())
    }

    @Test
    fun enablingAdvancedStrategiesKeepsOcrFirst() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        assertFalse(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)

        assertFalse(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())
    }

    @Test
    fun unsupportedPersistedSelectionFallsBackToOcrFirst() {
        ExtractionConfig.runtimeAvailabilityChecker = { false }
        ExtractionConfig.init(context)
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .putString("extraction_strategy", "HYBRID")
            .apply()

        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { false }
        ExtractionConfig.init(context)

        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.refreshRuntimeAvailability(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        assertFalse(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())
    }

    @Test
    fun availableStrategiesOnlyIncludeOcrFirst() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        val strategies = ExtractionConfig.getAvailableStrategies()

        assertEquals(listOf(ExtractionStrategy.OCR_FIRST), strategies)
    }

    @Test
    fun runtimeAvailabilityDoesNotEnableCaptureModelStrategies() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)

        assertFalse(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())
    }

    @Test
    fun remoteUnsupportedStrategyKeepsOcrFirst() {
        ExtractionConfig.init(context)

        ExtractionConfig.updateFromRemoteConfig("LLM_FIRST")

        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())
    }

    private fun clearPreferences() {
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
