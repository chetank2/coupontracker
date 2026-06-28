package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredFieldExtractorTest {

    private val extractor = StructuredFieldExtractor()

    @Test
    fun `detectFieldsStructured captures expiry date with comma separated month`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "Buy 2 Get 2 Free. Expires on 31 May, 2025. Use code SAVE",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.detectFieldsStructured(context)

        val expiryCandidates = results[FieldType.EXPIRY_DATE].orEmpty()
        println("DEBUG: Expiry candidates found: ${expiryCandidates.map { it.value }}")
        assertTrue(
            "Expected expiry candidates to include the ISO normalized date, but found: ${expiryCandidates.map { it.value }}",
            expiryCandidates.any { it.value == "2025-05-31" }
        )
    }

    @Test
    fun `store detection prioritizes real brand over watermark`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "Pastm rewards\nAha Annual Plan Offer",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.detectFieldsStructured(context)
        val storeCandidates = results[FieldType.STORE_NAME].orEmpty()

        println("DEBUG: Store candidates found: ${storeCandidates.map { it.value }}")
        assertTrue(
            "Expected store candidates to contain Aha, but found: ${storeCandidates.map { it.value }}",
            storeCandidates.any { it.value.contains("Aha", ignoreCase = true) }
        )

        assertTrue(
            "Watermark term Pastm should be filtered out, but found: ${storeCandidates.map { it.value }}",
            storeCandidates.none { it.value.equals("Pastm", ignoreCase = true) }
        )
    }

    @Test
    fun `store detection ignores status bar Pastm and keeps merchant brand`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = """
                8:20 Pastm M
                X
                Minimalist
                The Daily
                Radiance Ritual
                Flat ₹100 Off + ₹50 Cashback
                on Radiance Kit from beminimalist.co
                Code: MNPPRK100UAPR255QYSGZA COPY
            """.trimIndent(),
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.detectFieldsStructured(context)
        val storeCandidates = results[FieldType.STORE_NAME].orEmpty()

        println("DEBUG: Store candidates with status bar: ${storeCandidates.map { it.value to it.source }}")
        assertTrue(
            "Expected store candidates to include Minimalist, but found: ${storeCandidates.map { it.value }}",
            storeCandidates.any { it.value.equals("Minimalist", ignoreCase = true) }
        )
        assertTrue(
            "Status bar artifact Pastm should be filtered out, but found: ${storeCandidates.map { it.value }}",
            storeCandidates.none { it.value.equals("Pastm", ignoreCase = true) }
        )
    }

    @Test
    fun `store detection accepts brands with y vowel like XYXX`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "Exclusive XYXX voucher",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.detectFieldsStructured(context)
        val storeCandidates = results[FieldType.STORE_NAME].orEmpty()

        println("DEBUG: Store candidates for XYXX: ${storeCandidates.map { it.value }}")
        assertTrue(
            "Expected store candidates to include XYXX, but found: ${storeCandidates.map { it.value }}",
            storeCandidates.any { it.value == "XYXX" }
        )
    }

    @Test
    fun `code detection marks no code only with explicit evidence`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "IDFC FIRST Bank\nMonthly Interest\nNo code needed",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.detectFieldsStructured(context)
        val codeCandidates = results[FieldType.COUPON_CODE].orEmpty()

        assertTrue(
            "Expected explicit no-code evidence to produce NO_CODE_NEEDED, but found: ${codeCandidates.map { it.value }}",
            codeCandidates.any { it.value == "NO_CODE_NEEDED" }
        )
    }

    @Test
    fun `code detection does not mark no code from cashback or missing-code diagnostics`() = runTest {
        val cashbackContext = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "MEGA DEAL\n₹599 + ₹50 cashback on orders above ₹999",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )
        val diagnosticContext = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "Store offer\nNo code found in this OCR pass",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val cashbackCandidates = extractor.detectFieldsStructured(cashbackContext)[FieldType.COUPON_CODE].orEmpty()
        val diagnosticCandidates = extractor.detectFieldsStructured(diagnosticContext)[FieldType.COUPON_CODE].orEmpty()

        assertFalse(
            "Cashback copy alone should not produce NO_CODE_NEEDED, but found: ${cashbackCandidates.map { it.value }}",
            cashbackCandidates.any { it.value == "NO_CODE_NEEDED" }
        )
        assertFalse(
            "Missing-code diagnostics should not produce NO_CODE_NEEDED, but found: ${diagnosticCandidates.map { it.value }}",
            diagnosticCandidates.any { it.value == "NO_CODE_NEEDED" }
        )
    }
}
