package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCleanerTest {

    @Test
    fun `cleanForLlmExtraction strips copy lines and suffixes`() {
        val raw = """
            8:19
            Pastm
            Get Flat 20% Off*
            Code: AHAPPE20 COPY
            TAP TO COPY
            Scratch card received on offer
        """.trimIndent()

        val result = OcrTextCleaner.cleanForLlmExtraction(raw)

        val expected = """
            Pastm
            Get Flat 20 percent Off*
            Code: AHAPPE20
            Scratch card received on offer
        """.trimIndent()

        assertEquals(expected, result.cleanedText)
    }

    @Test
    fun `cleanOcrText preserves relative expiry lines`() {
        val raw = """
            PORTRONICS
            EXPIRES IN 10 DAYS
            Code OCE10
        """.trimIndent()

        val result = OcrTextCleaner.cleanOcrText(raw)

        assertEquals(raw, result)
    }
}
