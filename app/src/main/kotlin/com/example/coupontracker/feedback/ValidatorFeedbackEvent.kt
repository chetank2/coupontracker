package com.example.coupontracker.feedback

import com.example.coupontracker.data.model.FieldType

/**
 * Structured representation of a validator-driven feedback event.
 */
data class ValidatorFeedbackEvent(
    val eventType: EventType,
    val ocrText: String?,
    val fieldOutcomes: List<FieldOutcome>,
    val rationale: Map<String, Any?>,
    val metadata: Map<String, Any?>
) {
    enum class EventType {
        VALIDATOR_OVERRIDE,
        USER_CORRECTION
    }

    data class FieldOutcome(
        val field: FieldType,
        val originalValue: String?,
        val resolvedValue: String?,
        val confidence: Float?,
        val status: String,
        val ruleViolations: List<String>,
        val evidence: List<String>,
        val replacementSource: String?
    )
}
