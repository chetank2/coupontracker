package com.example.coupontracker.extraction

/**
 * Signals that the primary LLM-first pass cannot be executed (warmup or init failure).
 *
 * The progressive pipeline should catch this exception and continue with downstream
 * passes without attempting the LLM-first path again until the failure is cleared.
 */
class PassOneUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

