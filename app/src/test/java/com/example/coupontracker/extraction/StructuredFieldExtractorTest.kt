package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
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
        assertTrue(
            "Expected expiry candidates to include the normalized date",
            expiryCandidates.any { it.value.contains("31 May") }
        )
    }
}
