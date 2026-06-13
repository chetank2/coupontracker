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

    @Test
    fun `build includes canonical provenance and description guidance`() {
        val result = builder.build(sampleOcr)

        assertTrue(result.systemPrompt.contains("storeNameSource must be one lowercase provenance token"))
        assertTrue(result.systemPrompt.contains("Use ocr when the store name is present in the OCR excerpt"))
        assertTrue(result.userPrompt.contains("combine the main offer line with its following subtitle"))
        assertTrue(result.userPrompt.contains("copy exact supporting lines"))
    }
}
