package com.example.coupontracker.runtime

import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelStrategyConfig
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class DeviceTierPolicyTest {

    private fun cap(
        ram: Long = 4096, lowRam: Boolean = false, bat: Boolean = false, thermal: Int = 0,
        qwen: Boolean = true, gemma: Boolean = false, mmproj: Boolean = false, native: Boolean = true
    ) = DeviceCapability(ram, ram / 2, lowRam, bat, thermal, qwen, gemma, mmproj, native)

    @Test
    fun `LOW_END disables low-confidence retry`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 2048, lowRam = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }

    @Test
    fun `HIGH_END enables VLM_QWEN retry when mmproj present`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, mmproj = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN) }
    }

    @Test
    fun `HIGH_END without mmproj stays on TEXT_QWEN retry`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, mmproj = false), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }

    @Test
    fun `battery saver forces LOW_END regardless of RAM`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, bat = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }
}
