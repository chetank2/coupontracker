package com.example.coupontracker.extraction.model

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class ModelSelectorTest {

    private fun adapter(mode: ModelMode): CouponExtractionModel =
        mockk<CouponExtractionModel>().also { every { it.mode } returns mode }

    @Test
    fun `select returns adapter matching configured mode`() {
        val qwen = adapter(ModelMode.TEXT_QWEN)
        val replay = adapter(ModelMode.BENCHMARK_REPLAY)
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.DEFAULT) } returns ModelMode.TEXT_QWEN
        every { config.modeFor(ModelRole.BENCHMARK) } returns ModelMode.BENCHMARK_REPLAY

        val selector = ModelSelector(adapters = setOf(qwen, replay), config = config)

        assertSame(qwen, selector.select(ModelRole.DEFAULT))
        assertSame(replay, selector.select(ModelRole.BENCHMARK))
    }

    @Test
    fun `select throws when mode unregistered`() {
        val qwen = adapter(ModelMode.TEXT_QWEN)
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY) } returns ModelMode.VLM_QWEN

        val selector = ModelSelector(adapters = setOf(qwen), config = config)

        val ex = assertThrows(ModelSelectorException::class.java) {
            selector.select(ModelRole.LOW_CONFIDENCE_RETRY)
        }
        assertEquals(ModelRole.LOW_CONFIDENCE_RETRY, ex.role)
        assertEquals(ModelMode.VLM_QWEN, ex.mode)
    }

    @Test
    fun `duplicate adapter modes rejected at construction`() {
        val a = adapter(ModelMode.TEXT_QWEN)
        val b = adapter(ModelMode.TEXT_QWEN)
        val config = mockk<ModelStrategyConfig>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            ModelSelector(adapters = linkedSetOf(a, b), config = config)
        }
    }
}
