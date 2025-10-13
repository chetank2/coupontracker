package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextExtractorTest {

    private val extractor = TextExtractor()

    @Test
    fun `extractDescription combines multi-line offer text`() {
        val text = """
            Buy 2 Get 2 FREE*
            + Up to 5% Foxcoins
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Buy 2 Get 2 FREE* + Up to 5% Foxcoins",
            result
        )
    }
}
