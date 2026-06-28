package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreNameExtractorTest {
    private val extractor = StoreNameExtractor()

    @Test
    fun `extract keeps commercial merchant phrase behavior`() {
        val text = """
            Lhe Man
            MAN COHDANY
            Buy any 4 products at The Man Company website
        """.trimIndent()

        val result = extractor.extract(text)

        assertEquals("The Man Company", result)
    }

    @Test
    fun `extract rejects generic cashback as store name`() {
        val result = extractor.extract("cashback")

        assertNull(result)
    }
}
