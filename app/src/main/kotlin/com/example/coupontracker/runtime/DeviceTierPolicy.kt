package com.example.coupontracker.runtime

import android.os.PowerManager
import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelStrategyConfig

object DeviceTierPolicy {

    fun tierFor(cap: DeviceCapability): DeviceTier = when {
        cap.isBatterySaver -> DeviceTier.LOW_END
        cap.isLowRamDevice || cap.totalRamMb <= 3072 -> DeviceTier.LOW_END
        cap.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> DeviceTier.LOW_END
        cap.totalRamMb >= 6144 && cap.nativeLibraryLoaded -> DeviceTier.HIGH_END
        else -> DeviceTier.MID
    }

    fun apply(cap: DeviceCapability, config: ModelStrategyConfig) {
        val tier = tierFor(cap)

        val defaultMode = ModelMode.TEXT_QWEN
        val retryMode = when (tier) {
            DeviceTier.LOW_END, DeviceTier.MID -> ModelMode.TEXT_QWEN
            DeviceTier.HIGH_END -> when {
                cap.mmprojPresent -> ModelMode.VLM_QWEN
                cap.gemmaTextModelPresent -> ModelMode.TEXT_GEMMA
                else -> ModelMode.TEXT_QWEN
            }
            DeviceTier.DEVELOPER -> ModelMode.VLM_QWEN
        }
        val experimentMode = if (cap.gemmaTextModelPresent && tier != DeviceTier.LOW_END)
            ModelMode.TEXT_GEMMA else ModelMode.TEXT_QWEN

        config.setModeFor(ModelRole.DEFAULT, defaultMode)
        config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, retryMode)
        config.setModeFor(ModelRole.EXPERIMENT, experimentMode)
        // BENCHMARK role intentionally left alone.
    }
}
