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
    
    /**
     * Parse expiry date with IST timezone and confidence scoring
     */
    fun parseExpiryIST(rawDate: String, now: LocalDate = LocalDate.now()): DateParseResult {
        if (rawDate.isBlank()) {
            return DateParseResult(null, 0.0f, "Empty date string")
        }
        
        // Clean and normalize the date string
        val cleanedDate = cleanDateString(rawDate)
        Log.d(TAG, "Parsing date: '$rawDate' -> '$cleanedDate'")
        
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
        
        // Try fuzzy parsing for common variations
        val fuzzyResult = tryFuzzyParsing(cleanedDate, now)
        if (fuzzyResult.date != null) {
            return fuzzyResult
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
            // Past dates are usually invalid (except very recent ones)
            daysDifference < -7 -> DateValidation(false, daysDifference, "Date is more than 7 days in the past")
            
            // Very far future dates are suspicious
            daysDifference > 730 -> DateValidation(false, daysDifference, "Date is more than 2 years in the future")
            
            // Perfect range
            daysDifference in 0..365 -> DateValidation(true, daysDifference, "Date is in optimal range")
            
            // Acceptable range
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
    private fun tryFuzzyParsing(cleanedDate: String, now: LocalDate): DateParseResult {
        // Handle "30 Sept" -> "30 Sep" (common typo)
        val septVariation = cleanedDate.replace("Sept", "Sep")
        if (septVariation != cleanedDate) {
            val result = parseExpiryIST(septVariation, now)
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
            val result = parseExpiryIST(withYear, now)
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
