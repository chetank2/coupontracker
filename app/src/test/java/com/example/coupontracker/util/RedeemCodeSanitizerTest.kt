package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedeemCodeSanitizerTest {

    @Test
    fun `sanitizePreserve removes trailing copy instructions`() {
        val raw = "ahappe20 COPY"
        val sanitized = RedeemCodeSanitizer.sanitizePreserve(raw)

        assertEquals("AHAPPE20", sanitized)
    }

    @Test
    fun `sanitizePreserve removes trailing apply-now tokens`() {
        val raw = "SAVE50 apply now"
        val sanitized = RedeemCodeSanitizer.sanitizePreserve(raw)

        assertEquals("SAVE50", sanitized)
    }

    @Test
    fun `sanitize returns null for empty values`() {
        assertNull(RedeemCodeSanitizer.sanitize("   "))
    }
}

