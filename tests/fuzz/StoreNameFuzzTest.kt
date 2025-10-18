package com.example.coupontracker.extraction.validation

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreNameFuzzTest {
    private val validator = StoreNameValidator(TestBrandLexicon.create())
    private val random = Random(20240519)

    @Test
    fun `cta adjacent tokens never slip past validation`() {
        val ctaTokens = listOf(
            "shop now",
            "tap to claim",
            "claim now",
            "redeem now",
            "copy code",
            "apply now"
        )

        repeat(500) {
            val prefix = randomNoise()
            val suffix = randomNoise()
            val token = ctaTokens[random.nextInt(ctaTokens.size)]
            val candidate = listOf(prefix, token, suffix)
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
                .trim()

            val assessment = validator.assessCandidate(
                value = candidate,
                description = "${candidate} limited offer",
                redeemCode = suffix.uppercase(),
                source = if (it % 2 == 0) "ocr" else "structured"
            )

            assertFalse("$candidate should never be accepted", assessment.isAccepted)
            assertTrue(
                "CTA infused string must either be flagged or need attention",
                assessment.issues.contains("cta_stopword") || assessment.needsAttention
            )
        }
    }

    private fun randomNoise(): String {
        val length = random.nextInt(0, 6)
        if (length == 0) return ""
        val chars = CharArray(length) {
            val choice = random.nextInt(0, 26)
            ('a' + choice)
        }
        return String(chars)
    }
}
