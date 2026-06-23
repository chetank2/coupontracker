package com.example.coupontracker.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Date
import java.util.Locale

/**
 * Parses model-emitted expiry strings into canonical Date values.
 *
 * This is intentionally more tolerant than user-facing expiry validation because
 * verification models often return readable dates such as "05 May, 2025, 11:59 PM".
 */
object ModelExpiryNormalizer {
    private val zoneId: ZoneId = ZoneId.of("Asia/Kolkata")
    private val isoRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val explicitTextDateRegex = Regex("""(?i)\b(\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]{3,9},?\s+\d{4})\b""")
    private val numericDateRegex = Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\b""")
    private val ordinalSuffixRegex = Regex("""(?i)(\d{1,2})(st|nd|rd|th)\b""")
    private val timeSuffixRegex = Regex("""(?i),?\s+\d{1,2}:\d{2}\s*(?:AM|PM)\b.*$""")
    private val dateFormatters = listOf(
        formatter("uuuu-MM-dd"),
        formatter("d MMM uuuu"),
        formatter("dd MMM uuuu"),
        formatter("d MMMM uuuu"),
        formatter("dd MMMM uuuu"),
        formatter("d/M/uuuu"),
        formatter("dd/M/uuuu"),
        formatter("d/MM/uuuu"),
        formatter("dd/MM/uuuu"),
        formatter("d-M-uuuu"),
        formatter("dd-M-uuuu"),
        formatter("d-MM-uuuu"),
        formatter("dd-MM-uuuu"),
        formatter("d/M/uu"),
        formatter("dd/M/uu"),
        formatter("d/MM/uu"),
        formatter("dd/MM/uu")
    )

    fun parse(value: String?, captureTimestamp: Date? = null): Date? {
        val cleaned = clean(value) ?: return null
        val baseDate = captureTimestamp
            ?.toInstant()
            ?.atZone(zoneId)
            ?.toLocalDate()
            ?: LocalDate.now(zoneId)
        val explicitCandidate = when {
            isoRegex.matches(cleaned) -> cleaned
            else -> explicitTextDateRegex.find(cleaned)?.groupValues?.getOrNull(1)
                ?: numericDateRegex.find(cleaned)?.groupValues?.getOrNull(1)
                ?: cleaned
        }
        return parseExplicitDate(explicitCandidate)
            ?: IndianDateParser.extractExpiryFromText(cleaned, baseDate).date?.toDate()
            ?: IndianDateParser.parseExpiryIST(cleaned, baseDate).date?.toDate()
    }

    fun toIsoDate(value: String?, captureTimestamp: Date? = null): String? {
        val date = parse(value, captureTimestamp) ?: return null
        return date.toInstant().atZone(zoneId).toLocalDate().toString()
    }

    private fun parseExplicitDate(value: String): Date? {
        val candidate = value
            .replace(ordinalSuffixRegex, "$1")
            .replace(timeSuffixRegex, "")
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return dateFormatters.firstNotNullOfOrNull { formatter ->
            runCatching {
                LocalDate.parse(candidate, formatter).toDate()
            }.recoverCatching { error ->
                if (error is DateTimeParseException && candidate.contains('/')) {
                    LocalDate.parse(candidate.replace('/', '-'), formatter).toDate()
                } else {
                    throw error
                }
            }.getOrNull()
        }
    }

    private fun clean(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.equals("unknown", ignoreCase = true) ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("n/a", ignoreCase = true)
        ) {
            return null
        }
        return trimmed
    }

    private fun LocalDate.toDate(): Date {
        return Date.from(atStartOfDay(zoneId).toInstant())
    }

    private fun formatter(pattern: String): DateTimeFormatter {
        return DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .toFormatter(Locale.US)
    }
}
