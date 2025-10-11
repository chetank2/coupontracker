package com.example.coupontracker.util

import android.util.Log
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*

/**
 * IST-first multi-format date parser for Indian coupon dates
 * Implements the date normalization pattern with confidence scoring
 */
object IndianDateParser {
    private const val TAG = "IndianDateParser"
    
    // Date formats in priority order (Indian formats first)
    private val DATE_FORMATS = listOf(
        "d/M/uuuu",           // 5/12/2024 (DD/MM/YYYY Indian format)
        "dd/MM/uuuu",         // 05/12/2024
        "d/MMM, uuuu",        // 10/Nov, 2025 (LLM output format)
        "dd/MMM, uuuu",       // 10/Nov, 2025
        "dd-MM-uuuu",         // 05-12-2024
        "dd MMM uuuu",        // 05 Dec 2024
        "d MMM uuuu",         // 5 Dec 2024
        "dd MMMM uuuu",       // 05 December 2024
        "d MMMM uuuu",        // 5 December 2024
        "MMM d, uuuu",        // Dec 5, 2024 (US format)
        "MMMM d, uuuu",       // December 5, 2024
        "uuuu-MM-dd",         // 2024-12-05 (ISO format)
        "d MMM",              // 5 Dec (assume current/next year)
        "dd MMM"              // 05 Dec
    )
    
    // Month name mappings for Indian English
    private val MONTH_REPLACEMENTS = mapOf(
        "jan" to "Jan", "january" to "January",
        "feb" to "Feb", "february" to "February", 
        "mar" to "Mar", "march" to "March",
        "apr" to "Apr", "april" to "April",
        "may" to "May",
        "jun" to "Jun", "june" to "June",
        "jul" to "Jul", "july" to "July",
        "aug" to "Aug", "august" to "August",
        "sep" to "Sep", "sept" to "Sep", "september" to "September",
        "oct" to "Oct", "october" to "October",
        "nov" to "Nov", "november" to "November",
        "dec" to "Dec", "december" to "December"
    )

    // Enhanced regex patterns for expiry detection
    private val EXPIRY_PATTERNS = listOf(
        // Absolute dates with context
        Regex("""(?i)\b(?:expires?|valid\s*(?:till|until|on)|ends?)\s*(?:on\s*)?(\d{1,2}\s*[A-Za-z]{3,9}\s*,?\s*\d{4})(?:,\s*\d{1,2}:\d{2}\s*(?:AM|PM))?"""),
        
        // Numeric dates with context
        Regex("""(?i)\b(?:expires?|valid\s*(?:till|until|on)|ends?)\s*(?:on\s*)?(0?[1-9]|[12][0-9]|3[01])[-/\s](0?[1-9]|1[0-2])[-/\s](\d{2,4})"""),
        
        // Relative dates
        Regex("""(?i)\b(?:expires?|valid)\s+in\s+(\d+)\s+days?\b"""),
        
        // End of month
        Regex("""(?i)\b(?:end(?:s)?\s+of\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:ember|uary|ch|il|e|y|ust|tember|ober|ember)?)\b"""),
        
        // Just the date without context (fallback)
        Regex("""(\d{1,2}\s*[A-Za-z]{3,9}\s*,?\s*\d{4})(?:,\s*\d{1,2}:\d{2}\s*(?:AM|PM))?"""),
        
        // Numeric only fallback
        Regex("""(0?[1-9]|[12][0-9]|3[01])[-/](0?[1-9]|1[0-2])[-/](\d{2,4})""")
    )
    
    /**
     * Extracts expiry date from text using regex patterns.
     * This handles cases where the date is embedded in longer text.
     */
    fun extractExpiryFromText(text: String, now: LocalDate = LocalDate.now()): DateParseResult {
        if (text.isBlank()) {
            return DateParseResult(null, 0.0f, "Empty text")
        }

        Log.d(TAG, "Extracting expiry from text: '$text'")

        // Try each regex pattern in priority order
        for ((index, pattern) in EXPIRY_PATTERNS.withIndex()) {
            val match = pattern.find(text)
            if (match != null) {
                val extractedDate = match.groupValues.getOrNull(1) ?: match.value
                Log.d(TAG, "Pattern $index matched: '${match.value}' -> extracted: '$extractedDate'")
                
                // Handle special cases
                val result = when {
                    // Relative dates (e.g., "in 5 days")
                    extractedDate.matches(Regex("\\d+")) && pattern.pattern.contains("days") -> {
                        val days = extractedDate.toIntOrNull() ?: 0
                        val futureDate = now.plusDays(days.toLong())
                        DateParseResult(futureDate, 0.9f, "Relative date: +$days days")
                    }
                    
                    // End of month (e.g., "end of May")
                    pattern.pattern.contains("end") -> {
                        parseEndOfMonth(extractedDate, now)
                    }
                    
                    // Regular date parsing
                    else -> parseExpiryIST(extractedDate, now)
                }
                
                if (result.date != null) {
                    return result.copy(confidence = result.confidence * (1.0f - index * 0.1f)) // Prefer earlier patterns
                }
            }
        }

        Log.w(TAG, "No expiry date pattern matched in text: '$text'")
        return DateParseResult(null, 0.0f, "No date pattern found")
    }

    /**
     * Parses "end of month" expressions like "end of May" or "end of December"
     */
    private fun parseEndOfMonth(monthName: String, now: LocalDate): DateParseResult {
        val cleanMonth = monthName.lowercase().trim()
        val monthNumber = when {
            cleanMonth.startsWith("jan") -> 1
            cleanMonth.startsWith("feb") -> 2
            cleanMonth.startsWith("mar") -> 3
            cleanMonth.startsWith("apr") -> 4
            cleanMonth.startsWith("may") -> 5
            cleanMonth.startsWith("jun") -> 6
            cleanMonth.startsWith("jul") -> 7
            cleanMonth.startsWith("aug") -> 8
            cleanMonth.startsWith("sep") -> 9
            cleanMonth.startsWith("oct") -> 10
            cleanMonth.startsWith("nov") -> 11
            cleanMonth.startsWith("dec") -> 12
            else -> return DateParseResult(null, 0.0f, "Unrecognized month: $monthName")
        }

        // Determine year - if the month has passed this year, use next year
        val currentYear = now.year
        val currentMonth = now.monthValue
        val year = if (monthNumber < currentMonth || (monthNumber == currentMonth && now.dayOfMonth > 15)) {
            currentYear + 1
        } else {
            currentYear
        }

        // Get last day of the month
        val lastDayOfMonth = LocalDate.of(year, monthNumber, 1).plusMonths(1).minusDays(1)
        
        return DateParseResult(lastDayOfMonth, 0.8f, "End of month: $monthName $year")
    }

    /**
     * Parse expiry date with IST timezone and confidence scoring
     */
    fun parseExpiryIST(rawDate: String, now: LocalDate = LocalDate.now(), depth: Int = 0): DateParseResult {
        if (rawDate.isBlank()) {
            return DateParseResult(null, 0.0f, "Empty date string")
        }
        
        // Prevent infinite recursion (max 2 levels: original + 1 fuzzy attempt)
        if (depth > 1) {
            Log.w(TAG, "⚠️ Recursion depth limit reached for: '$rawDate'")
            return DateParseResult(null, 0.0f, "Recursion depth limit exceeded")
        }
        
        // Clean and normalize the date string
        val cleanedDate = cleanDateString(rawDate)
        Log.d(TAG, "Parsing date: '$rawDate' -> '$cleanedDate' (depth: $depth)")
        
        // Try each format in priority order
        for ((index, format) in DATE_FORMATS.withIndex()) {
            try {
                val formatter = DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                var parsedDate = LocalDate.parse(cleanedDate, formatter)
                
                // Handle year inference for partial dates
                if (!format.contains("uuuu")) {
                    parsedDate = inferYear(parsedDate, now)
                }
                
                // Validate the date makes sense
                val validation = validateParsedDate(parsedDate, now, format)
                if (validation.isValid) {
                    val confidence = calculateConfidence(format, index, validation.daysDifference)
                    Log.d(TAG, "Successfully parsed '$cleanedDate' as $parsedDate (confidence: $confidence)")
                    return DateParseResult(parsedDate, confidence, "Parsed with format: $format")
                } else {
                    Log.d(TAG, "Date validation failed for $parsedDate: ${validation.reason}")
                }
                
            } catch (e: DateTimeParseException) {
                // Continue to next format
                Log.v(TAG, "Format '$format' failed for '$cleanedDate': ${e.message}")
            }
        }
        
        // Try fuzzy parsing for common variations (only at depth 0)
        if (depth == 0) {
            val fuzzyResult = tryFuzzyParsing(cleanedDate, now, depth + 1)
            if (fuzzyResult.date != null) {
                return fuzzyResult
            }
        }
        
        Log.w(TAG, "Failed to parse date: '$rawDate'")
        return DateParseResult(null, 0.0f, "No matching format found")
    }
    
    /**
     * Clean and normalize date string
     */
    private fun cleanDateString(rawDate: String): String {
        var cleaned = rawDate.trim()
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .replace(",", "") // Remove commas initially for parsing
        
        // Remove time components that aren't part of the date
        // E.g., "24 Midnight 2025" -> "24 2025" (invalid but won't cause recursion)
        cleaned = cleaned.replace(Regex("\\b(?:midnight|noon|am|pm)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?"), "") // Remove times like "8:20"
            .trim()
            .replace(Regex("\\s+"), " ") // Normalize again after removals
        
        // Normalize month names
        for ((key, value) in MONTH_REPLACEMENTS) {
            cleaned = cleaned.replace(key, value, ignoreCase = true)
        }
        
        // Handle "till" and other prefixes
        cleaned = cleaned.replace(Regex("^(?:valid\\s+)?(?:till|until|up\\s+to|expires?\\s+on?)\\s*", RegexOption.IGNORE_CASE), "")
        
        // Re-add comma for US format parsing
        if (Regex("\\w+\\s+\\d{1,2}\\s+\\d{4}").matches(cleaned)) {
            cleaned = cleaned.replace(Regex("(\\w+\\s+\\d{1,2})\\s+(\\d{4})"), "$1, $2")
        }
        
        return cleaned.trim()
    }
    
    /**
     * Infer year for partial dates (e.g., "30 Sept" -> "30 Sept 2024")
     */
    private fun inferYear(parsedDate: LocalDate, now: LocalDate): LocalDate {
        val currentYear = now.year
        val withCurrentYear = parsedDate.withYear(currentYear)
        
        // If date with current year is in the past by more than 30 days, try next year
        return if (withCurrentYear.isBefore(now.minusDays(30))) {
            parsedDate.withYear(currentYear + 1)
        } else {
            withCurrentYear
        }
    }
    
    /**
     * Validate parsed date makes sense for a coupon
     */
    private fun validateParsedDate(date: LocalDate, now: LocalDate, format: String): DateValidation {
        val daysDifference = java.time.temporal.ChronoUnit.DAYS.between(now, date).toInt()
        
        return when {
            // RELAXED: Accept dates from old screenshots (up to 6 months past)
            // This allows processing screenshots taken months ago
            daysDifference < -180 -> DateValidation(false, daysDifference, "Date is more than 6 months in the past")
            
            // Very far future dates are suspicious
            daysDifference > 730 -> DateValidation(false, daysDifference, "Date is more than 2 years in the future")
            
            // Perfect range
            daysDifference in 0..365 -> DateValidation(true, daysDifference, "Date is in optimal range")
            
            // Acceptable range (includes expired coupons from recent screenshots)
            else -> DateValidation(true, daysDifference, "Date is acceptable")
        }
    }
    
    /**
     * Calculate confidence score based on format and date validity
     */
    private fun calculateConfidence(format: String, formatIndex: Int, daysDifference: Int): Float {
        // Base confidence based on format priority
        val formatConfidence = when (formatIndex) {
            0, 1, 2 -> 0.9f  // Indian formats (DD/MM/YYYY, DD-MM-YYYY)
            3, 4, 5, 6 -> 0.85f  // Month-name formats
            7, 8 -> 0.8f  // US formats
            9 -> 0.75f  // ISO format
            10, 11 -> 0.7f  // Partial dates (year inferred)
            else -> 0.6f
        }
        
        // Adjust based on date reasonableness
        val dateConfidence = when {
            daysDifference in 7..90 -> 1.0f  // Perfect range (1 week to 3 months)
            daysDifference in 1..6 -> 0.9f   // Very soon
            daysDifference in 91..180 -> 0.85f  // 3-6 months
            daysDifference in 181..365 -> 0.75f  // 6-12 months
            daysDifference < 0 -> 0.3f  // Past dates (low confidence)
            else -> 0.6f  // Far future
        }
        
        // Year inference penalty
        val yearPenalty = if (!format.contains("uuuu")) 0.9f else 1.0f
        
        return formatConfidence * dateConfidence * yearPenalty
    }
    
    /**
     * Try fuzzy parsing for common variations
     */
    private fun tryFuzzyParsing(cleanedDate: String, now: LocalDate, depth: Int): DateParseResult {
        // Handle "30 Sept" -> "30 Sep" (common typo)
        val septVariation = cleanedDate.replace("Sept", "Sep")
        if (septVariation != cleanedDate) {
            val result = parseExpiryIST(septVariation, now, depth)
            if (result.date != null) {
                return result.copy(confidence = result.confidence * 0.95f, reason = "Fuzzy: Sept->Sep")
            }
        }
        
        // Handle missing year more aggressively
        val monthDayPattern = Regex("(\\d{1,2})\\s+(\\w+)")
        val match = monthDayPattern.find(cleanedDate)
        if (match != null) {
            val (day, month) = match.destructured
            val withYear = "$day $month ${now.year}"
            val result = parseExpiryIST(withYear, now, depth)
            if (result.date != null) {
                return result.copy(confidence = result.confidence * 0.8f, reason = "Fuzzy: Added current year")
            }
        }
        
        return DateParseResult(null, 0.0f, "Fuzzy parsing failed")
    }
}

/**
 * Result of date parsing with confidence
 */
data class DateParseResult(
    val date: LocalDate?,
    val confidence: Float,
    val reason: String
)

/**
 * Date validation result
 */
private data class DateValidation(
    val isValid: Boolean,
    val daysDifference: Int,
    val reason: String
)
