package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

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

    @Test
    fun `relative model date uses capture timestamp`() {
        val screenshotDate = Date.from(
            LocalDate.of(2025, 5, 2)
                .atStartOfDay(ZoneId.of("Asia/Kolkata"))
                .toInstant()
        )

        assertEquals(
            "2025-05-12",
            ModelExpiryNormalizer.toIsoDate("Expires in 10 days", screenshotDate)
        )
    }
}
