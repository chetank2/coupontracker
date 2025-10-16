package com.example.coupontracker.extraction.framework

import com.example.coupontracker.extraction.framework.FieldExtractionFramework.FieldResult
import com.example.coupontracker.extraction.framework.FieldExtractionFramework.FieldType
import com.example.coupontracker.extraction.framework.FieldExtractionFramework.Pipeline
import com.example.coupontracker.extraction.framework.FieldExtractionFramework.StrategyEntry
import com.example.coupontracker.extraction.framework.FieldExtractionFramework.StrategyRegistry
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FieldExtractionFrameworkTest {

    private val context = FieldExtractionFramework.ExtractionContext(
        ocrLines = listOf("Flat 20% off", "Use code SAVE20", "Valid till 12 Nov"),
        captureTimestamp = Date()
    )

    @Test
    fun preferredStrategyWinsWhenItHasHigherConfidence() {
        val highConfidence = StrategyEntry(
            name = "model-store-detector",
            fieldType = FieldType.STORE_NAME,
            priority = 1,
            strategy = FieldExtractionFramework.FieldExtractionStrategy { _, _ ->
                FieldResult(FieldType.STORE_NAME, "Myntra", 0.92f, sources = listOf("ml"))
            }
        )
        val fallback = StrategyEntry(
            name = "regex-store-detector",
            fieldType = FieldType.STORE_NAME,
            priority = 2,
            strategy = FieldExtractionFramework.FieldExtractionStrategy { _, _ ->
                FieldResult(FieldType.STORE_NAME, "Now", 0.55f, sources = listOf("regex"))
            }
        )

        val pipeline = Pipeline(StrategyRegistry(listOf(highConfidence, fallback)))
        val result = pipeline.resolveField(FieldType.STORE_NAME, context)

        assertNotNull(result)
        assertEquals("Myntra", result!!.value)
        assertEquals(0.92f, result.confidence)
    }

    @Test
    fun fallbackAllowedWhenFirstStrategyProducesLowConfidence() {
        val first = StrategyEntry(
            name = "low-confidence-detection",
            fieldType = FieldType.COUPON_CODE,
            priority = 1,
            strategy = FieldExtractionFramework.FieldExtractionStrategy { _, _ ->
                FieldResult(FieldType.COUPON_CODE, "SAVE20", 0.40f)
            }
        )
        val second = StrategyEntry(
            name = "deterministic-code",
            fieldType = FieldType.COUPON_CODE,
            priority = 2,
            strategy = FieldExtractionFramework.FieldExtractionStrategy { _, previous ->
                if (previous != null && previous.confidence >= 0.6f) return@FieldExtractionStrategy previous
                FieldResult(FieldType.COUPON_CODE, "SAVE2024", 0.72f)
            }
        )

        val traces = mutableListOf<FieldExtractionFramework.FieldTrace>()
        val pipeline = Pipeline(
            registry = StrategyRegistry(listOf(first, second)),
            listeners = listOf { _, trace -> traces += trace }
        )

        val result = pipeline.resolveField(FieldType.COUPON_CODE, context)

        assertNotNull(result)
        assertEquals("SAVE2024", result!!.value)
        assertEquals(0.72f, result.confidence)
        assertFalse(traces.isEmpty())
        assertEquals(2, traces.single().attempts.size)
        assertEquals("deterministic-code", traces.single().attempts.last().strategyName)
    }

    @Test
    fun pipelineCanReturnNullWhenNoStrategyEmits() {
        val entry = StrategyEntry(
            name = "noop",
            fieldType = FieldType.DESCRIPTION,
            priority = 1,
            strategy = FieldExtractionFramework.FieldExtractionStrategy { _, _ -> null },
            allowFallback = false
        )
        val pipeline = Pipeline(StrategyRegistry(listOf(entry)))
        val result = pipeline.resolveField(FieldType.DESCRIPTION, context)

        assertNull(result)
    }
}
