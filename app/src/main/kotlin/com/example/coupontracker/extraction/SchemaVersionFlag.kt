package com.example.coupontracker.extraction

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls whether the LLM extraction pipeline emits + accepts schema-v2
 * optional fields (redeemCodes, primaryRedeemCode, category, storeUrl,
 * paymentMethod, minimumPurchase, maximumDiscount, offerType).
 *
 * Default: `false`. Production behaviour is unchanged until flipped.
 *
 * When enabled:
 *  - PromptBuilder appends v2 fields as optional in the LLM prompt.
 *  - LocalLlmOcrService.enforceCanonicalFields permits v2 keys through.
 *  - parseLlmResponseToCouponInfo populates v2 fields onto the CouponInfo.
 *  - New Room columns from MIGRATION_13_14 receive non-null values.
 */
@Singleton
class SchemaVersionFlag(
    private val prefs: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isV2Enabled(): Boolean = prefs.getBoolean(KEY_V2_ENABLED, false)

    fun setV2Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_V2_ENABLED, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "coupon_schema_version"
        const val KEY_V2_ENABLED = "v2_enabled"
    }
}
