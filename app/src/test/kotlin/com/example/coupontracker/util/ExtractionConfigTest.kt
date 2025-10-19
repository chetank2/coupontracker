package com.example.coupontracker.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.llm.MlcLlmNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun enablingAdvancedStrategiesRestoresPersistedSelection() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)
        assertTrue(ExtractionConfig.areAdvancedStrategiesEnabled())
        ExtractionConfig.setStrategy(ExtractionStrategy.LLM_FIRST)

        assertEquals(ExtractionStrategy.LLM_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)

        assertTrue(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.LLM_FIRST, ExtractionConfig.getStrategy())
    }

    @Test
    fun persistedAdvancedSelectionResumesWhenGuardToggledOn() {
        ExtractionConfig.runtimeAvailabilityChecker = { false }
        ExtractionConfig.init(context)
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .putString("extraction_strategy", ExtractionStrategy.HYBRID.name)
            .apply()

        ExtractionConfig.resetForTesting()
        ExtractionConfig.runtimeAvailabilityChecker = { false }
        ExtractionConfig.init(context)

        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.refreshRuntimeAvailability(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        assertTrue(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.HYBRID, ExtractionConfig.getStrategy())
    }

    @Test
    fun availableStrategiesIncludeAdvancedWhenLibraryReadyAndGuardEnabled() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        val strategies = ExtractionConfig.getAvailableStrategies()

        assertTrue(strategies.contains(ExtractionStrategy.LLM_FIRST))
        assertTrue(strategies.contains(ExtractionStrategy.HYBRID))
    }

    @Test
    fun runtimeAvailabilityAutomaticallyEnablesAdvancedStrategies() {
        ExtractionConfig.runtimeAvailabilityChecker = { true }
        ExtractionConfig.init(context)

        assertTrue(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.LLM_FIRST, ExtractionConfig.getStrategy())
    }

    private fun clearPreferences() {
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
