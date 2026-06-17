package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DateParserTest {

    private val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `parses expires in weeks from base date`() {
        val baseDate = formatter.parse("2026-06-17")

        val result = DateParser.parseDate("Expires in 2 weeks", baseDate)

        assertEquals("2026-07-01", formatter.format(result!!))
    }

    @Test
    fun `parses expires in months from base date`() {
        val baseDate = formatter.parse("2026-06-17")

        val result = DateParser.parseDate("EXPIRES IN 1 MONTH", baseDate)

        assertEquals("2026-07-17", formatter.format(result!!))
    }
}
