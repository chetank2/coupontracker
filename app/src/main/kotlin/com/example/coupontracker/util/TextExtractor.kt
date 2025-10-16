package com.example.coupontracker.util

import android.util.Log
import java.io.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class to hold extracted coupon information
 */
data class CouponInfo(
    val storeName: String = "",
    val description: String = "",
    val expiryDate: Date? = null,
    val cashbackAmount: Double? = null,
    val redeemCode: String? = null,
    val category: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val discountType: String? = null, // "PERCENTAGE" or "AMOUNT"

    // New fields
    val minimumPurchase: Double? = null,
    val maximumDiscount: Double? = null,
    val paymentMethod: String? = null,
    val platformType: String? = null,
    val usageLimit: Int? = null
) : Serializable {
    /**
     * Check if this coupon has enough valid information to be useful
     */
    fun isValid(): Boolean {
        // Must have a merchant name
        if (storeName.isBlank() || storeName == "Unknown Store") {
            return false
        }

        // Must have either a code or an amount
        if (redeemCode.isNullOrBlank() && (cashbackAmount == null || cashbackAmount <= 0)) {
            return false
        }

        return true
    }

    override fun toString(): String {
        return "CouponInfo(storeName='$storeName', description='$description', " +
               "expiryDate=$expiryDate, cashbackAmount=$cashbackAmount, " +
               "discountType=$discountType, redeemCode=$redeemCode, category=$category, " +
               "rating=$rating, status=$status, minimumPurchase=$minimumPurchase, " +
               "maximumDiscount=$maximumDiscount, paymentMethod=$paymentMethod, " +
               "platformType=$platformType, usageLimit=$usageLimit)"
    }
}

/**
 * Utility class to extract coupon information from text
 */
class TextExtractor {
    private val TAG = "TextExtractor"

    private inline fun safeLogDebug(tag: String, message: () -> String) {
        try {
            Log.d(tag, message())
        } catch (_: Throwable) {
            // Ignore logging failures in unit tests
        }
    }

    private inline fun safeLogError(tag: String, message: String, throwable: Throwable) {
        try {
            Log.e(tag, message, throwable)
        } catch (_: Throwable) {
            // Ignore logging failures in unit tests
        }
    }

    /**
     * Extract all coupon information from text
     * @param text The text to extract information from
     * @param baseDate The base date to use for relative expiry calculations (defaults to current time)
     * @return CouponInfo object containing extracted information
     */
    suspend fun extractCouponInfo(text: String, baseDate: Date? = null): CouponInfo = withContext(Dispatchers.Default) {
        extractCouponInfoSync(text, baseDate)
    }

    /**
     * Synchronous version of extractCouponInfo
     * @param text The text to extract information from
     * @param baseDate The base date to use for relative expiry calculations (defaults to current time)
     * @return CouponInfo object containing extracted information
     */
    fun extractCouponInfoSync(text: String, baseDate: Date? = null): CouponInfo {
        safeLogDebug(TAG) { "Extracting coupon info from text: ${text.take(100)}..." }

        val storeName = extractStoreName(text)
        val description = extractDescription(text)
        val expiryDate = extractExpiryDate(text, baseDate)
        val cashbackAmount = extractCashbackAmount(text)
        val redeemCode = extractRedeemCode(text)
        val category = extractCategory(text)
        val rating = extractRating(text)
        val status = extractStatus(text)
        val discountType = extractDiscountType(text)

        // Extract new fields
        val minimumPurchase = extractMinimumPurchase(text)
        val maximumDiscount = extractMaximumDiscount(text)
        val paymentMethod = extractPaymentMethod(text)
        val platformType = extractPlatformType(text)
        val usageLimit = extractUsageLimit(text)

        val result = CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode,
            category = category,
            rating = rating,
            status = status,
            discountType = discountType,
            minimumPurchase = minimumPurchase,
            maximumDiscount = maximumDiscount,
            paymentMethod = paymentMethod,
            platformType = platformType,
            usageLimit = usageLimit
        )

        safeLogDebug(TAG) { "Extracted coupon info: $result" }
        return result
    }

    /**
     * Extract store name from text
     * @param text The text to extract from
     * @return The extracted store name or null if not found
     */
    fun extractStoreName(text: String): String? {
        // Look for "Brand:" pattern
        val brandPattern = Pattern.compile("(?i)Brand:\\s*([\\p{L}\\p{M}\\p{N}]+)", Pattern.UNICODE_CASE)
        val brandMatcher = brandPattern.matcher(text)
        if (brandMatcher.find()) {
            val brand = brandMatcher.group(1)
            safeLogDebug(TAG) { "Found brand from 'Brand:' pattern: $brand" }
            return brand
        }

        val lowerText = text.lowercase(Locale.ROOT)
        val wordFrequency = mutableMapOf<String, Int>()
        val wordMatcher = Pattern.compile("\\b([\\p{L}][\\p{L}\\p{M}'\\-]*)\\b", Pattern.UNICODE_CASE).matcher(text)
        while (wordMatcher.find()) {
            val word = wordMatcher.group(1)?.lowercase(Locale.ROOT) ?: continue
            wordFrequency[word] = wordFrequency.getOrDefault(word, 0) + 1
        }

        val candidateScores = mutableMapOf<String, Double>()
        val candidateOriginal = mutableMapOf<String, String>()
        val lines = text.lines()

        fun addCandidate(raw: String?, isTitleCase: Boolean, lineIndex: Int, line: String) {
            val candidate = cleanCandidate(raw) ?: return
            val normalized = candidate.lowercase(Locale.ROOT)
            if (COMMON_WORDS.contains(normalized)) {
                return
            }

            val baseScore = if (isTitleCase) 4.0 else 2.0
            val frequency = wordFrequency[normalized] ?: 1
            val frequencyScore = if (frequency > 1) frequency * 2.5 else frequency.toDouble()
            val lineBonus = (lines.size - lineIndex).coerceAtLeast(1)
            val firstIndex = lowerText.indexOf(normalized)
            val positionScore = if (firstIndex >= 0 && text.isNotEmpty()) {
                (text.length - firstIndex).toDouble() / text.length.toDouble() * 3.0
            } else {
                0.0
            }

            val lineWordCount = line.split("\\s+".toRegex()).count { it.isNotBlank() }
            val headingBonus = if (lineWordCount <= 3) 3.0 else 0.0

            val contextBonus = when {
                line.contains("redeem", ignoreCase = true) -> 1.5
                line.contains("exclusive", ignoreCase = true) -> 1.0
                line.contains("plan", ignoreCase = true) || line.contains("offer", ignoreCase = true) -> 0.5
                else -> 0.0
            }

            val shortNameBonus = if (candidate.length <= 4 && isTitleCase) 1.5 else 0.0

            val totalScore = baseScore + frequencyScore + lineBonus + positionScore + headingBonus + contextBonus + shortNameBonus
            val currentScore = candidateScores[normalized]
            if (currentScore == null || totalScore > currentScore) {
                candidateScores[normalized] = totalScore
                candidateOriginal[normalized] = candidate
            }
        }

        data class TitleToken(val text: String, val start: Int, val end: Int)

        fun mergeAdjacentTitleTokens(line: String, tokens: List<TitleToken>): List<String> {
            if (tokens.isEmpty()) return emptyList()

            val merged = mutableListOf<String>()
            var currentText = tokens[0].text
            var currentEnd = tokens[0].end

            for (i in 1 until tokens.size) {
                val next = tokens[i]
                val between = line.substring(currentEnd, next.start)
                if (between.all { it.isWhitespace() }) {
                    currentText = "$currentText ${next.text}"
                    currentEnd = next.end
                } else {
                    merged.add(currentText)
                    currentText = next.text
                    currentEnd = next.end
                }
            }

            merged.add(currentText)
            return merged
        }

        val titlePattern = Pattern.compile("\\b([\\p{Lu}][\\p{L}\\p{M}]{2,})\\b", Pattern.UNICODE_CASE)
        val allCapsPattern = Pattern.compile("\\b([\\p{Lu}\\p{N}]{3,})\\b", Pattern.UNICODE_CASE)

        lines.forEachIndexed { index, line ->
            val titleMatcher = titlePattern.matcher(line)
            val titleTokens = mutableListOf<TitleToken>()
            while (titleMatcher.find()) {
                val token = titleMatcher.group(1) ?: continue
                titleTokens.add(TitleToken(token, titleMatcher.start(), titleMatcher.end()))
                addCandidate(token, true, index, line)
            }

            mergeAdjacentTitleTokens(line, titleTokens)
                .filter { it.contains(' ') }
                .forEach { combined -> addCandidate(combined, true, index, line) }

            val capsMatcher = allCapsPattern.matcher(line)
            while (capsMatcher.find()) {
                addCandidate(capsMatcher.group(1), false, index, line)
            }
        }

        if (candidateScores.isNotEmpty()) {
            val bestKey = candidateScores.maxByOrNull { it.value }!!.key
            val bestCandidate = candidateOriginal[bestKey]
            if (bestCandidate != null) {
                safeLogDebug(TAG) { "Selected store name candidate: $bestCandidate" }
                return bestCandidate
            }
        }

        // Try to find store names by common patterns
        val storePatterns = listOf(
            Pattern.compile("(?i)from\\s+(([\\p{L}\\p{M}\\p{N}]+(?:[&.'-]?\\s*[\\p{L}\\p{M}\\p{N}]+)*))", Pattern.UNICODE_CASE),
            Pattern.compile("(?i)at\\s+(([\\p{L}\\p{M}\\p{N}]+(?:[&.'-]?\\s*[\\p{L}\\p{M}\\p{N}]+)*))", Pattern.UNICODE_CASE),
            Pattern.compile("(?i)on\\s+(([\\p{L}\\p{M}\\p{N}]+(?:[&.'-]?\\s*[\\p{L}\\p{M}\\p{N}]+)*))", Pattern.UNICODE_CASE),
            Pattern.compile("(?i)via\\s+(([\\p{L}\\p{M}\\p{N}]+(?:[&.'-]?\\s*[\\p{L}\\p{M}\\p{N}]+)*))\\s+pay", Pattern.UNICODE_CASE)
        )

        for (pattern in storePatterns) {
            val storeMatcher = pattern.matcher(text)
            if (storeMatcher.find()) {
                val name = cleanCandidate(storeMatcher.group(1)) ?: continue
                safeLogDebug(TAG) { "Found store name from pattern: $name" }
                return name
            }
        }

        return null
    }

    private fun cleanCandidate(raw: String?): String? {
        val initial = raw?.trim()?.takeIf { it.length >= 3 } ?: return null
        val tokens = initial.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return null
        }

        val builder = mutableListOf<String>()
        for (token in tokens) {
            val trimmedToken = token.trim().trimEnd(',', '.', ';', ':')
            if (trimmedToken.isEmpty()) continue

            val leadingLetter = trimmedToken.firstOrNull { it.isLetter() }
            if (builder.isNotEmpty() && leadingLetter != null && leadingLetter.isLowerCase()) {
                break
            }

            builder.add(trimmedToken)
        }

        val cleaned = builder.joinToString(" ").trim()
        if (cleaned.length < 3) {
            return null
        }

        if (!cleaned.any { it.isLetter() }) {
            // Guard against numeric dashboard counters like "428"
            return null
        }

        val lower = cleaned.lowercase(Locale.ROOT)

        if (!lower.any { it in "aeiouy" }) {
            return null
        }

        val trailingConsonants = lower.takeLastWhile { it.isLetter() && it !in "aeiouy" }
        if (trailingConsonants.length >= 3) {
            return null
        }

        return cleaned
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        // FIRST: Check for multi-line "buy X get Y" patterns (high priority)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            if (Pattern.compile("(?i)buy\\s+\\d+\\s+get\\s+\\d+").matcher(line).find()) {
                val builder = StringBuilder(line)
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1]
                    if (nextLine.startsWith("+")) {
                        // Preserve the "+" as a connector
                        builder.append(' ').append(nextLine.trim())
                    } else if (Pattern.compile("(?i)(\\bup\\s*to\\b|flat|plus|&)").matcher(nextLine).find()) {
                        builder.append(' ').append(nextLine)
                    }
                }
                val combined = builder.toString().replace("\\s+".toRegex(), " ").trim()
                safeLogDebug(TAG) { "Found multi-line offer description: $combined" }
                if (combined.isMeaningfulDescription()) {
                    return combined
                }
            }
        }

        // Look for "Offer:" pattern
        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(text)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Offer:' pattern: $offer" }
            sanitizeDescription(offer)?.let { return it }
        }

        // Look for "You won X products at ₹Y + ₹Z cashback" pattern
        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(text)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'You won products' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Special case for coupons with "Get upto ₹X" pattern
        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(text)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            safeLogDebug(TAG) { "Found description from 'Get upto' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Up to X% off" pattern
        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(text)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Up to X%' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Flat ₹X OFF" pattern
        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(text)
        if (flatOffMatcher.find()) {
            val desc = flatOffMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Flat ₹X OFF' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        val candidates = mutableListOf<String>()

        fun addCandidate(raw: String?) {
            val sanitized = sanitizeDescription(raw)
            if (sanitized != null && sanitized.isMeaningfulDescription()) {
                candidates.add(sanitized)
            }
        }

        // Look for discount descriptions
        val discountPatterns = listOf(
            Pattern.compile("(?i)(buy\\s+\\d+\\s+get\\s+\\d+\\s+free.{0,80})"),
            Pattern.compile("(?i)(buy\\s+\\d+\\s+get\\s+\\d+.{0,80})"),
            Pattern.compile("(?i)(\\d+%\\s+off.{3,80})"),
            Pattern.compile("(?i)(₹\\d+\\s+off.{3,80})"),
            Pattern.compile("(?i)(Rs\\.?\\s*\\d+\\s+off.{3,80})"),
            Pattern.compile("(?i)(save\\s+\\d+%.{3,80})"),
            Pattern.compile("(?i)(up\\s+to\\s+₹\\d+\\s+off?.{0,80})"),
            Pattern.compile("(?i)(flat\\s+(?:₹\\s*)?\\d+[\\d,]*(?:\\.\\d+)?\\s+(?:off|cashback).{0,80})"),
            Pattern.compile("(?i)(\\d+[\\d,]*(?:\\.\\d+)?\\s+cashback.{0,80})"),
            Pattern.compile("(?i)(extra\\s+\\d+%\\s+off.{0,80})")
        )

        for (pattern in discountPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val desc = matcher.group(1)
                safeLogDebug(TAG) { "Found description from discount pattern: $desc" }
                addCandidate(desc)
            }
        }

        // If no specific discount pattern is found, consider the first sentence
        val sentences = text.split(Pattern.compile("[.!?]"))
        if (sentences.isNotEmpty() && sentences[0].length > 10) {
            val desc = sentences[0].trim()
            safeLogDebug(TAG) { "Using first sentence as description: $desc" }
            addCandidate(desc)
        }

        val summaryFallback = buildMonetarySummary(text, lines)

        val bestCandidate = candidates
            .filter { it.isMeaningfulDescription() }
            .maxByOrNull { it.length }

        if (bestCandidate != null) {
            return refineDescriptionCandidate(bestCandidate, summaryFallback)
        }

        return summaryFallback
    }

    private fun sanitizeDescription(value: String?): String? {
        val cleaned = LocalLlmOcrService.cleanDescription(value)
        return cleaned.ifBlank { null }
    }

    private fun refineDescriptionCandidate(candidate: String, summaryFallback: String?): String {
        val normalized = candidate.trim()
        if (summaryFallback == null) {
            return ensureRupeeSymbol(normalized)
        }

        val placeholder = GENERIC_DESCRIPTION_PATTERN.matcher(normalized).find()
        val valueMatches = RUPEE_VALUE_PATTERN.findAll(normalized).toList()
        val containsCashback = normalized.contains("cashback", ignoreCase = true)
        val containsPlus = normalized.contains('+') || normalized.contains(" plus ", ignoreCase = true)
        val containsMultipleAmounts = valueMatches.size >= 2

        if (placeholder || (containsCashback && (containsPlus || containsMultipleAmounts))) {
            return summaryFallback
        }

        if (valueMatches.isNotEmpty() && !normalized.contains('₹')) {
            val ensured = ensureRupeeSymbol(normalized)
            if (ensured != normalized) {
                return ensured
            }
        }

        return normalized
    }

    private fun ensureRupeeSymbol(candidate: String): String {
        if (candidate.contains('₹') || candidate.contains('%')) {
            return candidate
        }

        val matcher = LEADING_AMOUNT_PATTERN.matcher(candidate)
        if (!matcher.find()) {
            return candidate
        }

        val amountGroup = matcher.group(2)?.replace(",", "") ?: return candidate
        val amountValue = amountGroup.toIntOrNull() ?: return candidate
        if (amountValue < 50) {
            return candidate
        }

        val replacement = buildString {
            append(matcher.group(1))
            append(" ₹")
            append(amountGroup)
        }

        return candidate.replaceRange(matcher.start(), matcher.end(), replacement)
    }

    private fun buildMonetarySummary(text: String, lines: List<String>): String? {
        data class MonetaryLine(val line: String, val amount: Int)

        val monetaryCandidates = lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            if (!MONETARY_LINE_PATTERN.matcher(trimmed).find()) return@mapNotNull null
            if (trimmed.contains('%')) return@mapNotNull null

            val dominantAmount = extractDominantAmount(trimmed) ?: return@mapNotNull null
            MonetaryLine(trimmed, dominantAmount)
        }

        val best = monetaryCandidates.maxByOrNull { it.amount } ?: return null

        val prefix = when {
            best.line.contains("flat", ignoreCase = true) -> "Flat"
            best.line.contains("upto", ignoreCase = true) || best.line.contains("up to", ignoreCase = true) -> "Up to"
            best.line.contains("extra", ignoreCase = true) -> "Extra"
            best.line.contains("save", ignoreCase = true) -> "Save"
            else -> null
        }

        val suffix = when {
            best.line.contains("off", ignoreCase = true) -> "off"
            best.line.contains("cashback", ignoreCase = true) -> "cashback"
            best.line.contains("discount", ignoreCase = true) -> "discount"
            else -> "off"
        }

        val summaryCore = buildString {
            if (prefix != null) {
                append(prefix)
                append(' ')
            }
            append('₹')
            append(best.amount)
            append(' ')
            append(suffix.lowercase(Locale.ROOT))
        }.trim()

        val store = extractStoreName(text)?.takeIf { it.isNotBlank() } ?: findHeadingStoreFallback(lines)

        return store?.let { "$it Coupon - $summaryCore" } ?: summaryCore
    }

    private fun extractDominantAmount(line: String): Int? {
        var best: Int? = null
        val matcher = DIGIT_RUN_PATTERN.matcher(line)
        while (matcher.find()) {
            val raw = matcher.group(1)?.replace(",", "") ?: continue
            val value = raw.toIntOrNull() ?: continue
            if (value < 50) continue
            if (best == null || value > best!!) {
                best = value
            }
        }
        return best
    }

    private fun findHeadingStoreFallback(lines: List<String>): String? {
        return lines.firstOrNull { line ->
            if (line.isBlank()) return@firstOrNull false
            val cleaned = line.trim()
            if (!cleaned.any { it.isLetter() }) return@firstOrNull false
            if (GENERIC_HEADING_PATTERN.matcher(cleaned).find()) return@firstOrNull false
            val words = cleaned.split(" ").filter { it.isNotBlank() }
            words.size in 1..3
        }?.trim()
    }

    private fun String.isMeaningfulDescription(): Boolean {
        if (isBlank()) {
            return false
        }

        val normalized = trim()
        if (normalized.length < 4) {
            return false
        }

        val hasAlphaNumeric = normalized.any { it.isLetterOrDigit() }
        if (!hasAlphaNumeric) {
            return false
        }

        if (looksLikeDashboardStats(normalized)) {
            return false
        }

        val genericPhrases = listOf(
            "offer",
            "coupon",
            "deal"
        )

        val lower = normalized.lowercase(Locale.ROOT)
        if (genericPhrases.any { lower == it }) {
            return false
        }

        return true
    }

    private fun looksLikeDashboardStats(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)

        if (Regex("^\\d{1,2}:\\d{2}").containsMatchIn(text.trim())) {
            return true
        }

        if (Regex("vouchers?\\s+active").containsMatchIn(lower)) {
            return true
        }

        if (Regex("lifetime\\s*:\\s*\\d+").containsMatchIn(lower)) {
            return true
        }

        if (Regex("active\\s*:\\s*\\d+").containsMatchIn(lower)) {
            return true
        }

        return false
    }

    /**
     * Extract expiry date from text
     * @param text The text to extract from
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The extracted expiry date or null if not found
     */
    fun extractExpiryDate(text: String, baseDate: Date? = null): Date? {
        return parseExpiryDate(text, baseDate)
    }

    /**
     * Parse expiry date from text
     * @param text The text to parse
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The parsed date or null if not found
     */
    fun parseExpiryDate(text: String, baseDate: Date? = null): Date? {
        val referenceDate = baseDate ?: Date()
        val cleanedText = cleanDateCandidate(text)
        
        // Check for "Expires in X hours" format
        val expiresInHoursPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+hours?")
        val expiresInHoursMatcher = expiresInHoursPattern.matcher(text)
        if (expiresInHoursMatcher.find()) {
            val hoursToAdd = expiresInHoursMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.time = referenceDate
            calendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            safeLogDebug(TAG) { "Found expiry date from 'expires in X hours' format: ${hoursToAdd} hours from base date $referenceDate" }
            return calendar.time
        }

        // Check for "Expiry:" format
        val expiryPattern = Pattern.compile("(?i)Expiry:\\s*(.+?)(?=\\n|$)")
        val expiryMatcher = expiryPattern.matcher(text)
        if (expiryMatcher.find()) {
            val expiryText = expiryMatcher.group(1)?.trim() ?: return null
            val cleanedExpiryText = cleanDateCandidate(expiryText)

            // Check if it contains "Expires in X hours/days"
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

                safeLogDebug(TAG) { "Found expiry date from 'Expiry:' with 'expires in' format: $timeValue $timeUnit from base date $referenceDate" }
                return calendar.time
            }

            // Try to parse the expiry text as a date
            try {
                val datePatterns = COMMON_DATE_PATTERNS

                for (pattern in datePatterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(cleanedExpiryText)
                        if (date != null) {
                            safeLogDebug(TAG) { "Parsed expiry date from 'Expiry:' field: $expiryText" }
                            return date
                        }
                    } catch (e: ParseException) {
                        // Try next pattern
                    }
                }
            } catch (e: Exception) {
                safeLogError(TAG, "Error parsing expiry date from 'Expiry:' field", e)
            }
        }

        // Check for explicit expiry date in format "Expires: Mar 15, 2025"
        val explicitDatePattern = Pattern.compile("(?i)Expires?:?\\s+(\\w+\\s+\\d{1,2},\\s+\\d{4})")
        val explicitDateMatcher = explicitDatePattern.matcher(text)
        if (explicitDateMatcher.find()) {
            val dateStr = explicitDateMatcher.group(1) ?: return null
            val cleanedDate = cleanDateCandidate(dateStr)
            try {
                val date = parseWithCommonPatterns(cleanedDate)
                safeLogDebug(TAG) { "Found expiry date from explicit format: $dateStr" }
                return date
            } catch (e: ParseException) {
                // Continue with other patterns
            }
        }

        // Check for "Expires in X days" format
        val expiresInPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+days?")
        val expiresInMatcher = expiresInPattern.matcher(text)
        if (expiresInMatcher.find()) {
            val daysToAdd = expiresInMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.time = referenceDate
            calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
            safeLogDebug(TAG) { "Found expiry date from 'expires in X days' format: ${daysToAdd} days from base date $referenceDate" }
            return calendar.time
        }

        // Check for standard date formats
        val datePatterns = COMMON_DATE_PATTERNS

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
                for (pattern in datePatterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(cleanedDateStr)
                        safeLogDebug(TAG) { "Found expiry date from standard format: $dateStr" }
                        return date
                    } catch (e: ParseException) {
                        // Try next pattern
                    }
                }
            }
        }

        // Try to parse the text directly as a date
        for (pattern in datePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val date = sdf.parse(cleanedText)
                safeLogDebug(TAG) { "Parsed text directly as date: $text" }
                return date
            } catch (e: ParseException) {
                // Try next pattern
            }
        }

        // If no expiry date is found, set a default expiry date 30 days from now
        if (text.contains("ABHIBUS", ignoreCase = true)) {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 30)
            safeLogDebug(TAG) { "Using default expiry date for ABHIBUS: 30 days from now" }
            return calendar.time
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

    /**
     * Extract cashback amount from text
     * @param text The text to extract from
     * @return The extracted cashback amount or null if not found
     */
    fun extractCashbackAmount(text: String): Double? {
        // Look for specific percentage patterns first
        val percentagePatterns = listOf(
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
            Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%")
        )

        for (pattern in percentagePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        safeLogDebug(TAG) { "Found percentage discount: $amount%" }
                        // Don't apply any conversion factor for percentages
                        return amount
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing percentage", e)
                }
            }
        }

        // Now check for currency amount patterns
        val amountPatterns = listOf(
            Pattern.compile("(?i)(?:upto|up to|flat|get)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:off|cashback)"),
            Pattern.compile("(?i)(?:save|discount of)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in amountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        safeLogDebug(TAG) { "Found fixed currency amount: $amount" }
                        return amount
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing amount", e)
                }
            }
        }

        // Myntra specific pattern
        if (text.contains("myntra", ignoreCase = true)) {
            val myntraAmountPattern = Pattern.compile("(?i)(?:up to |voucher up to )(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
            val myntraAmountMatcher = myntraAmountPattern.matcher(text)
            if (myntraAmountMatcher.find()) {
                try {
                    val amount = myntraAmountMatcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        safeLogDebug(TAG) { "Found Myntra cashback amount: $amount" }
                        return amount
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing Myntra amount", e)
                }
            }
        }

        // Look for simple currency amounts
        val simpleAmountPattern = Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val simpleAmountMatcher = simpleAmountPattern.matcher(text)
        if (simpleAmountMatcher.find()) {
            try {
                val amount = simpleAmountMatcher.group(1)?.toDoubleOrNull()
                if (amount != null) {
                    safeLogDebug(TAG) { "Found simple cashback amount: $amount" }
                    return amount
                }
            } catch (e: Exception) {
                safeLogError(TAG, "Error parsing simple amount", e)
            }
        }

        return null
    }

    /**
     * Determine the type of discount (percentage or fixed amount)
     * @param text The text to analyze
     * @return "PERCENTAGE" or "AMOUNT" or null if undetermined
     */
    fun extractDiscountType(text: String): String? {
        // Check if text contains percentage indicators
        if (text.contains("%")) {
            val percentagePatterns = listOf(
                Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
                Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%"),
                Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%")
            )

            for (pattern in percentagePatterns) {
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    safeLogDebug(TAG) { "Identified discount type: PERCENTAGE" }
                    return "PERCENTAGE"
                }
            }
        }

        // Check for currency amount patterns
        val amountPatterns = listOf(
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:upto|up to|flat|get)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:off|cashback)"),
            Pattern.compile("(?i)(?:save|discount of)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in amountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                safeLogDebug(TAG) { "Identified discount type: AMOUNT" }
                return "AMOUNT"
            }
        }

        return null
    }

    /**
     * Extract redeem code from text
     * @param text The text to extract from
     * @return The extracted redeem code or null if not found
     */
    fun extractRedeemCode(text: String): String? {
        // First check for code: pattern (most common)
        val codePattern = Pattern.compile("(?i)code\\s*[-–—:]?\\s*([A-Z0-9]{5,})")
        val codeMatcher = codePattern.matcher(text)
        if (codeMatcher.find()) {
            val code = codeMatcher.group(1)
            safeLogDebug(TAG) { "Found code from 'code:' pattern: $code" }
            RedeemCodeSanitizer.sanitize(code)?.let { sanitized ->
                return sanitized
            }
        }

        // Look for "code" pattern without colon (common in Myntra coupons)
        val codeWithoutColonPattern = Pattern.compile("(?i)\\bcode\\b\\s*[-–—:]?\\s*([A-Z0-9]{5,})")
        val codeWithoutColonMatcher = codeWithoutColonPattern.matcher(text)
        if (codeWithoutColonMatcher.find()) {
            val code = codeWithoutColonMatcher.group(1)
            safeLogDebug(TAG) { "Found code from 'code' pattern without colon: $code" }
            RedeemCodeSanitizer.sanitize(code)?.let { sanitized ->
                return sanitized
            }
        }

        // Check specifically for Myntra coupon code format
        if (text.contains("myntra", ignoreCase = true)) {
            // Try to find a typical Myntra code pattern (8-10 characters, alphanumeric)
            val myntraCodePattern = Pattern.compile("\\b([A-Z0-9]{8,})\\b")
            val myntraCodeMatcher = myntraCodePattern.matcher(text)
            while (myntraCodeMatcher.find()) {
                val potentialCode = myntraCodeMatcher.group(1)
                // Skip if it's too long to be a code
                if (potentialCode?.length ?: 0 <= 20) {
                    safeLogDebug(TAG) { "Found Myntra code from specific pattern: $potentialCode" }
                    RedeemCodeSanitizer.sanitize(potentialCode)?.let { sanitized ->
                        return sanitized
                    }
                }
            }
        }

        // Try to find all caps+digits strings that might be codes
        val allCapsDigitsPattern = Pattern.compile("\\b([A-Z0-9]{6,})\\b")
        val allCapsDigitsMatcher = allCapsDigitsPattern.matcher(text)

        while (allCapsDigitsMatcher.find()) {
            val potentialCode = allCapsDigitsMatcher.group(1)
            // Skip if it's likely not a code (too long or too short)
            if ((potentialCode?.length ?: 0) in 6..20 && !COMMON_WORDS.contains(potentialCode?.lowercase() ?: "")) {
                safeLogDebug(TAG) { "Found code from all caps+digits pattern: $potentialCode" }
                RedeemCodeSanitizer.sanitize(potentialCode)?.let { sanitized ->
                    return sanitized
                }
            }
        }

        // Look for specific indicators that might precede a code
        val codeIndicators = listOf("use", "apply", "redeem", "coupon", "promocode", "promo code")

        for (indicator in codeIndicators) {
            val indicatorIndex = text.indexOf(indicator, ignoreCase = true)
            if (indicatorIndex != -1) {
                // Extract the next word after the indicator
                val afterIndicator = text.substring(indicatorIndex + indicator.length).trim()
                val potentialCode = afterIndicator.split(Pattern.compile("\\s+"))[0]

                if (potentialCode.length >= 5 && potentialCode.matches("[A-Z0-9]+".toRegex())) {
                    safeLogDebug(TAG) { "Found code after indicator '$indicator': $potentialCode" }
                    RedeemCodeSanitizer.sanitize(potentialCode)?.let { sanitized ->
                        return sanitized
                    }
                }
            }
        }

        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains("code", ignoreCase = true)) continue
            val next = lines.getOrNull(i + 1)?.trim()?.split(" ")?.firstOrNull { token ->
                token.length >= 5 && token.all { it.isLetterOrDigit() }
            }
            if (next != null) {
                safeLogDebug(TAG) { "Found code on line following indicator: $next" }
                RedeemCodeSanitizer.sanitize(next)?.let { sanitized ->
                    return sanitized
                }
            }
        }

        return null
    }

    /**
     * Extract category from text
     * @param text The text to extract from
     * @return The extracted category or null if not found
     */
    fun extractCategory(text: String): String? {
        val lowerText = text.lowercase()

        // For XYXX, set category to Fashion
        if (lowerText.contains("xyxx")) {
            safeLogDebug(TAG) { "Setting category to Fashion for XYXX" }
            return "Fashion"
        }

        // For Myntra, set category to Fashion
        if (lowerText.contains("myntra")) {
            safeLogDebug(TAG) { "Setting category to Fashion for Myntra" }
            return "Fashion"
        }

        // For ABHIBUS, set category to Travel
        if (lowerText.contains("abhibus")) {
            safeLogDebug(TAG) { "Setting category to Travel for ABHIBUS" }
            return "Travel"
        }

        for (category in CATEGORIES) {
            if (lowerText.contains(category.lowercase())) {
                safeLogDebug(TAG) { "Found category from text: $category" }
                return category
            }
        }

        return null
    }

    /**
     * Extract rating from text
     * @param text The text to extract from
     * @return The extracted rating or null if not found
     */
    fun extractRating(text: String): String? {
        // Look for "Rating:" pattern
        val ratingPattern = Pattern.compile("(?i)Rating:\\s*(.+?)(?=\\n|$)")
        val ratingMatcher = ratingPattern.matcher(text)
        if (ratingMatcher.find()) {
            val rating = ratingMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found rating from 'Rating:' pattern: $rating" }
            return rating
        }

        // Look for star rating pattern (e.g., "⭐ 4.31")
        val starRatingPattern = Pattern.compile("(⭐\\s*\\d+\\.\\d+)")
        val starRatingMatcher = starRatingPattern.matcher(text)
        if (starRatingMatcher.find()) {
            val rating = starRatingMatcher.group(1)
            safeLogDebug(TAG) { "Found rating from star pattern: $rating" }
            return rating
        }

        // Look for numeric rating pattern (e.g., "4.31/5")
        val numericRatingPattern = Pattern.compile("(\\d+\\.\\d+)\\s*/\\s*5")
        val numericRatingMatcher = numericRatingPattern.matcher(text)
        if (numericRatingMatcher.find()) {
            val rating = numericRatingMatcher.group(1)
            safeLogDebug(TAG) { "Found rating from numeric pattern: $rating" }
            return rating
        }

        return null
    }

    /**
     * Extract status from text
     * @param text The text to extract from
     * @return The extracted status or null if not found
     */
    fun extractStatus(text: String): String? {
        // Look for "Status:" pattern
        val statusPattern = Pattern.compile("(?i)Status:\\s*(.+?)(?=\\n|$)")
        val statusMatcher = statusPattern.matcher(text)
        if (statusMatcher.find()) {
            val status = statusMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found status from 'Status:' pattern: $status" }
            return status
        }

        // Look for "Available to Redeem" pattern
        if (text.contains("Available to Redeem", ignoreCase = true)) {
            safeLogDebug(TAG) { "Found 'Available to Redeem' status" }
            return "Available to Redeem"
        }

        // Look for "Redeemed" pattern
        if (text.contains("Redeemed", ignoreCase = true)) {
            safeLogDebug(TAG) { "Found 'Redeemed' status" }
            return "Redeemed"
        }

        // Look for "Expired" pattern
        if (text.contains("Expired", ignoreCase = true)) {
            safeLogDebug(TAG) { "Found 'Expired' status" }
            return "Expired"
        }

        return null
    }

    /**
     * Extract minimum purchase amount from text
     * @param text The text to extract from
     * @return The extracted minimum purchase amount or null if not found
     */
    fun extractMinimumPurchase(text: String): Double? {
        val patterns = listOf(
            Pattern.compile("(?i)min(?:imum)?\\s+(?:order|purchase)\\s+(?:of)?\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:order|purchase)\\s+above\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)valid\\s+on\\s+(?:orders|purchases)\\s+above\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        safeLogDebug(TAG) { "Found minimum purchase amount: $amount" }
                        return amount
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing minimum purchase amount", e)
                }
            }
        }

        return null
    }

    /**
     * Extract maximum discount amount from text
     * @param text The text to extract from
     * @return The extracted maximum discount amount or null if not found
     */
    fun extractMaximumDiscount(text: String): Double? {
        val patterns = listOf(
            Pattern.compile("(?i)max(?:imum)?\\s+(?:discount|cashback)\\s*(?:of)?\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)up\\s+to\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:discount|cashback)\\s+up\\s+to\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        safeLogDebug(TAG) { "Found maximum discount amount: $amount" }
                        return amount
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing maximum discount amount", e)
                }
            }
        }

        return null
    }

    /**
     * Extract payment method from text
     * @param text The text to extract from
     * @return The extracted payment method or null if not found
     */
    fun extractPaymentMethod(text: String): String? {
        val patterns = listOf(
            Pattern.compile("(?i)valid\\s+(?:only)?\\s+on\\s+(\\w+(?:\\s+\\w+)?)\\s+(?:payments|cards)"),
            Pattern.compile("(?i)(\\w+(?:\\s+\\w+)?)\\s+(?:payments|cards)\\s+only"),
            Pattern.compile("(?i)pay\\s+using\\s+(\\w+(?:\\s+\\w+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val method = matcher.group(1)
                safeLogDebug(TAG) { "Found payment method: $method" }
                return method
            }
        }

        // Check for common payment methods
        val paymentMethods = listOf(
            "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet",
            "PhonePe", "Google Pay", "Paytm", "Amazon Pay"
        )

        for (method in paymentMethods) {
            if (text.contains(method, ignoreCase = true)) {
                safeLogDebug(TAG) { "Found payment method from common list: $method" }
                return method
            }
        }

        return null
    }

    /**
     * Extract platform type from text
     * @param text The text to extract from
     * @return The extracted platform type or null if not found
     */
    fun extractPlatformType(text: String): String? {
        return PlatformDetector.detectPlatformFromText(text)
    }

    /**
     * Extract usage limit from text
     * @param text The text to extract from
     * @return The extracted usage limit or null if not found
     */
    fun extractUsageLimit(text: String): Int? {
        val patterns = listOf(
            Pattern.compile("(?i)(?:can be used|valid)\\s+(\\d+)\\s+times?"),
            Pattern.compile("(?i)(\\d+)\\s+uses?\\s+(?:per|only)"),
            Pattern.compile("(?i)limit\\s+(\\d+)\\s+uses?")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val limit = matcher.group(1)?.toIntOrNull()
                    if (limit != null) {
                        safeLogDebug(TAG) { "Found usage limit: $limit" }
                        return limit
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing usage limit", e)
                }
            }
        }

        // Check for "single use" or "one time" phrases
        if (text.contains("single use", ignoreCase = true) ||
            text.contains("one time", ignoreCase = true) ||
            text.contains("once per", ignoreCase = true)) {
            safeLogDebug(TAG) { "Found single use limit" }
            return 1
        }

        return null
    }

    companion object {
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

        private val COMMON_WORDS = setOf(
            "the", "and", "for", "with", "off", "use", "get", "code", "coupon",
            "offer", "valid", "till", "from", "upto", "free", "save", "discount",
            "multi", "product", "products", "kit", "combo", "pack", "value", "special",
            "now", "today", "details", "redeem", "claim", "activate", "shop", "buy",
            "view", "apply", "tap", "click", "pastm", "patm"
        )

        private val GENERIC_DESCRIPTION_PATTERN = Pattern.compile("(?i)^(coupon\\s*offer|offer\\s*details|coupon\\s*details|details|offer)")
        private val MONETARY_LINE_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save).*(off|cashback|discount)")
        private val LEADING_AMOUNT_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save)\\s+(\\d[\\d,]{2,})")
        private val DIGIT_RUN_PATTERN = Pattern.compile("(\\d[\\d,]{2,})")
        private val GENERIC_HEADING_PATTERN = Pattern.compile("(?i)(offer|details|coupon|code|cashback)")
        private val RUPEE_VALUE_PATTERN = "(?i)(?:₹|rs\\.?\\s*)?\\d[\\d,]{2,}(?=\\s*(?:cashback|off|discount|\\+|$))".toRegex()

        private val CATEGORIES = listOf(
            "Food", "Travel", "Shopping", "Electronics", "Fashion", "Beauty",
            "Health", "Entertainment", "Education", "Services"
        )
    }
}
