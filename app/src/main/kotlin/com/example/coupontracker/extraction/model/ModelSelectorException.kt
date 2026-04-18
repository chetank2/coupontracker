package com.example.coupontracker.extraction.model

/**
 * Thrown when `ModelSelector.select(role)` cannot resolve an adapter because
 * the mode chosen for the role has not been registered (e.g. config picks
 * VLM_QWEN before Plan 5 has landed the adapter).
 */
class ModelSelectorException(
    val role: ModelRole,
    val mode: ModelMode,
    message: String = "No adapter registered for role=$role mode=$mode"
) : IllegalStateException(message)
