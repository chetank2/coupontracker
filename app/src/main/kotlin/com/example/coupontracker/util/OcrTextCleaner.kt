package com.example.coupontracker.util

/**
 * Utility for cleaning OCR text by removing UI chrome/noise.
 * Filters out battery indicators, signal bars, time displays, etc.
 * Enhanced for multi-coupon extraction with currency normalization and metadata extraction.
 */

/**
 * Result of OCR text cleaning with extracted metadata
 */
data class CleanedOcrResult(
    val cleanedText: String,
    val originalText: String,
    val metadata: Map<String, Any> = emptyMap(),
    val removedPatterns: List<String> = emptyList()
)

object OcrTextCleaner {
    
    // Banner labels commonly found in coupon screenshots
    private val BANNER_PATTERNS = listOf(
        "expires today", "expires in", "expiring soon", "collect now", "details",
        "view terms", "apply", "activate", "claim", "get offer", "redeem now",
        "save now", "shop now", "buy now", "add to cart", "view offer",
        "terms & conditions", "terms and conditions", "see details", "read more",
        "limited time", "for you", "recommended", "trending", "popular",
        "I'll use it later", "remind me", "not interested"
    ).map { it.lowercase() }

    private val CTA_LINE_PATTERNS = listOf(
        Regex("""^copy$""", RegexOption.IGNORE_CASE),
        Regex("""^tap to copy$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:avail|apply|subscribe|redeem|claim|grab|shop|buy) now$""", RegexOption.IGNORE_CASE),
        Regex("""^use now$""", RegexOption.IGNORE_CASE),
        Regex("""^get deal$""", RegexOption.IGNORE_CASE)
    )

    private val CTA_SUFFIXES = listOf(
        "copy",
        "copy code",
        "tap to copy",
        "avail now",
        "apply now",
        "subscribe now",
        "redeem now",
        "claim now",
        "grab deal",
        "shop now",
        "buy now",
        "get offer",
        "get deal"
    )
    
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
        
        // Check against banner patterns
        if (BANNER_PATTERNS.any { trimmed.lowercase().contains(it) }) {
            return true
        }
        
        // Lines with only special characters
        if (trimmed.all { !it.isLetterOrDigit() }) {
            return true
        }
        
        return false
    }
    
    /**
     * Normalize currency symbols for LLM processing
     */
    private fun normalizeCurrency(text: String): String {
        return text
            .replace("₹", "Rs. ")
            .replace("₨", "Rs. ")
            .replace("$", "USD ")
            .replace("€", "EUR ")
            .replace("£", "GBP ")
    }
    
    /**
     * Normalize percentage for LLM processing
     */
    private fun normalizePercentage(text: String): String {
        return text.replace(Regex("""(\d+)\s*%"""), "$1 percent")
    }
    
    /**
     * Extract date format hints from text
     */
    private fun extractDateHints(text: String): Map<String, String> {
        val hints = mutableMapOf<String, String>()
        
        // Detect date formats present
        val datePatterns = listOf(
            Regex("""\b\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}\b""") to "DD MMM YYYY",
            Regex("""\b\d{1,2}/\d{1,2}/\d{4}\b""") to "DD/MM/YYYY or MM/DD/YYYY",
            Regex("""\b\d{4}-\d{2}-\d{2}\b""") to "YYYY-MM-DD"
        )
        
        for ((pattern, format) in datePatterns) {
            if (pattern.containsMatchIn(text)) {
                hints["dateFormat"] = format
                break
            }
        }
        
        // Detect relative dates
        if (Regex("""\b(?:expires? in|valid for|days?\s+left)\s+\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            hints["hasRelativeDate"] = "true"
        }
        
        return hints
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
    
    /**
     * Clean OCR text for LLM extraction with metadata and normalization
     * This is the main method for multi-coupon extraction pipeline
     */
    fun cleanForLlmExtraction(text: String, includeCurrencyNormalization: Boolean = true): CleanedOcrResult {
        val removedPatterns = mutableListOf<String>()
        
        // Step 1: Remove UI chrome and banner labels
        val lines = text.lines()
        val cleanedLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }

            if (isUiChrome(trimmed)) {
                removedPatterns.add("UI_CHROME: $trimmed")
                continue
            }

            if (CTA_LINE_PATTERNS.any { it.containsMatchIn(trimmed) }) {
                removedPatterns.add("CTA_LINE: $trimmed")
                continue
            }

            val stripped = stripCtaSuffixes(trimmed)
            if (stripped.isBlank()) {
                removedPatterns.add("CTA_STRIP: $trimmed")
                continue
            }

            val sanitized = sanitizeInlineCtaTokens(stripped)
            if (sanitized.isBlank()) {
                removedPatterns.add("CTA_INLINE: $trimmed")
                continue
            }

            cleanedLines += sanitized
        }
        
        var cleanedText = cleanedLines.joinToString("\n").trim()
        
        // Step 2: Normalize currency and percentage (optional, for better LLM understanding)
        if (includeCurrencyNormalization) {
            cleanedText = normalizeCurrency(cleanedText)
            cleanedText = normalizePercentage(cleanedText)
        }
        
        // Step 3: Extract metadata hints
        val metadata = mutableMapOf<String, Any>()
        metadata.putAll(extractDateHints(text))
        metadata["originalLength"] = text.length
        metadata["cleanedLength"] = cleanedText.length
        metadata["removedLineCount"] = lines.size - cleanedLines.size
        
        return CleanedOcrResult(
            cleanedText = cleanedText,
            originalText = text,
            metadata = metadata,
            removedPatterns = removedPatterns
        )
    }

    private fun stripCtaSuffixes(line: String): String {
        var result = line
        var changed: Boolean
        do {
            changed = false
            for (suffix in CTA_SUFFIXES) {
                val newValue = result.removeCaseInsensitiveSuffix(" $suffix")
                if (newValue.length != result.length) {
                    result = newValue
                    changed = true
                } else {
                    val alt = result.removeCaseInsensitiveSuffix(suffix)
                    if (alt.length != result.length) {
                        result = alt
                        changed = true
                    }
                }
            }
        } while (changed)

        return result.trim()
    }

    private fun sanitizeInlineCtaTokens(line: String): String {
        var result = line.replace(Regex("""(\b[A-Z0-9]{3,})(\s+(?:copy|copy code|tap to copy))\b""", RegexOption.IGNORE_CASE), "$1")
        result = result.replace(Regex("""\b(?:copy|copy code|tap to copy|apply code|use now)\b""", RegexOption.IGNORE_CASE), " ")
        return result.replace(Regex("\\s{2,}"), " ").trim()
    }
}

private fun String.removeCaseInsensitiveSuffix(suffix: String): String {
    if (this.length < suffix.length) return this
    val end = this.substring(this.length - suffix.length)
    return if (end.equals(suffix, ignoreCase = true)) {
        this.substring(0, this.length - suffix.length).trimEnd()
    } else {
        this
    }
}

