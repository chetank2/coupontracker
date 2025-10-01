package com.example.coupontracker.extraction

import android.graphics.RectF
import com.example.coupontracker.data.model.FieldType

/**
 * Context for extraction that preserves all data throughout the pipeline.
 * Supports progressive refinement with multiple extraction attempts.
 */
data class ExtractionContext(
    val imageUri: String,
    val ocrText: String,
    val ocrBlocks: List<TextBlock> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val attempts: MutableList<ExtractionAttempt> = mutableListOf()
)

/**
 * Structured OCR text block with position and confidence
 */
data class TextBlock(
    val text: String,
    val bounds: RectF,
    val confidence: Float,
    val language: String? = null
)

/**
 * Record of a single extraction attempt in the progressive pipeline
 */
data class ExtractionAttempt(
    val passName: String,
    val strategy: String,
    val timestamp: Long,
    val durationMs: Long,
    val fieldsExtracted: Map<FieldType, FieldCandidate>,
    val confidence: Float,
    val reason: String?
)

/**
 * A candidate value for a field with provenance information
 */
data class FieldCandidate(
    val value: String,
    val confidence: Float,
    val source: String,
    val context: String?
)

