package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancedOCRHelperTest {

    private val helper = EnhancedOCRHelper()

    @Test
    fun normalizeCouponCode_replacesAmbiguousCharacters() {
        val rawCode = "MNPRRK10UAPR255QYSGZA"
        val normalized = helper.normalizeCouponCode(rawCode)
        assertEquals("MNPRRK10OUAPR255QYSG7A", normalized)
    }
}
