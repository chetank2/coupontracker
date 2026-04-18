package com.example.coupontracker.extraction.model

/**
 * Where in the pipeline a model is invoked. Config maps each role to a
 * `ModelMode`; the `ModelSelector` resolves that to a concrete adapter.
 */
enum class ModelRole {
    /** Main extraction path. Plan 2 ships with TEXT_QWEN as the default. */
    DEFAULT,
    /** Retried after the default path flags low confidence. Plan 5 wires VLM. */
    LOW_CONFIDENCE_RETRY,
    /** A/B experiment slot. Plan 4 wires Gemma text here. */
    EXPERIMENT,
    /** Hermetic benchmark slot (ReplayCouponModel). */
    BENCHMARK
}
