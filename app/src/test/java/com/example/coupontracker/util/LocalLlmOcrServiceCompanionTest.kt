package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmOcrServiceCompanionTest {

    @Test
    fun `cleanDescription keeps multi line offer details together`() {
        val raw = """
            You won Leaf bass wireless
            bluetooth earphones worth ₹3999 for ₹899
        """.trimIndent()

        val result = LocalLlmOcrService.cleanDescription(raw)

        assertEquals(
            "You won Leaf bass wireless bluetooth earphones worth ₹3999 for ₹899",
            result
        )
    }
}
