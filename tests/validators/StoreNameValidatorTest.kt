package com.example.coupontracker.extraction.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreNameValidatorTest {
    private val validator = StoreNameValidator(TestBrandLexicon.create())

    @Test
    fun `cta stopwords are rejected even when surrounded by noise`() {
        val assessment = validator.assessCandidate(
            value = "*** Shop Now ***",
            description = "Shop now for festival deals",
            redeemCode = "FEST100",
            source = "ocr"
        )

        assertTrue(assessment.issues.contains("cta_stopword"))
        assertFalse("CTA heavy candidates must never be accepted", assessment.isAccepted)
        assertTrue("CTA heavy candidates should always need attention", assessment.needsAttention)
    }

    @Test
    fun `lexicon backed brands earn acceptance through multiple signals`() {
        val assessment = validator.assessCandidate(
            value = "Myntra",
            description = "Myntra End of Reason Sale",
            redeemCode = "MYNTRA20",
            source = "structured"
        )

        assertTrue("Known brands should be accepted", assessment.isAccepted)
        assertEquals("Myntra", assessment.canonical)
        val categories = assessment.signals.map { it.category }.toSet()
        assertTrue("Expected lexicon signal", categories.contains("lexicon"))
        assertTrue("Expected heuristic signal", categories.contains("heuristic"))
        assertTrue("Expected structured source signal", categories.contains("source"))
        assertTrue("Accepted brands still need a second high confidence signal", assessment.needsAttention)
        assertEquals(1, assessment.highConfidenceCount)
    }

    @Test
    fun `duplicate fields raise cross domain issues`() {
        val assessment = validator.assessCandidate(
            value = "SAVE20",
            description = "SAVE20",
            redeemCode = "SAVE20",
            source = "ocr"
        )

        assertTrue(assessment.issues.contains("duplicate_code"))
        assertTrue(assessment.issues.contains("duplicate_description"))
        assertFalse("Duplicate tokens cannot be accepted", assessment.isAccepted)
    }

    @Test
    fun `null or blank store names always require attention`() {
        val blankAssessment = validator.assessCandidate(
            value = "   ",
            description = "Seasonal offer",
            redeemCode = "FALL50",
            source = "ocr"
        )
        assertTrue(blankAssessment.issues.contains("empty"))
        assertFalse(blankAssessment.isAccepted)
        assertTrue(blankAssessment.needsAttention)
        assertEquals(null, blankAssessment.original)

        val nullAssessment = validator.assessCandidate(
            value = null,
            description = "Mega Sale",
            redeemCode = "SALE50",
            source = "structured"
        )
        assertTrue(nullAssessment.issues.contains("empty"))
        assertFalse(nullAssessment.isAccepted)
        assertTrue(nullAssessment.needsAttention)
    }

    @Test
    fun `single signal fallbacks never pass acceptance gate`() {
        val assessment = validator.assessCandidate(
            value = "Shop",
            description = "Daily shop deals",
            redeemCode = "SHOP2024",
            source = "ocr"
        )

        assertTrue("Generic labels should be flagged", assessment.issues.contains("generic"))
        assertFalse("Generic fallback must not be accepted", assessment.isAccepted)
    }

    @Test
    fun `accepted assessments always include the original token`() {
        val assessment = validator.assessCandidate(
            value = "Nykaa",
            description = "Nykaa Pink Friday Deals",
            redeemCode = "NYKAA100",
            source = "structured"
        )

        assertTrue(assessment.isAccepted)
        assertNotNull("Accepted assessments should preserve the original candidate", assessment.original)
        assertTrue(
            "If this test fails the validator started allowing optional store names",
            assessment.original!!.isNotBlank()
        )
    }
}
