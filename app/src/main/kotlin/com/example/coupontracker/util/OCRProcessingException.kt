package com.example.coupontracker.util

/**
 * Signals that the OCR pipeline failed to produce usable structured data.
 */
class OCRProcessingException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
