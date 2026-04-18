package com.example.coupontracker.extraction.model

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Role → ModelMode mapping backed by SharedPreferences.
 * Used by `ModelSelector` to resolve which adapter to invoke per role.
 */
@Singleton
class ModelStrategyConfig(
    private val prefs: SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun modeFor(role: ModelRole): ModelMode {
        val raw = prefs.getString(keyFor(role), null) ?: return defaultFor(role)
        return runCatching { ModelMode.valueOf(raw) }.getOrDefault(defaultFor(role))
    }

    fun setModeFor(role: ModelRole, mode: ModelMode) {
        prefs.edit().putString(keyFor(role), mode.name).apply()
    }

    private fun keyFor(role: ModelRole): String = "role.${role.name}"

    private fun defaultFor(role: ModelRole): ModelMode = when (role) {
        ModelRole.DEFAULT -> ModelMode.TEXT_QWEN
        ModelRole.LOW_CONFIDENCE_RETRY -> ModelMode.TEXT_QWEN
        ModelRole.EXPERIMENT -> ModelMode.TEXT_QWEN
        ModelRole.BENCHMARK -> ModelMode.BENCHMARK_REPLAY
    }

    companion object {
        const val PREFS_NAME = "coupon_model_strategy"
    }
}
