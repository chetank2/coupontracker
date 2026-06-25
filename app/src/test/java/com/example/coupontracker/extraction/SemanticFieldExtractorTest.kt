package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticFieldExtractorTest {

    private val extractor = SemanticFieldExtractor()

    @Test
    fun `description extraction rejects saved coupon boilerplate`() = runTest {
        val context = ExtractionContext(
            imageUri = "test://coupon",
            ocrText = "Scratch card received on offer",
            ocrBlocks = emptyList(),
            metadata = emptyMap(),
            captureTimestamp = null
        )

        val results = extractor.extractFieldsSemantic(context, setOf(FieldType.DESCRIPTION))
        val descriptions = results[FieldType.DESCRIPTION].orEmpty()

        assertTrue(
            "Saved-offer boilerplate should not be a description, but found: ${descriptions.map { it.value }}",
            descriptions.none { it.value.equals("Scratch card received on offer", ignoreCase = true) }
        )
    }
}
