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
        Log.d(TAG, "Extracting coupon info from text: ${text.take(100)}...")

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
            description = sanitizeDescription(description) ?: "",
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

        Log.d(TAG, "Extracted coupon info: $result")
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
            Log.d(TAG, "Found brand from 'Brand:' pattern: $brand")
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

        fun addCandidate(raw: String?, isTitleCase: Boolean, lineIndex: Int) {
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

            val totalScore = baseScore + frequencyScore + lineBonus + positionScore
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
                addCandidate(token, true, index)
            }

            mergeAdjacentTitleTokens(line, titleTokens)
                .filter { it.contains(' ') }
                .forEach { combined -> addCandidate(combined, true, index) }

            val capsMatcher = allCapsPattern.matcher(line)
            while (capsMatcher.find()) {
                addCandidate(capsMatcher.group(1), false, index)
            }
        }

        if (candidateScores.isNotEmpty()) {
            val bestKey = candidateScores.maxByOrNull { it.value }!!.key
            val bestCandidate = candidateOriginal[bestKey]
            if (bestCandidate != null) {
                Log.d(TAG, "Selected store name candidate: $bestCandidate")
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
                Log.d(TAG, "Found store name from pattern: $name")
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
        return cleaned.takeIf { it.length >= 3 }
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        // First, try to form a clean description using store name and discount info
        val storeName = extractStoreName(text) ?: ""
        val discountType = extractDiscountType(text)
        val cashbackAmount = extractCashbackAmount(text)

        if (storeName.isNotBlank() && cashbackAmount != null) {
            val amountStr = if (discountType == "PERCENTAGE") {
                "$cashbackAmount%"
            } else {
                "₹$cashbackAmount"
            }

            val discountPhrase = when {
                text.contains("cashback", ignoreCase = true) -> "$amountStr cashback"
                text.contains("flat", ignoreCase = true) -> "Flat $amountStr off"
                discountType == "PERCENTAGE" -> "Up to $amountStr off"
                else -> "$amountStr off"
            }

            val cleanDescription = "$storeName Coupon - $discountPhrase"
            Log.d(TAG, "Created clean description: $cleanDescription")
            sanitizeDescription(cleanDescription)?.let { return it }
        }

        // If we couldn't create a clean description, fall back to pattern matching

        // Look for "Offer:" pattern
        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(text)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Offer:' pattern: $offer")
            sanitizeDescription(offer)?.let { return it }
        }

        // Look for "You won X products at ₹Y + ₹Z cashback" pattern
        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(text)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'You won products' pattern: $desc")
            sanitizeDescription(desc)?.let { return it }
        }

        // Special case for coupons with "Get upto ₹X" pattern
        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(text)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            Log.d(TAG, "Found description from 'Get upto' pattern: $desc")
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Up to X% off" pattern
        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(text)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Up to X%' pattern: $desc")
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Flat ₹X OFF" pattern
        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(text)
        if (flatOffMatcher.find()) {
            val desc = flatOffMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Flat ₹X OFF' pattern: $desc")
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for discount descriptions
        val discountPatterns = listOf(
            Pattern.compile("(?i)(\\d+%\\s+off.{3,30})"),
            Pattern.compile("(?i)(₹\\d+\\s+off.{3,30})"),
            Pattern.compile("(?i)(Rs\\.?\\s*\\d+\\s+off.{3,30})"),
            Pattern.compile("(?i)(save\\s+\\d+%.{3,30})"),
            Pattern.compile("(?i)(up\\s+to\\s+₹\\d+\\s+off?.{0,30})")
        )

        for (pattern in discountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val desc = matcher.group(1)
                Log.d(TAG, "Found description from discount pattern: $desc")
                sanitizeDescription(desc)?.let { return it }
            }
        }

        // If no specific discount pattern is found, return the first sentence
        val sentences = text.split(Pattern.compile("[.!?]"))
        if (sentences.isNotEmpty() && sentences[0].length > 10) {
            val desc = sentences[0].trim()
            Log.d(TAG, "Using first sentence as description: $desc")
            return sanitizeDescription(desc)
        }

        return null
    }

    private fun sanitizeDescription(value: String?): String? {
        val cleaned = LocalLlmOcrService.cleanDescription(value)
        return cleaned.ifBlank { null }
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
        
        // Check for "Expires in X hours" format
        val expiresInHoursPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+hours?")
        val expiresInHoursMatcher = expiresInHoursPattern.matcher(text)
        if (expiresInHoursMatcher.find()) {
            val hoursToAdd = expiresInHoursMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.time = referenceDate
            calendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            Log.d(TAG, "Found expiry date from 'expires in X hours' format: ${hoursToAdd} hours from base date $referenceDate")
            return calendar.time
        }

        // Check for "Expiry:" format
        val expiryPattern = Pattern.compile("(?i)Expiry:\\s*(.+?)(?=\\n|$)")
        val expiryMatcher = expiryPattern.matcher(text)
        if (expiryMatcher.find()) {
            val expiryText = expiryMatcher.group(1)?.trim() ?: return null

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

                Log.d(TAG, "Found expiry date from 'Expiry:' with 'expires in' format: $timeValue $timeUnit from base date $referenceDate")
                return calendar.time
            }

            // Try to parse the expiry text as a date
            try {
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
                        val date = sdf.parse(expiryText)
                        if (date != null) {
                            Log.d(TAG, "Parsed expiry date from 'Expiry:' field: $expiryText")
                            return date
                        }
                    } catch (e: ParseException) {
                        // Try next pattern
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing expiry date from 'Expiry:' field", e)
            }
        }

        // Check for explicit expiry date in format "Expires: Mar 15, 2025"
        val explicitDatePattern = Pattern.compile("(?i)Expires?:?\\s+(\\w+\\s+\\d{1,2},\\s+\\d{4})")
        val explicitDateMatcher = explicitDatePattern.matcher(text)
        if (explicitDateMatcher.find()) {
            val dateStr = explicitDateMatcher.group(1) ?: return null
            try {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val date = sdf.parse(dateStr)
                Log.d(TAG, "Found expiry date from explicit format: $dateStr")
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
            Log.d(TAG, "Found expiry date from 'expires in X days' format: ${daysToAdd} days from base date $referenceDate")
            return calendar.time
        }

        // Check for standard date formats
        val datePatterns = listOf(
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "MMM dd, yyyy"
        )

        val dateRegexes = listOf(
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{2,4})")
        )

        for (regex in dateRegexes) {
            val matcher = regex.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(1) ?: continue
                for (pattern in datePatterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(dateStr)
                        Log.d(TAG, "Found expiry date from standard format: $dateStr")
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
                val date = sdf.parse(text)
                Log.d(TAG, "Parsed text directly as date: $text")
                return date
            } catch (e: ParseException) {
                // Try next pattern
            }
        }

        // If no expiry date is found, set a default expiry date 30 days from now
        if (text.contains("ABHIBUS", ignoreCase = true)) {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 30)
            Log.d(TAG, "Using default expiry date for ABHIBUS: 30 days from now")
            return calendar.time
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
                        Log.d(TAG, "Found percentage discount: $amount%")
                        // Don't apply any conversion factor for percentages
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing percentage", e)
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
                        Log.d(TAG, "Found fixed currency amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing amount", e)
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
                        Log.d(TAG, "Found Myntra cashback amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Myntra amount", e)
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
                    Log.d(TAG, "Found simple cashback amount: $amount")
                    return amount
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing simple amount", e)
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
                    Log.d(TAG, "Identified discount type: PERCENTAGE")
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
                Log.d(TAG, "Identified discount type: AMOUNT")
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
            Log.d(TAG, "Found code from 'code:' pattern: $code")
            RedeemCodeSanitizer.sanitize(code)?.let { sanitized ->
                return sanitized
            }
        }

        // Look for "code" pattern without colon (common in Myntra coupons)
        val codeWithoutColonPattern = Pattern.compile("(?i)\\bcode\\b\\s*[-–—:]?\\s*([A-Z0-9]{5,})")
        val codeWithoutColonMatcher = codeWithoutColonPattern.matcher(text)
        if (codeWithoutColonMatcher.find()) {
            val code = codeWithoutColonMatcher.group(1)
            Log.d(TAG, "Found code from 'code' pattern without colon: $code")
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
                    Log.d(TAG, "Found Myntra code from specific pattern: $potentialCode")
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
                Log.d(TAG, "Found code from all caps+digits pattern: $potentialCode")
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
                    Log.d(TAG, "Found code after indicator '$indicator': $potentialCode")
                    RedeemCodeSanitizer.sanitize(potentialCode)?.let { sanitized ->
                        return sanitized
                    }
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
            Log.d(TAG, "Setting category to Fashion for XYXX")
            return "Fashion"
        }

        // For Myntra, set category to Fashion
        if (lowerText.contains("myntra")) {
            Log.d(TAG, "Setting category to Fashion for Myntra")
            return "Fashion"
        }

        // For ABHIBUS, set category to Travel
        if (lowerText.contains("abhibus")) {
            Log.d(TAG, "Setting category to Travel for ABHIBUS")
            return "Travel"
        }

        for (category in CATEGORIES) {
            if (lowerText.contains(category.lowercase())) {
                Log.d(TAG, "Found category from text: $category")
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
            Log.d(TAG, "Found rating from 'Rating:' pattern: $rating")
            return rating
        }

        // Look for star rating pattern (e.g., "⭐ 4.31")
        val starRatingPattern = Pattern.compile("(⭐\\s*\\d+\\.\\d+)")
        val starRatingMatcher = starRatingPattern.matcher(text)
        if (starRatingMatcher.find()) {
            val rating = starRatingMatcher.group(1)
            Log.d(TAG, "Found rating from star pattern: $rating")
            return rating
        }

        // Look for numeric rating pattern (e.g., "4.31/5")
        val numericRatingPattern = Pattern.compile("(\\d+\\.\\d+)\\s*/\\s*5")
        val numericRatingMatcher = numericRatingPattern.matcher(text)
        if (numericRatingMatcher.find()) {
            val rating = numericRatingMatcher.group(1)
            Log.d(TAG, "Found rating from numeric pattern: $rating")
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
            Log.d(TAG, "Found status from 'Status:' pattern: $status")
            return status
        }

        // Look for "Available to Redeem" pattern
        if (text.contains("Available to Redeem", ignoreCase = true)) {
            Log.d(TAG, "Found 'Available to Redeem' status")
            return "Available to Redeem"
        }

        // Look for "Redeemed" pattern
        if (text.contains("Redeemed", ignoreCase = true)) {
            Log.d(TAG, "Found 'Redeemed' status")
            return "Redeemed"
        }

        // Look for "Expired" pattern
        if (text.contains("Expired", ignoreCase = true)) {
            Log.d(TAG, "Found 'Expired' status")
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
                        Log.d(TAG, "Found minimum purchase amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing minimum purchase amount", e)
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
                        Log.d(TAG, "Found maximum discount amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing maximum discount amount", e)
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
                Log.d(TAG, "Found payment method: $method")
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
                Log.d(TAG, "Found payment method from common list: $method")
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
                        Log.d(TAG, "Found usage limit: $limit")
                        return limit
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing usage limit", e)
                }
            }
        }

        // Check for "single use" or "one time" phrases
        if (text.contains("single use", ignoreCase = true) ||
            text.contains("one time", ignoreCase = true) ||
            text.contains("once per", ignoreCase = true)) {
            Log.d(TAG, "Found single use limit")
            return 1
        }

        return null
    }

    companion object {
        private val COMMON_WORDS = setOf(
            "the", "and", "for", "with", "off", "use", "get", "code", "coupon",
            "offer", "valid", "till", "from", "upto", "free", "save", "discount",
            "multi", "product", "products", "kit", "combo", "pack", "value", "special"
        )

        private val CATEGORIES = listOf(
            "Food", "Travel", "Shopping", "Electronics", "Fashion", "Beauty",
            "Health", "Entertainment", "Education", "Services"
        )
    }
}