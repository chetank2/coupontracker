package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class CouponInputManagerTest {

    @Test
    fun captureTimestampFallback_isTreatedAsUnknown() {
        val captureTimestamp = Date()

        val normalized = normalizeExpiryDate(Date(captureTimestamp.time), captureTimestamp)

        assertNull("Fallback expiry should be discarded", normalized)
        assertEquals("Unknown", DateFormatter.getExpiryText(normalized))
    }
}
