package com.example.coupontracker.extraction.model

import android.graphics.Bitmap

/**
 * Single entry point for producing canonical coupon JSON from a screenshot
 * and/or OCR text. Adapters wrap the underlying runtime (Qwen text, Gemma,
 * VLM, benchmark replay, …).
 */
interface CouponExtractionModel {

    val mode: ModelMode

    /**
     * Extract from OCR text. MUST return JSON conforming to
     * `CouponJsonContract.RECOGNIZED_KEYS` or throw.
     */
    suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult

    /**
     * Extract from an image (+ optional OCR text). Adapters that do not
     * support vision MUST throw UnsupportedOperationException from here.
     */
    suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult
}

data class ModelExtractionResult(
    val canonicalJson: String,
    val latencyMs: Long,
    val usedFallback: Boolean,
    val notes: List<String> = emptyList()
)
