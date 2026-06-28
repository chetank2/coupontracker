package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ExpiryDateExtractorTest {

    @Test
    fun `parse handles day first expiry with trailing time`() {
        val text = "Get 2 Months Audible Premium Plus\nExpires on 31 May, 2025, 11:59 PM"

        val result = ExpiryDateExtractor.parse(text)

        assertNotNull(result)
        assertEquals("2025-05-31", dateOnly.format(result!!))
    }

    @Test
    fun `parse uses supplied base date for relative days`() {
        val baseDate = dateTime.parse("2026-06-16 10:30")

        val result = ExpiryDateExtractor.parse("EXPIRES IN 29 DAYS", baseDate)

        assertNotNull(result)
        assertEquals("2026-07-15", dateOnly.format(result!!))
    }

    @Test
    fun `parse uses supplied base date for relative hours`() {
        val baseDate = dateTime.parse("2026-06-18 22:53")

        val result = ExpiryDateExtractor.parse("EXPIRES IN 04 HOURS", baseDate)

        assertNotNull(result)
        val calendar = Calendar.getInstance(Locale.US).apply { time = result!! }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, calendar.get(Calendar.MONTH))
        assertEquals(19, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(2, calendar.get(Calendar.HOUR_OF_DAY))
    }

    private companion object {
        val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}
