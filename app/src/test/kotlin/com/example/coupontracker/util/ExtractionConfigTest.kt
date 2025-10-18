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
    }

    @After
    fun tearDown() {
        MlcLlmNative.libraryLoader = originalLoader
        MlcLlmNative.resetForTests()
        ExtractionConfig.resetForTesting()
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
        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)
        ExtractionConfig.setStrategy(ExtractionStrategy.LLM_FIRST)

        assertEquals(ExtractionStrategy.LLM_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.resetForTesting()
        ExtractionConfig.init(context)

        assertTrue(ExtractionConfig.areAdvancedStrategiesEnabled())
        assertEquals(ExtractionStrategy.LLM_FIRST, ExtractionConfig.getStrategy())
    }

    @Test
    fun persistedAdvancedSelectionResumesWhenGuardToggledOn() {
        ExtractionConfig.init(context)
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .putString("extraction_strategy", ExtractionStrategy.HYBRID.name)
            .apply()

        ExtractionConfig.resetForTesting()
        ExtractionConfig.init(context)

        assertEquals(ExtractionStrategy.OCR_FIRST, ExtractionConfig.getStrategy())

        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        assertEquals(ExtractionStrategy.HYBRID, ExtractionConfig.getStrategy())
    }

    @Test
    fun availableStrategiesIncludeAdvancedWhenLibraryReadyAndGuardEnabled() {
        val stubLoader = object : MlcLlmNative.Companion.NativeLibraryLoader {
            override fun loadLibrary(name: String) {}
            override fun load(path: String) {}
        }
        MlcLlmNative.libraryLoader = stubLoader
        assertTrue(MlcLlmNative.loadLibrary(context))

        ExtractionConfig.init(context)
        ExtractionConfig.setAdvancedStrategiesEnabled(true)

        val strategies = ExtractionConfig.getAvailableStrategies()

        assertTrue(strategies.contains(ExtractionStrategy.LLM_FIRST))
        assertTrue(strategies.contains(ExtractionStrategy.HYBRID))
    }

    private fun clearPreferences() {
        context.getSharedPreferences("extraction_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
