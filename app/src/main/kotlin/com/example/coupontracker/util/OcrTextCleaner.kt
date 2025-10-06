package com.example.coupontracker.util

/**
 * Utility for cleaning OCR text by removing UI chrome/noise.
 * Filters out battery indicators, signal bars, time displays, etc.
 */
object OcrTextCleaner {
    
    // UI chrome patterns (status bar, navigation, etc.)
    private val UI_NOISE_PATTERNS = listOf(
        // Time displays
        Regex("""^\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)?\s*$"""),
        
        // Battery/Signal indicators
        Regex("""\b(?:5G|4G|LTE|VoLTE|Wi-?Fi|Bluetooth)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:battery|signal|charging)\b""", RegexOption.IGNORE_CASE),
        Regex("""[🔋📶📱📡]"""),  // Battery/signal icons
        Regex("""Ở\s+\d+%"""),  // Vietnamese battery indicator
        
        // Navigation elements
        Regex("""^[<>←→↑↓⬅➡⬆⬇]$"""),
        Regex("""\b(?:back|next|close|dismiss|ok|cancel)\b""", RegexOption.IGNORE_CASE),
        
        // Status indicators
        Regex("""\b(?:active|inactive|lifetime)\s*:\s*\d+\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:vouchers?|coupons?)\s*(?:active|available)\b""", RegexOption.IGNORE_CASE),
        
        // Single letters or very short lines (likely UI elements)
        Regex("""^[A-Za-z]$"""),
        Regex("""^[xX]$"""),  // Close button
        Regex("""^[mM]\s+[oO]$"""),  // Menu overflow
        
        // Carrier/SIM indicators
        Regex("""\b(?:SIM\s*[12]|carrier|network)\b""", RegexOption.IGNORE_CASE)
    )
    
    /**
     * Check if a line is likely UI chrome/noise
     */
    fun isUiChrome(line: String): Boolean {
        val trimmed = line.trim()
        
        // Empty or too short
        if (trimmed.length < 2) return true
        
        // Check against UI noise patterns
        if (UI_NOISE_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return true
        }
        
        // Lines with only special characters
        if (trimmed.all { !it.isLetterOrDigit() }) {
            return true
        }
        
        return false
    }
    
    /**
     * Clean OCR text by removing UI chrome lines
     */
    fun cleanOcrText(ocrText: String): String {
        return ocrText.lines()
            .filterNot { isUiChrome(it) }
            .joinToString("\n")
            .trim()
    }
    
    /**
     * Clean OCR text but preserve original for fallback
     * Returns pair of (cleaned, original)
     */
    fun cleanWithFallback(ocrText: String): Pair<String, String> {
        val cleaned = cleanOcrText(ocrText)
        return Pair(cleaned, ocrText)
    }
    
    /**
     * Extract first meaningful line (skip UI chrome)
     */
    fun getFirstMeaningfulLine(ocrText: String, maxLength: Int = 100): String? {
        return ocrText.lines()
            .filterNot { isUiChrome(it) }
            .firstOrNull { it.trim().isNotEmpty() }
            ?.trim()
            ?.take(maxLength)
    }
    
    /**
     * Get main content (skip first few lines which are often UI chrome)
     */
    fun getMainContent(ocrText: String, skipLines: Int = 3): String {
        return ocrText.lines()
            .drop(skipLines)
            .filterNot { isUiChrome(it) }
            .joinToString("\n")
            .trim()
    }
}

