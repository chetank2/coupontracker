package com.example.coupontracker.extraction.model

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStrategyConfigTest {

    private fun prefsReturning(
        stored: Map<String, String> = emptyMap()
    ): SharedPreferences {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        stored.forEach { (k, v) ->
            every { prefs.getString(k, any()) } returns v
        }
        return prefs
    }

    @Test
    fun `defaults map DEFAULT to TEXT_QWEN and BENCHMARK to BENCHMARK_REPLAY`() {
        val config = ModelStrategyConfig(prefsReturning())
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.DEFAULT))
        assertEquals(ModelMode.BENCHMARK_REPLAY, config.modeFor(ModelRole.BENCHMARK))
    }

    @Test
    fun `LOW_CONFIDENCE_RETRY defaults to Gemma vision and EXPERIMENT defaults to Qwen text`() {
        val config = ModelStrategyConfig(prefsReturning())
        assertEquals(ModelMode.VLM_GEMMA, config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY))
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.EXPERIMENT))
    }

    @Test
    fun `stored mode overrides default`() {
        val config = ModelStrategyConfig(
            prefsReturning(mapOf("role.EXPERIMENT" to "TEXT_GEMMA"))
        )
        assertEquals(ModelMode.TEXT_GEMMA, config.modeFor(ModelRole.EXPERIMENT))
    }

    @Test
    fun `text retry preference is preserved to disable image retry`() {
        listOf(ModelMode.TEXT_QWEN, ModelMode.TEXT_GEMMA).forEach { mode ->
            val config = ModelStrategyConfig(
                prefsReturning(mapOf("role.LOW_CONFIDENCE_RETRY" to mode.name))
            )

            assertEquals(mode, config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY))
        }
    }

    @Test
    fun `vision retry preference is preserved`() {
        val config = ModelStrategyConfig(
            prefsReturning(mapOf("role.LOW_CONFIDENCE_RETRY" to "VLM_QWEN"))
        )

        assertEquals(ModelMode.VLM_QWEN, config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY))
    }

    @Test
    fun `setModeFor writes to prefs with correct key`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        val config = ModelStrategyConfig(prefs)

        config.setModeFor(ModelRole.EXPERIMENT, ModelMode.TEXT_GEMMA)

        verify { editor.putString("role.EXPERIMENT", "TEXT_GEMMA") }
        verify { editor.apply() }
    }

    @Test
    fun `invalid stored value falls back to default`() {
        val config = ModelStrategyConfig(
            prefsReturning(mapOf("role.DEFAULT" to "NOT_A_MODE"))
        )
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.DEFAULT))
    }
}
