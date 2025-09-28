package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmOcrServiceNormalizationTest {

    @Test
    fun `normalizeStoreName should drop leading alphanumeric noise`() {
        val normalized = LocalLlmOcrService.normalizeStoreName("F2 Souvenir")
        assertEquals("Souvenir", normalized)
    }
}
