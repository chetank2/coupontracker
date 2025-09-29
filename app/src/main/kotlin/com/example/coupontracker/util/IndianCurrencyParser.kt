package com.example.coupontracker.util

import android.util.Log
import java.util.Locale

/**
 * Utility for parsing Indian currency amounts with thousand separators
 * Handles formats like: ₹1,500, Rs. 2,50,000, 15,000 OFF, 1,23,456.78, etc.
 */
object IndianCurrencyParser {
    private const val TAG = "IndianCurrencyParser"
    
    /**
     * Parse numeric value from Indian currency/percentage strings
     * Handles Indian thousand separator format (1,23,456) and standard format (1,234,567)
     * 
     * Examples:
     * - "₹1,500 OFF" → 1500.0
     * - "Rs. 2,50,000" → 250000.0  
     * - "15,000 cashback" → 15000.0
     * - "1,23,456.78" → 123456.78
     * - "25% OFF" → 25.0
     * - "₹0" → 0.0
     * - "FREE" → null
     */
    fun parseAmount(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        
        return try {
            Log.d(TAG, "Parsing amount: '$value'")
            
            // Handle special cases
            val normalized = value.trim().uppercase()
            if (normalized.contains("FREE") || normalized.contains("NO COST")) {
                Log.d(TAG, "Detected free offer: '$value' → 0.0")
                0.0
            } else {
            
            // Extract numeric part with Indian thousand separators
            val numericString = extractNumericPart(value)
            
            if (numericString.isBlank()) {
                Log.w(TAG, "No numeric value found in: '$value'")
                null
            } else {
            
            val parsed = parseIndianNumber(numericString)
            Log.d(TAG, "Parsed '$value' → $parsed")
            parsed
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse amount: '$value'", e)
            null
        }
    }
    
    /**
     * Extract the numeric part from a currency string
     * Handles various currency symbols and text
     */
    private fun extractNumericPart(value: String): String {
        // Remove common currency symbols and text, but preserve commas and decimals
        var cleaned = value
            .replace(Regex("(?i)\\b(off|cashback|back|discount|save|flat|extra|upto|up to)\\b"), " ")
            .replace(Regex("[₹$£€¥%]"), " ") // Remove currency symbols
            .replace(Regex("(?i)\\b(rs|inr|rupees?)\\b\\.?"), " ") // Remove "Rs", "INR", "Rupees"
            .replace(Regex("[^0-9,.]"), " ") // Keep only digits, commas, and decimals
            .trim()
        
        // Find the longest numeric sequence with commas and decimals
        val numericPattern = Regex("\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?")
        val matches = numericPattern.findAll(cleaned).toList()
        
        // Return the longest match (likely the main amount)
        return matches.maxByOrNull { it.value.length }?.value ?: ""
    }
    
    /**
     * Parse Indian number format with thousand separators
     * Handles both Indian (1,23,456) and Western (1,234,567) comma patterns
     */
    private fun parseIndianNumber(numericString: String): Double? {
        if (numericString.isBlank()) return null
        
        return try {
            // Remove commas and parse
            val withoutCommas = numericString.replace(",", "")
            withoutCommas.toDoubleOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse numeric string: '$numericString'", e)
            null
        }
    }
    
    /**
     * Format a numeric amount for display with Indian thousand separators
     * Example: 123456.78 → "1,23,456.78"
     */
    fun formatIndianAmount(amount: Double): String {
        return try {
            if (amount % 1.0 == 0.0) {
                // Integer amount - use Indian number format
                formatIndianInteger(amount.toLong())
            } else {
                // Decimal amount - format with 2 decimal places
                val integerPart = amount.toLong()
                val decimalPart = String.format(Locale.US, "%.2f", amount % 1.0).substring(2)
                "${formatIndianInteger(integerPart)}.$decimalPart"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format amount: $amount", e)
            amount.toString()
        }
    }
    
    /**
     * Format integer with Indian thousand separators
     * Example: 123456 → "1,23,456"
     */
    private fun formatIndianInteger(amount: Long): String {
        if (amount < 1000) return amount.toString()
        
        val amountStr = amount.toString()
        val result = StringBuilder()
        
        // Add last 3 digits
        val len = amountStr.length
        result.insert(0, amountStr.substring(len - 3))
        
        // Add remaining digits in groups of 2
        var pos = len - 3
        while (pos > 0) {
            val start = maxOf(0, pos - 2)
            result.insert(0, ",")
            result.insert(0, amountStr.substring(start, pos))
            pos = start
        }
        
        return result.toString()
    }
    
    /**
     * Check if a string represents a percentage
     */
    fun isPercentage(value: String?): Boolean {
        return value?.contains("%") == true
    }
    
    /**
     * Check if a string represents a currency amount
     */
    fun isCurrency(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.contains(Regex("[₹$£€¥]")) || 
               value.contains(Regex("(?i)\\b(rs|inr|rupees?)\\b"))
    }
    
    /**
     * Extract percentage value from string
     * Example: "25% OFF" → 25.0
     */
    fun parsePercentage(value: String?): Double? {
        if (value.isNullOrBlank() || !value.contains("%")) return null
        
        val numericPart = extractNumericPart(value.replace("%", ""))
        return parseIndianNumber(numericPart)
    }
}
