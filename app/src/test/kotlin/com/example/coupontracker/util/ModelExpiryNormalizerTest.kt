package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelExpiryNormalizerTest {

    @Test
    fun `parses model readable date with time suffix`() {
        assertEquals("2025-05-05", ModelExpiryNormalizer.toIsoDate("05 May, 2025, 11:59 PM"))
    }

    @Test
    fun `parses canonical iso date`() {
        assertEquals("2025-05-05", ModelExpiryNormalizer.toIsoDate("2025-05-05"))
    }

    @Test
    fun `ignores unknown model date`() {
        assertNull(ModelExpiryNormalizer.toIsoDate("unknown"))
    }
}
