package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.util.TextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FieldValidationCoordinatorTest {
    private val textExtractor = TextExtractor()
    private val storeNameResolver = StoreNameResolver(StoreNameValidator())
    private val coordinator = FieldValidationCoordinator(textExtractor, storeNameResolver)

    @Test
    fun `refine replaces invalid store with structured candidate`() {
        val bundle = FieldValueBundle(
            storeName = "Unknown Store",
            description = "Flat 20% OFF on fashion",
            redeemCode = "SAVE20",
            expiryDateText = null
        )
        val structured = mapOf(
            FieldType.STORE_NAME to listOf(FieldCandidate("Myntra", 0.9f, "all_caps", null))
        )

        val summary = coordinator.refine(
            bundle,
            rawOcrText = "MYNTRA\nFlat 20% OFF on fashion\nUse code SAVE20",
            captureTimestamp = null,
            structuredCandidates = structured
        )

        assertEquals("Myntra", summary.fields.storeName)
        assertEquals("Myntra", summary.storeResolution.value)
        assertTrue(summary.issues.any { it.field == FieldType.STORE_NAME })
    }

    @Test
    fun `refine rejects llm store name when absent from OCR and keeps OCR fallback`() {
        val bundle = FieldValueBundle(
            storeName = "pm Vit",
            description = "Flat off for kids products",
            redeemCode = "PHGZQZG2DCY9WB",
            expiryDateText = null
        )

        val summary = coordinator.refine(
            bundle,
            rawOcrText = """
                Gritzo
                Flat off for kids products
                Code PHGZQZG2DCY9WB
            """.trimIndent(),
            captureTimestamp = null,
            structuredCandidates = mapOf(
                FieldType.STORE_NAME to listOf(FieldCandidate("Gritzo", 0.9f, "ocr_heading", null))
            )
        )

        assertEquals("Gritzo", summary.fields.storeName)
        assertEquals("Gritzo", summary.storeResolution.value)
        assertTrue(summary.issues.any { it.field == FieldType.STORE_NAME })
    }

    @Test
    fun `refine clears unsupported llm store name when no OCR fallback exists`() {
        val bundle = FieldValueBundle(
            storeName = "Aha",
            description = "20% off annual plan",
            redeemCode = "AHAPPE20",
            expiryDateText = null
        )

        val summary = coordinator.refine(
            bundle,
            rawOcrText = """
                Save 20 percent on annual plan
                Code AHAPPE20
            """.trimIndent(),
            captureTimestamp = null,
            structuredCandidates = emptyMap()
        )

        assertEquals(null, summary.fields.storeName)
        assertTrue(summary.storeResolution.needsAttention)
        assertTrue(summary.storeResolution.violations.contains("ocr_evidence_missing"))
    }

    @Test
    fun `refine fills missing description from text extractor`() {
        val bundle = FieldValueBundle(
            storeName = "Amazon",
            description = null,
            redeemCode = "AMZ100",
            expiryDateText = null
        )

        val summary = coordinator.refine(
            bundle,
            rawOcrText = "AMAZON\nGet Rs 100 cashback on first order\nUse code AMZ100",
            captureTimestamp = null,
            structuredCandidates = emptyMap()
        )

        assertNotNull(summary.fields.description)
        assertTrue(summary.issues.any { it.field == FieldType.DESCRIPTION })
    }

    @Test
    fun `refine supplies expiry from structured fallback`() {
        val bundle = FieldValueBundle(
            storeName = "Flipkart",
            description = "Flat 15% off",
            redeemCode = "FLIP15",
            expiryDateText = null
        )
        val structured = mapOf(
            FieldType.EXPIRY_DATE to listOf(FieldCandidate("2025-05-31", 0.85f, "absolute_date", null))
        )

        val summary = coordinator.refine(
            bundle,
            rawOcrText = "Flipkart Summer Sale\nValid till 31 May 2025",
            captureTimestamp = null,
            structuredCandidates = structured
        )

        assertEquals("2025-05-31", summary.fields.expiryDateText)
        assertTrue(summary.issues.any { it.field == FieldType.EXPIRY_DATE })
    }
}
