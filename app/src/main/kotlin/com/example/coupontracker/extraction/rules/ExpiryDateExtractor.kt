package com.example.coupontracker.extraction.rules

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object ExpiryDateExtractor {
    fun extract(
        text: String,
        baseDate: Date? = null,
        logDebug: ((String) -> Unit)? = null,
        logError: ((String, Throwable) -> Unit)? = null
    ): Date? {
        return parse(text, baseDate, logDebug, logError)
    }

    fun parse(
        text: String,
        baseDate: Date? = null,
        logDebug: ((String) -> Unit)? = null,
        logError: ((String, Throwable) -> Unit)? = null
    ): Date? {
        val referenceDate = baseDate ?: Date()
        val cleanedText = cleanDateCandidate(text)

        val expiresInHoursPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+hours?")
        val expiresInHoursMatcher = expiresInHoursPattern.matcher(text)
        if (expiresInHoursMatcher.find()) {
            val hoursToAdd = expiresInHoursMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.time = referenceDate
            calendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            logDebug?.invoke("Found expiry date from 'expires in X hours' format: ${hoursToAdd} hours from base date $referenceDate")
            return calendar.time
        }

        val expiryPattern = Pattern.compile("(?i)Expiry:\\s*(.+?)(?=\\n|$)")
        val expiryMatcher = expiryPattern.matcher(text)
        if (expiryMatcher.find()) {
            val expiryText = expiryMatcher.group(1)?.trim() ?: return null
            val cleanedExpiryText = cleanDateCandidate(expiryText)

            val expiresInPattern = Pattern.compile("(?i)Expires\\s+in\\s+(\\d+)\\s+(hours?|days?)")
            val expiresInMatcher = expiresInPattern.matcher(expiryText)
            if (expiresInMatcher.find()) {
                val timeValue = expiresInMatcher.group(1)?.toIntOrNull() ?: 0
                val timeUnit = expiresInMatcher.group(2)?.lowercase() ?: ""

                val calendar = Calendar.getInstance()
                calendar.time = referenceDate
                if (timeUnit.startsWith("hour")) {
                    calendar.add(Calendar.HOUR_OF_DAY, timeValue)
                } else {
                    calendar.add(Calendar.DAY_OF_YEAR, timeValue)
                }

                logDebug?.invoke("Found expiry date from 'Expiry:' with 'expires in' format: $timeValue $timeUnit from base date $referenceDate")
                return calendar.time
            }

            try {
                for (pattern in COMMON_DATE_PATTERNS) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(cleanedExpiryText)
                        if (date != null) {
                            logDebug?.invoke("Parsed expiry date from 'Expiry:' field: $expiryText")
                            return date
                        }
                    } catch (_: ParseException) {
                        // Try next pattern
                    }
                }
            } catch (e: Exception) {
                logError?.invoke("Error parsing expiry date from 'Expiry:' field", e)
            }
        }

        val explicitDatePattern = Pattern.compile("(?i)Expires?:?\\s+(\\w+\\s+\\d{1,2},\\s+\\d{4})")
        val explicitDateMatcher = explicitDatePattern.matcher(text)
        if (explicitDateMatcher.find()) {
            val dateStr = explicitDateMatcher.group(1) ?: return null
            val cleanedDate = cleanDateCandidate(dateStr)
            try {
                val date = parseWithCommonPatterns(cleanedDate)
                logDebug?.invoke("Found expiry date from explicit format: $dateStr")
                return date
            } catch (_: ParseException) {
                // Continue with other patterns
            }
        }

        val expiresInPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+days?")
        val expiresInMatcher = expiresInPattern.matcher(text)
        if (expiresInMatcher.find()) {
            val daysToAdd = expiresInMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.time = referenceDate
            calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
            logDebug?.invoke("Found expiry date from 'expires in X days' format: ${daysToAdd} days from base date $referenceDate")
            return calendar.time
        }

        val dateRegexes = listOf(
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}\\s+[A-Za-z]{3},\\s+\\d{2,4})")
        )

        for (regex in dateRegexes) {
            val matcher = regex.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(1) ?: continue
                val cleanedDateStr = cleanDateCandidate(dateStr)
                for (pattern in COMMON_DATE_PATTERNS) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(cleanedDateStr)
                        logDebug?.invoke("Found expiry date from standard format: $dateStr")
                        return date
                    } catch (_: ParseException) {
                        // Try next pattern
                    }
                }
            }
        }

        for (pattern in COMMON_DATE_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val date = sdf.parse(cleanedText)
                logDebug?.invoke("Parsed text directly as date: $text")
                return date
            } catch (_: ParseException) {
                // Try next pattern
            }
        }

        return null
    }

    private fun cleanDateCandidate(raw: String): String {
        var cleaned = raw.trim()
        cleaned = cleaned.replace(Regex(",\\s*\\d{1,2}:\\d{2}(?:\\s*[AP]M)?", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s+at\\s+\\d{1,2}:\\d{2}(?:\\s*[AP]M)?", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s+\\d{1,2}:\\d{2}(?:\\s*[AP]M)?", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(",", " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    private fun parseWithCommonPatterns(value: String): Date? {
        for (pattern in COMMON_DATE_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val date = sdf.parse(value)
                if (date != null) {
                    return date
                }
            } catch (_: ParseException) {
                // Try next pattern
            }
        }
        return null
    }

    private val COMMON_DATE_PATTERNS = listOf(
        "dd/MM/yyyy",
        "d/M/yyyy",
        "MM/dd/yyyy",
        "M/d/yyyy",
        "yyyy-MM-dd",
        "dd-MM-yyyy",
        "d-M-yyyy",
        "dd MMM yyyy",
        "d MMM yyyy",
        "dd MMMM yyyy",
        "d MMMM yyyy",
        "MMM dd yyyy",
        "MMM d yyyy",
        "MMMM dd yyyy",
        "MMMM d yyyy"
    )
}
