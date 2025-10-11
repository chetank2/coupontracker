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
        
        // App ratings (1.0-5.0 range, often mistaken for cashback)
        Regex("""^[1-5]\.\d{1,2}$"""),  // Matches: 4.38, 4.5, 3.87, etc.
        Regex("""\b⭐?\s*[1-5]\.\d{1,2}\s*⭐?\b"""),  // Matches: ⭐ 4.38, 4.5 ⭐
        
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
        Regex("""^3d$""", RegexOption.IGNORE_CASE),  // UI badge
        
        // Carrier/SIM indicators
        Regex("""\b(?:SIM\s*[12]|carrier|network)\b""", RegexOption.IGNORE_CASE),
        
        // Delivery/Food app context (CRITICAL FIX for McDonald's confusion)
        // These patterns indicate background food delivery apps
        Regex("""\d{1,2}:\d{2}\s*(?:AM|PM)\s+\d+\s+items?""", RegexOption.IGNORE_CASE),  // "11:11 PM 1 items"
        Regex("""\bAssigning on priority\b""", RegexOption.IGNORE_CASE),  // Delivery status
        Regex("""\bSearching for a delive""", RegexOption.IGNORE_CASE),  // Delivery search
        Regex("""\bAdd Delivery Instructions\b""", RegexOption.IGNORE_CASE),  // Delivery UI
        
        // Common delivery app UI elements
        Regex("""\b(?:Swiggy|Zomato|Uber\s*Eats|DoorDash)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfood at the earliest\b""", RegexOption.IGNORE_CASE)
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
     * Detect if text contains delivery app context markers
     */
    private fun hasDeliveryAppContext(text: String): Boolean {
        val deliveryMarkers = listOf(
            Regex("""\d{1,2}:\d{2}\s*(?:AM|PM)\s+\d+\s+items?""", RegexOption.IGNORE_CASE),
            Regex("""\bAssigning on priority\b""", RegexOption.IGNORE_CASE),
            Regex("""\bSearching for a delivery partner\b""", RegexOption.IGNORE_CASE),
            Regex("""\bAdd Delivery Instructions\b""", RegexOption.IGNORE_CASE)
        )
        return deliveryMarkers.any { it.containsMatchIn(text) }
    }
    
    /**
     * Clean OCR text by removing UI chrome lines and delivery app context
     */
    fun cleanOcrText(ocrText: String): String {
        // Strategy: If we detect delivery app context, filter out everything BEFORE the coupon markers
        val lines = ocrText.lines()
        
        // Look for clear coupon indicators (brand logos in specific format, coupon codes, redemption text)
        val couponStartMarkers = listOf(
            Regex("""\b(?:Up to|Upto|Get|Save|Flat)\s+\d+%\s+Off\b""", RegexOption.IGNORE_CASE),
            Regex("""\bRedeem Now\b""", RegexOption.IGNORE_CASE),
            Regex("""\bI'll use it later\b""", RegexOption.IGNORE_CASE),
            Regex("""^[A-Z]{4,15}\d+[A-Z0-9]*$""")  // Coupon code pattern (e.g., BTXS5T13LI9V5)
        )
        
        // Check if we have delivery app context
        val hasDeliveryContext = hasDeliveryAppContext(ocrText)
        
        if (hasDeliveryContext) {
            // Find the first line that looks like a coupon
            val couponStartIndex = lines.indexOfFirst { line ->
                couponStartMarkers.any { it.containsMatchIn(line) }
            }
            
            if (couponStartIndex > 0) {
                // Also check for brand logo just before the offer (e.g., "BOAT" before "Up to 80% Off")
                val startFromIndex = maxOf(0, couponStartIndex - 2)  // Include 2 lines before coupon marker
                
                return lines.drop(startFromIndex)
                    .filterNot { isUiChrome(it) }
                    .joinToString("\n")
                    .trim()
            }
        }
        
        // Standard line-by-line filtering if no delivery context
        return lines
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

