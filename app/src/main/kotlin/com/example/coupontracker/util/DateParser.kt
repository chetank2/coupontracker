package com.example.coupontracker.util

import android.util.Log
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Utility class for parsing dates from text
 */
object DateParser {
    private const val TAG = "DateParser"

    /**
     * Parse a date from text
     * @param text The text to parse
     * @return The parsed date or null if not found
     */
    fun parseDate(text: String?): Date? {
        if (text.isNullOrBlank()) return null

        // Check for "Expires in X hours" format
        val expiresInHoursPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+hours?")
        val expiresInHoursMatcher = expiresInHoursPattern.matcher(text)
        if (expiresInHoursMatcher.find()) {
            val hoursToAdd = expiresInHoursMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            Log.d(TAG, "Found expiry date from 'expires in X hours' format: ${hoursToAdd} hours from now")
            return calendar.time
        }

        // Check for "Expires in X days" format
        val expiresInDaysPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+days?")
        val expiresInDaysMatcher = expiresInDaysPattern.matcher(text)
        if (expiresInDaysMatcher.find()) {
            val daysToAdd = expiresInDaysMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
            Log.d(TAG, "Found expiry date from 'expires in X days' format: ${daysToAdd} days from now")
            return calendar.time
        }

        // Try to parse the text as a date
        val datePatterns = listOf(
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "MMM dd, yyyy"
        )

        for (pattern in datePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val date = sdf.parse(text)
                if (date != null) {
                    Log.d(TAG, "Parsed date with pattern $pattern: $date")
                    return date
                }
            } catch (e: ParseException) {
                // Try next pattern
            }
        }

        return null
    }
}
