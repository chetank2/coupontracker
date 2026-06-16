package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEvidenceValidatorTest {

    @Test
    fun `store is supported when exact brand appears in OCR`() {
        val rawOcr = """
            Gritzo
            Flat off for kids products
            Code PHGZQZG2DCY9WB
        """.trimIndent()

        assertTrue(OcrEvidenceValidator.isPhraseSupported("Gritzo", rawOcr))
    }

    @Test
    fun `store is rejected when brand is absent from OCR`() {
        val rawOcr = """
            Gritzo
            Flat off for kids products
            Code PHGZQZG2DCY9WB
        """.trimIndent()

        assertFalse(OcrEvidenceValidator.isPhraseSupported("pm Vit", rawOcr))
    }

    @Test
    fun `code is supported across spaces and punctuation`() {
        val rawOcr = "Use code PHGZQZG2 DCY9WB before checkout"

        assertTrue(OcrEvidenceValidator.isPhraseSupported("PHGZQZG2DCY9WB", rawOcr))
    }

    @Test
    fun `short generic fragments are not enough evidence`() {
        val rawOcr = "Flat off for kids products"

        assertFalse(OcrEvidenceValidator.isPhraseSupported("pm", rawOcr))
    }

    @Test
    fun `short store name is not supported only because it appears inside coupon code`() {
        val rawOcr = "Save 20 percent on annual plan Code AHAPPE20"

        assertFalse(OcrEvidenceValidator.isPhraseSupported("Aha", rawOcr))
    }
}
