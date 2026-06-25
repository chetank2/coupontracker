package com.example.coupontracker.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.coupontracker.model.ModelPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun probe(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            pm.currentThermalStatus else 0

        val files = context.filesDir
        val qwenPresent = File(files, QWEN_MODEL_REL).exists()
        val gemmaPresent = File(files, GEMMA_TEXT_REL).exists()
        val gemmaVisionPresent = ModelPaths.isGemmaVisionInstalled(context)
        val mmprojPresent = File(files, MMPROJ_REL).exists()

        val nativeLoaded = runCatching {
            System.loadLibrary(NATIVE_LIBRARY_NAME); true
        }.getOrDefault(false)

        return DeviceCapability(
            totalRamMb = mem.totalMem / (1024 * 1024),
            availableRamMb = mem.availMem / (1024 * 1024),
            isLowRamDevice = am.isLowRamDevice,
            isBatterySaver = pm.isPowerSaveMode,
            thermalStatus = thermal,
            qwenModelPresent = qwenPresent,
            gemmaTextModelPresent = gemmaPresent,
            gemmaVisionModelPresent = gemmaVisionPresent,
            mmprojPresent = mmprojPresent,
            nativeLibraryLoaded = nativeLoaded
        )
    }

    companion object {
        const val QWEN_MODEL_REL = "qwen/model.bin"
        const val GEMMA_TEXT_REL = "gemma/gemma-2b-it.task"
        const val MMPROJ_REL = "minicpm/mmproj-model-f16.gguf"
        const val NATIVE_LIBRARY_NAME = "mlc_llm_jni"
    }
}
