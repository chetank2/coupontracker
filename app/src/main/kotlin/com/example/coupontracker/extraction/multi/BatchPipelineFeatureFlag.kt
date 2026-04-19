package com.example.coupontracker.extraction.multi

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature flag for routing batch extraction through CouponRegionPipeline.
 * Default is `false` so the existing BatchScannerViewModel detector loop
 * remains the production path. Flip to `true` (via SharedPreferences write
 * or a debug menu) once the pipeline path has been validated on-device.
 */
@Singleton
class BatchPipelineFeatureFlag(
    private val prefs: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "coupon_batch_pipeline_flag"
        const val KEY_ENABLED = "batch_pipeline_enabled"
    }
}
