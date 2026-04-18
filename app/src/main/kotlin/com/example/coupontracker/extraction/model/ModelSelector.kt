package com.example.coupontracker.extraction.model

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a ModelRole to its configured CouponExtractionModel adapter.
 * Adapters are injected as a Set-multibinding via Hilt; construction-time
 * check rejects duplicate modes.
 */
@Singleton
class ModelSelector @Inject constructor(
    adapters: Set<@JvmSuppressWildcards CouponExtractionModel>,
    private val config: ModelStrategyConfig
) {

    private val byMode: Map<ModelMode, CouponExtractionModel>

    init {
        require(adapters.map { it.mode }.toSet().size == adapters.size) {
            "Multiple adapters registered for same ModelMode: " +
                adapters.groupBy { it.mode }.filter { it.value.size > 1 }.keys
        }
        byMode = adapters.associateBy { it.mode }
    }

    fun select(role: ModelRole): CouponExtractionModel {
        val mode = config.modeFor(role)
        return byMode[mode] ?: throw ModelSelectorException(role, mode)
    }

    fun availableModes(): Set<ModelMode> = byMode.keys
}
