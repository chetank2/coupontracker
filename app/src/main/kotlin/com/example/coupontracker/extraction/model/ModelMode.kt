package com.example.coupontracker.extraction.model

/**
 * All model backends the roadmap may use. Not all values have adapters yet —
 * unmapped modes must throw IllegalStateException when selected. The enum
 * exists here (rather than being grown per plan) so feature code can switch
 * on it without later enum widening churn.
 */
enum class ModelMode {
    TEXT_QWEN,
    TEXT_GEMMA,
    VLM_QWEN,
    VLM_GEMMA,
    VLM_MINICPM,
    REMOTE_DEBUG_ONLY,
    BENCHMARK_REPLAY
}
