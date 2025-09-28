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

    @Test
    fun extractCouponInfo_sanitizesNoisyDescription() {
        val noisyText = """
            STORE: Be Minimalist
            DESCRIPTION: On   On Radiance   Kit Kit from beminimalist.co qwrty ZZZZZ
        """.trimIndent()

        val info = helper.extractCouponInfo(noisyText)

        assertEquals("On Radiance Kit from beminimalist.co", info["description"])
    }

    @Test
    fun extractCouponInfo_handlesPunctuationWhenCollapsingDuplicates() {
        val noisyText = """
            DESCRIPTION: Save SAVE!!! on kit, kit -- from-store 123
        """.trimIndent()

        val info = helper.extractCouponInfo(noisyText)

        assertEquals("Save on kit from-store 123", info["description"])
    }
}
