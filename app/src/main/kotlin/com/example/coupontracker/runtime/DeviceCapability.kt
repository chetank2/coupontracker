package com.example.coupontracker.runtime

data class DeviceCapability(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowRamDevice: Boolean,
    val isBatterySaver: Boolean,
    val thermalStatus: Int, // android.os.PowerManager.THERMAL_STATUS_*
    val qwenModelPresent: Boolean,
    val gemmaTextModelPresent: Boolean,
    val gemmaVisionModelPresent: Boolean,
    val mmprojPresent: Boolean,
    val nativeLibraryLoaded: Boolean
)
