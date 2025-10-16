package com.example.coupontracker.extraction.framework

import java.util.Date

/**
 * Field extraction framework provides a declarative pipeline for resolving
 * all coupon fields (store, description, code, expiry) without scattering
 * heuristics across unrelated classes.
 */
object FieldExtractionFramework {

    /**
     * Supported fields in the coupon schema.
     */
    enum class FieldType {
        STORE_NAME,
        DESCRIPTION,
        COUPON_CODE,
        EXPIRY_DATE
    }

    /**
     * Immutable context passed to every extractor.
     */
    data class ExtractionContext(
        val ocrLines: List<String>,
        val captureTimestamp: Date?,
        val metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Result returned by each strategy.
     */
    data class FieldResult(
        val fieldType: FieldType,
        val value: String,
        val confidence: Float,
        val sources: List<String> = emptyList(),
        val normalizationWarnings: List<String> = emptyList()
    )

    /**
     * Trace of how a field was resolved.
     */
    data class FieldTrace(
        val attempts: List<Attempt>,
        val finalResult: FieldResult?,
        val fallbackTriggered: Boolean
    ) {
        data class Attempt(
            val strategyName: String,
            val notes: String,
            val emittedResult: FieldResult?
        )
    }

    /**
     * Contract implemented by each extraction strategy.
     */
    fun interface FieldExtractionStrategy {
        fun extract(context: ExtractionContext, previousResult: FieldResult?): FieldResult?
    }

    /**
     * Registry entry describing a strategy and where it applies.
     */
    data class StrategyEntry(
        val name: String,
        val fieldType: FieldType,
        val priority: Int,
        val strategy: FieldExtractionStrategy,
        val allowFallback: Boolean = true
    )

    /**
     * Registry orchestrating strategy ordering.
     */
    class StrategyRegistry(entries: List<StrategyEntry>) {
        private val orderedEntries: Map<FieldType, List<StrategyEntry>> =
            entries.groupBy { it.fieldType }.mapValues { (_, list) ->
                list.sortedBy { it.priority }
            }

        fun listStrategies(fieldType: FieldType): List<StrategyEntry> =
            orderedEntries[fieldType].orEmpty()
    }

    /**
     * Observer that receives lifecycle events for analytics and debugging.
     */
    fun interface TraceListener {
        fun onFieldTrace(fieldType: FieldType, trace: FieldTrace)
    }

    /**
     * Primary orchestrator that runs strategies and records traces.
     */
    class Pipeline(
        private val registry: StrategyRegistry,
        private val listeners: List<TraceListener> = emptyList()
    ) {
        fun resolveField(
            fieldType: FieldType,
            context: ExtractionContext,
            existingResult: FieldResult? = null
        ): FieldResult? {
            val attempts = mutableListOf<FieldTrace.Attempt>()
            var currentResult = existingResult
            var fallbackTriggered = false

            for (entry in registry.listStrategies(fieldType)) {
                val result = entry.strategy.extract(context, currentResult)
                attempts += FieldTrace.Attempt(
                    strategyName = entry.name,
                    notes = if (result != null) "produced value" else "no value",
                    emittedResult = result
                )

                if (result != null) {
                    currentResult = when {
                        currentResult == null -> result
                        result.confidence >= currentResult.confidence -> result
                        else -> currentResult
                    }

                    if (!entry.allowFallback) {
                        break
                    }

                    fallbackTriggered = true
                }
            }

            val trace = FieldTrace(
                attempts = attempts,
                finalResult = currentResult,
                fallbackTriggered = fallbackTriggered
            )
            listeners.forEach { it.onFieldTrace(fieldType, trace) }
            return currentResult
        }
    }
}
