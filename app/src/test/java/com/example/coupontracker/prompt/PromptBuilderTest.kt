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

        assertTrue(result.systemPrompt.contains("storeNameSource=ocr|heading|unknown"))
        assertTrue(result.systemPrompt.contains("storeNameEvidence=1-2 exact OCR snippets"))
        assertTrue(result.systemPrompt.contains("No markdown, comments, nulls, empty strings, or extra keys"))
        assertTrue(result.systemPrompt.contains("Use only text present in OCR"))
        assertTrue(result.systemPrompt.contains("Do not correct brand names by guessing"))
        assertTrue(result.userPrompt.contains("Do not invent or rename stores"))
        assertTrue(result.userPrompt.contains("Get Flat 20% Off*"))
    }
}
