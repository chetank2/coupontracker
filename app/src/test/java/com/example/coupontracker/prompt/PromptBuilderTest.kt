package com.example.coupontracker.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    private val sampleOcr = """
        Code: AHAPPE20 COPY
        Get Flat 20% Off*
        AVAIL NOW
        Expires on 31 May, 2025, 11:59 PM
    """.trimIndent()

    @Test
    fun `build removes CTA noise while keeping coupon details`() {
        val result = builder.build(sampleOcr)

        val excerpt = result.truncatedOcrForPrompt

        assertTrue(excerpt.contains("Code: AHAPPE20", ignoreCase = true))
        assertFalse(excerpt.contains("COPY", ignoreCase = true))
        assertFalse(excerpt.contains("AVAIL NOW", ignoreCase = true))
    }
}

