package com.example.coupontracker.extraction.rules

import android.util.Log
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.data.util.CurrencyUtils
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.PlatformDetector
import com.example.coupontracker.util.RedeemCodeSanitizer
import com.example.coupontracker.util.StoreCandidateValidator
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
    val cashbackDetail: String? = null,
    val redeemCode: String? = null,
    val category: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val discountType: String? = null,

    // Provenance metadata
    val needsAttention: Boolean = false,
    val storeNameSource: String? = null,
    val storeNameEvidence: List<String> = emptyList(),

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
        if (storeName.isBlank() || storeName == com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE) {
            return false
        }

        // Must have either a code or a descriptive body
        if (redeemCode.isNullOrBlank() && description.isBlank()) {
            return false
        }

        return true
    }

    override fun toString(): String {
        return "CouponInfo(storeName='$storeName', description='$description', " +
               "expiryDate=$expiryDate, cashbackDetail=$cashbackDetail, " +
               "redeemCode=$redeemCode, category=$category, discountType=$discountType, " +
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

    private fun safeLogError(tag: String, message: String, throwable: Throwable) {
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

        val extractionText = prepareFieldExtractionText(text)
        val storeName = extractStoreName(extractionText) ?: extractStoreName(text)
        val scopedText = storeName
            ?.let { extractCouponBlockForStore(extractionText, it) }
            ?: extractionText
        val expiryDate = extractExpiryDate(scopedText, baseDate)
            ?: extractExpiryDate(extractionText, baseDate)
            ?: extractExpiryDate(text, baseDate)
        val cashbackDetail = extractCashbackDetail(scopedText)
        val redeemCode = extractRedeemCode(scopedText)
            ?: extractCodeBeforeSelectedOffer(extractionText)
            ?: extractCodeBeforeSelectedOffer(text)
            ?: extractRedeemCodeAfterSelectedAnchor(extractionText, storeName)
            ?: extractRedeemCodeAfterSelectedAnchor(text, storeName)
            ?: extractRedeemCodeFromSameCardFallback(extractionText, storeName)
            ?: extractRedeemCodeFromSameCardFallback(text, storeName)
        val description = extractDescription(scopedText, storeName, redeemCode)?.takeIf { it.isNotBlank() }
            ?: extractDescription(extractionText, storeName, redeemCode)?.takeIf { it.isNotBlank() }
            ?: extractDescription(text, storeName, redeemCode)?.takeIf { it.isNotBlank() }
            ?: extractRawOfferDescription(extractionText, redeemCode)
            ?: extractRawOfferDescription(text, redeemCode)
        val category = extractCategory(scopedText)
        val rating = extractRating(scopedText)
        val status = extractStatus(scopedText)

        // Extract new fields
        val minimumPurchase = extractMinimumPurchase(scopedText)
        val maximumDiscount = extractMaximumDiscount(scopedText)
        val paymentMethod = extractPaymentMethod(scopedText)
        val platformType = extractPlatformType(scopedText)
        val usageLimit = extractUsageLimit(scopedText)
        val discountType = inferDiscountType(cashbackDetail)

        val result = CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackDetail = cashbackDetail,
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

    fun extractCouponBlockForStore(text: String, storeName: String): String? {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.size < 3 || storeName.isBlank()) return null

        val storeKey = normalizeBlockKey(storeName)
        if (storeKey.length < 3) return null

        val storeIndices = lines.indices.filter { index ->
            normalizeBlockKey(lines[index]).contains(storeKey)
        }
        if (storeIndices.isEmpty()) return null

        val anchor = storeIndices.maxWithOrNull(
            compareBy<Int> { scoreStoreBlockAnchor(lines[it], storeName) }
                .thenBy { it }
        ) ?: return null

        var start = anchor
        var previous = anchor - 1
        var previousCount = 0
        while (previous >= 0 && previousCount < 5) {
            val line = lines[previous]
            if (isCouponBlockCodeLine(line)) break
            if (isCouponBlockActionLine(line)) break
            if (isCouponBlockChromeLine(line)) break

            start = previous
            previousCount += 1
            if (isCouponBlockExpiryLine(line)) break
            previous -= 1
        }

        var end = anchor
        var next = anchor + 1
        var seenCode = false
        while (next < lines.size && next - anchor <= 8) {
            val line = lines[next]
            if (isCouponBlockExpiryLine(line) && next > anchor + 1) break
            if (isCouponBlockChromeLine(line)) break

            end = next
            if (isCouponBlockCodeLine(line)) {
                seenCode = true
            }
            if (seenCode && isCouponBlockActionLine(line)) break
            next += 1
        }

        if (start == anchor && end == anchor) return null
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun scoreStoreBlockAnchor(line: String, storeName: String): Int {
        val normalized = normalizeBlockKey(line)
        val storeOnly = normalized == normalizeBlockKey(storeName)
        val offerContext = Pattern.compile(
            "(?i)\\b(won|get|save|flat|off|cashback|discount|membership|free|upto|up\\s+to|at\\s+just)\\b|₹|rs\\.?",
            Pattern.UNICODE_CASE
        ).matcher(line).find()
        val ratingLike = Pattern.compile("^\\s*[0-5](?:[.,]\\d{1,2})?\\s*$").matcher(line).find()

        return buildList {
            if (offerContext) add(8)
            if (!storeOnly) add(3)
            if (ratingLike) add(-4)
            add(line.length.coerceAtMost(80) / 20)
        }.sum()
    }

    private fun normalizeBlockKey(value: String): String {
        return CouponTextBlocks.normalizeKey(value)
    }

    private fun isCouponBlockCodeLine(line: String): Boolean {
        return CouponTextBlocks.isCodeLine(line)
    }

    private fun isCouponBlockExpiryLine(line: String): Boolean {
        return CouponTextBlocks.isExpiryLine(line)
    }

    private fun isCouponBlockActionLine(line: String): Boolean {
        return CouponTextBlocks.isActionLine(line)
    }

    private fun isCouponBlockChromeLine(line: String): Boolean {
        return CouponTextBlocks.isChromeLine(line)
    }

    private fun prepareFieldExtractionText(text: String): String {
        return CouponTextBlocks.prepareFieldText(text)
    }

    private fun trimLeadingPreviousCouponTail(text: String): String {
        return CouponTextBlocks.trimLeadingPreviousCouponTail(text)
    }

    private fun looksLikeSelectedCardOfferLine(line: String): Boolean {
        return CouponTextBlocks.looksLikeSelectedCardOfferLine(line)
    }

    private fun extractRedeemCodeFromSameCardFallback(text: String, storeName: String?): String? {
        val prepared = prepareFieldExtractionText(text)
        if (!canUseWholeTextCodeFallback(prepared, storeName)) {
            return null
        }
        return extractRedeemCode(prepared)
    }

    private fun extractCodeBeforeSelectedOffer(text: String): String? {
        val lines = prepareFieldExtractionText(text).lines().map { it.trim() }.filter { it.isNotBlank() }
        val offerIndex = lines.indexOfFirst(::looksLikeSelectedCardOfferLine)
        if (offerIndex <= 0) return null
        val windowStart = ((offerIndex - 1) downTo 0)
            .firstOrNull { index ->
                isCouponBlockExpiryLine(lines[index]) ||
                    isCouponBlockActionLine(lines[index]) ||
                    isCouponBlockChromeLine(lines[index])
            }
            ?.plus(1)
            ?: 0
        return findCodeInLines(lines, windowStart, offerIndex)
    }

    private fun extractRedeemCodeAfterSelectedAnchor(text: String, storeName: String?): String? {
        val prepared = prepareFieldExtractionText(text)
        val lines = prepared.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val storeKey = storeName?.let(::normalizeBlockKey).orEmpty()
        val anchorIndex = lines.indexOfFirst { line ->
            (storeKey.isNotBlank() && normalizeBlockKey(line).contains(storeKey)) ||
                looksLikeSelectedCardOfferLine(line)
        }
        if (anchorIndex < 0) return null

        val windowStart = ((anchorIndex - 1) downTo 0)
            .firstOrNull { index ->
                isCouponBlockExpiryLine(lines[index]) ||
                    isCouponBlockActionLine(lines[index]) ||
                    isCouponBlockChromeLine(lines[index])
            }
            ?.plus(1)
            ?: 0
        findCodeInLines(lines, windowStart, anchorIndex)?.let { return it }

        for (index in anchorIndex..lines.lastIndex) {
            val line = lines[index]
            if (isCouponBlockExpiryLine(line)) break
            if (!line.contains("code", ignoreCase = true)) continue
            extractCodeAtLine(lines, index)?.let { return it }
        }

        val scoped = lines.drop(anchorIndex).joinToString("\n")
        return extractRedeemCode(scoped)
    }

    private fun findCodeInLines(lines: List<String>, start: Int, endInclusive: Int): String? {
        return CouponCodeExtractor.findInLines(lines, start, endInclusive)
    }

    private fun extractCodeAtLine(lines: List<String>, index: Int): String? {
        return CouponCodeExtractor.extractAtLine(lines, index)
    }

    private fun canUseWholeTextCodeFallback(text: String, storeName: String?): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val codeIndex = lines.indexOfFirst(::isCouponBlockCodeLine)
        if (codeIndex < 0) return false

        val storeKey = storeName?.let(::normalizeBlockKey).orEmpty()
        val anchorIndex = lines.indexOfFirst { line ->
            (storeKey.isNotBlank() && normalizeBlockKey(line).contains(storeKey)) ||
                looksLikeSelectedCardOfferLine(line)
        }

        if (anchorIndex >= 0 && codeIndex < anchorIndex) {
            return false
        }

        return true
    }

    private fun isPhoneStatusBarNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        val normalized = trimmed.replace("\\s+".toRegex(), " ")
        val lower = normalized.lowercase(Locale.ROOT)
        if (Regex("^\\d{1,2}:\\d{2}$").matches(normalized)) return true
        if (Regex("(?i)^(?:yo|vo|volte)?\\s*5g\\s*\\d{0,3}%?\\s*[a-z]?$").matches(normalized)) return true
        if (Regex("(?i)\\b(?:vo|yo|volte)\\s*5g\\b").containsMatchIn(normalized) && normalized.length <= 16) return true
        if (Regex("^\\d{1,3}%$").matches(normalized)) return true
        if (lower in setOf("5g", "4g", "lte", "volte", "vo 5g", "yo 5g")) return true
        return false
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
            val brand = cleanCandidate(brandMatcher.group(1), text)
            if (StoreCandidateValidator.isAcceptable(brand, text)) {
                safeLogDebug(TAG) { "Found brand from 'Brand:' pattern: $brand" }
                return brand
            }
        }

        extractStoreFromOfferPhrase(text)?.let { offerStore ->
            safeLogDebug(TAG) { "Found store name from offer phrase: $offerStore" }
            return offerStore
        }

        extractStoreFromCommercialPhrase(text)?.let { commercialStore ->
            safeLogDebug(TAG) { "Found store name from commercial phrase: $commercialStore" }
            return commercialStore
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
        val lastOccurrence = mutableMapOf<String, Int>()
        val lines = text.lines()
        val firstCouponSignalIndex = lines.indexOfFirst { isCouponSignalLine(it) }
            .takeIf { it >= 0 }
        val hasWalletChrome = lines.any { isWalletChromeLine(it) }

        fun addCandidate(raw: String?, isTitleCase: Boolean, lineIndex: Int, line: String) {
            if (isCouponBlockCodeLine(line)) {
                return
            }
            if (OfferTextQuality.isLegalOrSupportNoise(line)) {
                return
            }
            val candidate = cleanCandidate(raw, text) ?: return
            if (!StoreCandidateValidator.isAcceptable(candidate, text)) {
                return
            }
            val normalized = candidate.lowercase(Locale.ROOT)
            if (COMMON_WORDS.contains(normalized)) {
                return
            }

            val baseScore = if (isTitleCase) 4.0 else 2.0
            val frequency = wordFrequency[normalized] ?: 1
            val frequencyScore = if (frequency > 1) frequency * 1.5 else 0.5
            val lineBonus = (lines.size - lineIndex).coerceAtLeast(1)
            val firstIndex = lowerText.indexOf(normalized)
            val positionScore = if (firstIndex >= 0 && text.isNotEmpty()) {
                val normalizedPos = firstIndex.toDouble() / text.length.toDouble()
                (1.0 - normalizedPos) * 2.0
            } else {
                0.0
            }

            val lineWordCount = line.split("\\s+".toRegex()).count { it.isNotBlank() }
            val headingBonus = if (lineWordCount <= 3) 3.0 else 0.0

            val contextPenalty = when {
                line.contains("redeem", ignoreCase = true) -> 3.0
                line.contains("exclusive", ignoreCase = true) -> 1.5
                line.contains("plan", ignoreCase = true) || line.contains("offer", ignoreCase = true) -> 1.0
                line.contains("claim", ignoreCase = true) -> 2.5
                else -> 0.0
            }

            val shortNamePenalty = if (candidate.length <= 4 && isTitleCase) 2.0 else 0.0
            val offerPhraseBonus = if (line.contains(Regex("(?i)\\b(?:from|on|at)\\s+(?:the\\s+)?[\\p{L}\\p{M}\\p{N}&.'-]+"))) 1.25 else 0.0
            val walletHeaderPenalty = if (
                hasWalletChrome &&
                firstCouponSignalIndex != null &&
                lineIndex < firstCouponSignalIndex &&
                !line.contains(Regex("(?i)\\b(?:won|off|cashback|discount|code|expires?|valid)\\b"))
            ) {
                12.0
            } else {
                0.0
            }

            val totalScore = baseScore + frequencyScore + lineBonus + positionScore + headingBonus + offerPhraseBonus -
                contextPenalty - shortNamePenalty - walletHeaderPenalty
            val currentScore = candidateScores[normalized]
            val currentCandidate = candidateOriginal[normalized]
            val shouldPreserveAllCapsLogo = currentScore != null &&
                currentCandidate != null &&
                looksLikeAllCapsLogo(currentCandidate) &&
                !looksLikeAllCapsLogo(candidate) &&
                totalScore <= currentScore + ALL_CAPS_LOGO_PREFERENCE_MARGIN
            if (!shouldPreserveAllCapsLogo && (currentScore == null || totalScore > currentScore)) {
                candidateScores[normalized] = totalScore
                candidateOriginal[normalized] = candidate
                lastOccurrence[normalized] = lineIndex
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
        val allCapsPattern = Pattern.compile("\\b([\\p{Lu}]{3,}\\d+(?:[.,]\\d+)?|[\\p{Lu}\\p{N}]{3,})\\b", Pattern.UNICODE_CASE)

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
            val bestKey = candidateScores.entries.sortedWith(
                compareByDescending<Map.Entry<String, Double>> { it.value }
                    .thenBy { lastOccurrence[it.key] ?: Int.MAX_VALUE }
            ).first().key
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
                val name = cleanCandidate(storeMatcher.group(1), text) ?: continue
                if (!StoreCandidateValidator.isAcceptable(name, text)) continue
                safeLogDebug(TAG) { "Found store name from pattern: $name" }
                return name
            }
        }

        return null
    }

    private fun isCouponSignalLine(line: String): Boolean {
        return line.contains(
            Regex("(?i)\\b(?:you\\s+won|won|off|cashback|discount|code\\s*[:\\-–—]?|expires?\\s+in|valid\\s+(?:till|until))\\b")
        )
    }

    private fun isWalletChromeLine(line: String): Boolean {
        return line.contains(Regex("(?i)\\b(?:vouchers?|active|lifetime)\\b"))
    }

    private fun looksLikeAllCapsLogo(value: String): Boolean {
        val letters = value.filter { it.isLetter() }
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    private fun extractStoreFromCommercialPhrase(text: String): String? {
        val patterns = listOf(
            Pattern.compile(
                "(?i)\\bfrom\\s+((?:the\\s+)?[\\p{L}\\p{M}\\p{N}&.'-]+(?:\\s+[\\p{L}\\p{M}\\p{N}&.'-]+){0,4})\\s+(?:website|app|store|site)\\b",
                Pattern.UNICODE_CASE
            ),
            Pattern.compile(
                "(?i)\\b(?:on|at)\\s+((?:the\\s+)?[\\p{L}\\p{M}\\p{N}&.'-]+(?:\\s+[\\p{L}\\p{M}\\p{N}&.'-]+){0,4})\\s+(?:website|app|store|site)\\b",
                Pattern.UNICODE_CASE
            ),
            Pattern.compile(
                "(?i)\\bby\\s+([\\p{Lu}][\\p{L}\\p{M}\\p{N}&.'-]{2,}(?:\\s+[\\p{Lu}][\\p{L}\\p{M}\\p{N}&.'-]{2,}){0,2})\\s+(?:worth|for)\\b",
                Pattern.UNICODE_CASE
            )
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val candidate = cleanCandidate(matcher.group(1), text) ?: continue
                if (StoreCandidateValidator.isAcceptable(candidate, text)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun extractStoreFromOfferPhrase(text: String): String? {
        val patterns = listOf(
            Pattern.compile(
                "\\b(?i:off|cashback|discount|bonus|reward|membership|voucher)\\s+(?i:on)\\s+([\\p{Lu}][\\p{L}\\p{M}\\p{N}&.'-]{2,})(?=\\s|$)",
                Pattern.UNICODE_CASE
            ),
            Pattern.compile(
                "\\b(?i:on)\\s+([\\p{Lu}][\\p{L}\\p{M}\\p{N}&.'-]{2,})(?=\\s|$)",
                Pattern.UNICODE_CASE
            ),
            Pattern.compile(
                "\\b(?i:from)\\s+([\\p{Lu}][\\p{L}\\p{M}\\p{N}&.'-]{2,})(?=\\s|$)",
                Pattern.UNICODE_CASE
            )
        )

        val lines = prepareFieldExtractionText(text).lines()
        for (line in lines) {
            if (!line.contains(Regex("(?i)\\b(won|off|cashback|discount|bonus|reward|voucher|membership)\\b"))) {
                continue
            }
            for (pattern in patterns) {
                val matcher = pattern.matcher(line)
                while (matcher.find()) {
                    val candidate = cleanCandidate(matcher.group(1), text) ?: continue
                    if (StoreCandidateValidator.isAcceptable(candidate, text)) {
                        return candidate
                    }
                }
            }
        }

        return null
    }

    private fun inferDiscountType(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        val normalized = detail.lowercase(Locale.ROOT)
        return when {
            normalized.contains("%") -> "PERCENTAGE"
            normalized.contains("₹") ||
                normalized.contains("rs") ||
                normalized.contains("inr") ||
                Regex("\\d").containsMatchIn(normalized) -> "AMOUNT"
            else -> null
        }
    }

    private fun cleanCandidate(raw: String?, fullText: String? = null): String? {
        val initial = raw?.trim()?.takeIf { it.length >= 3 } ?: return null
        val normalizedInitial = stripNumericCounterArtifact(initial, fullText)
        val tokens = normalizedInitial.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .toMutableList()
        if (tokens.isEmpty()) {
            return null
        }

        while (tokens.isNotEmpty() &&
            GENERIC_LEADING_STORE_TOKENS.contains(tokens.first().lowercase(Locale.ROOT))
        ) {
            tokens.removeAt(0)
        }

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

        while (builder.isNotEmpty() && GENERIC_TRAILING_TOKENS.contains(builder.last().uppercase(Locale.ROOT))) {
            builder.removeAt(builder.lastIndex)
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
        val allCapsAcronym = cleaned
            .filter { it.isLetterOrDigit() }
            .let { compact ->
                compact.length in 3..8 &&
                    compact.any(Char::isLetter) &&
                    compact.all { !it.isLetter() || it.isUpperCase() }
            }

        if (!allCapsAcronym && !lower.any { it in "aeiouy" }) {
            return null
        }

        val trailingConsonants = lower.takeLastWhile { it.isLetter() && it !in "aeiouy" }
        if (!allCapsAcronym && trailingConsonants.length >= 3) {
            return null
        }

        return cleaned
    }

    private fun stripNumericCounterArtifact(candidate: String, fullText: String?): String {
        val matcher = STORE_COUNTER_ARTIFACT_PATTERN.matcher(candidate)
        if (!matcher.matches()) {
            return candidate
        }

        val prefix = matcher.group(1) ?: return candidate
        val suffix = matcher.group(2) ?: return candidate
        val hasDecimalCounter = suffix.contains('.') || suffix.contains(',')
        val prefixAppearsStandalone = fullText
            ?.let {
                Pattern.compile("\\b${Pattern.quote(prefix)}\\b", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                    .matcher(it)
                    .find()
            }
            ?: false

        return if (hasDecimalCounter || prefixAppearsStandalone) prefix else candidate
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        return extractDescription(text, storeName = null, redeemCode = null)
    }

    private fun extractDescription(text: String, storeName: String?, redeemCode: String?): String? {
        val descriptionText = prepareDescriptionExtractionText(text, storeName, redeemCode)
        // FIRST: Check for multi-line "buy X get Y" patterns (high priority)
        val lines = descriptionText.lines().map { it.trim() }.filter { it.isNotEmpty() }
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

        val multiLineWonOffer = extractMultiLineWonOffer(lines)
        if (multiLineWonOffer != null) {
            safeLogDebug(TAG) { "Found multi-line won offer description: $multiLineWonOffer" }
            sanitizeDescription(multiLineWonOffer)?.let { return it }
        }

        // Look for "Offer:" pattern
        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(descriptionText)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Offer:' pattern: $offer" }
            sanitizeDescription(offer)?.let { return it }
        }

        // Look for "You won X products at ₹Y + ₹Z cashback" pattern
        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(descriptionText)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'You won products' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        val wonOfferPattern = Pattern.compile("(?i)(You\\s+won\\s+.+?)(?=\\n|$)")
        val wonOfferMatcher = wonOfferPattern.matcher(descriptionText)
        if (wonOfferMatcher.find()) {
            val desc = wonOfferMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'You won' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Special case for coupons with "Get upto ₹X" pattern
        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(descriptionText)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            safeLogDebug(TAG) { "Found description from 'Get upto' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Up to X% off" pattern
        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(descriptionText)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Up to X%' pattern: $desc" }
            sanitizeDescription(desc)?.let { return it }
        }

        // Look for "Flat ₹X OFF" pattern
        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(descriptionText)
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

        val scoredLineCandidate = lines
            .mapNotNull { line ->
                val candidate = buildOfferLineCandidate(lines, lines.indexOf(line))
                candidate?.let { it to OfferTextQuality.score(it) }
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 4 }
            ?.first
        if (scoredLineCandidate != null) {
            safeLogDebug(TAG) { "Found description from scored offer line: $scoredLineCandidate" }
            addCandidate(scoredLineCandidate)
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
            Pattern.compile("(?i)(extra\\s+\\d+%\\s+off.{0,80})"),
            Pattern.compile("(?i)(.{0,80}\\bat\\s+just\\s+(?:₹|rs\\.?)\\s*\\d+[\\d,]*(?:\\.\\d+)?\\b.{0,40})")
        )

        for (pattern in discountPatterns) {
            val matcher = pattern.matcher(descriptionText)
            while (matcher.find()) {
                val desc = matcher.group(1)
                safeLogDebug(TAG) { "Found description from discount pattern: $desc" }
                addCandidate(desc)
            }
        }

        // If no specific discount pattern is found, consider the first sentence
        val sentences = descriptionText.split(Pattern.compile("[.!?]"))
        if (sentences.isNotEmpty() && sentences[0].length > 10) {
            val desc = sentences[0].trim()
            if (!OfferTextQuality.isLikelyDateOrContextNoise(desc)) {
                safeLogDebug(TAG) { "Using first sentence as description: $desc" }
                addCandidate(desc)
            }
        }

        val usefulTextFallback = buildUsefulDescriptionFallback(lines)
        val summaryFallback = buildMonetarySummary(descriptionText, lines)

        val bestCandidate = candidates
            .filter { it.isMeaningfulDescription() }
            .maxWithOrNull(
                compareBy<String> { OfferTextQuality.score(it) }
                    .thenBy { it.length }
            )

        if (bestCandidate != null) {
            return refineDescriptionCandidate(bestCandidate, summaryFallback)
        }

        return usefulTextFallback ?: summaryFallback
    }

    private fun extractRawOfferDescription(text: String, redeemCode: String?): String? {
        val regexOffer = Regex(
            "(?is)\\b((?:you\\s+won|buy|get|save|flat|cashback|discount|bonus|reward|voucher|free)\\b.+?)(?=\\n\\s*(?:code\\s*[:\\-–—]?|expires?|expiry|valid|copy|redeem|details)\\b|$)"
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
        sanitizeDescription(regexOffer)
            ?.takeIf { it.isMeaningfulDescription() }
            ?.let { return it }

        val normalizedCode = redeemCode
            ?.let { RedeemCodeSanitizer.sanitizePreserve(it) }
            ?.let(::normalizeCodeKey)
            .orEmpty()
        val lines = prepareFieldExtractionText(text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val start = lines.indexOfFirst { line ->
            Regex("(?i)\\b(?:you\\s+won|buy|get|save|flat|cashback|discount|bonus|reward|voucher|free)\\b")
                .containsMatchIn(line)
        }
        if (start < 0) return null

        val parts = mutableListOf<String>()
        for (index in start..lines.lastIndex) {
            val line = lines[index]
            val normalizedLineCode = normalizeCodeKey(line)
            if (index > start && (
                    isCouponBlockExpiryLine(line) ||
                        isCouponBlockCodeLine(line) ||
                        isCouponBlockActionLine(line) ||
                        isCouponBlockChromeLine(line) ||
                        isStandaloneCodeCandidate(line) ||
                        isRatingLine(line) ||
                        (normalizedCode.isNotBlank() && normalizedLineCode == normalizedCode)
                    )
            ) {
                break
            }
            if (index > start && !isOfferContinuationLine(line) && parts.size >= 2) {
                parts += line
                break
            }
            if (index > start && !isOfferContinuationLine(line) && OfferTextQuality.score(line) < 1 && parts.isNotEmpty()) {
                parts += line
                break
            }
            parts += line
            if (parts.size >= 4) break
        }

        return sanitizeDescription(parts.joinToString(" "))
            ?.takeIf { it.isMeaningfulDescription() }
    }

    private fun prepareDescriptionExtractionText(
        text: String,
        storeName: String? = null,
        redeemCode: String? = null
    ): String {
        val normalizedStore = storeName?.let(::normalizeBlockKey).orEmpty()
        val normalizedCode = redeemCode
            ?.let { RedeemCodeSanitizer.sanitizePreserve(it) }
            ?.let(::normalizeCodeKey)
            .orEmpty()
        val cleanedLines = prepareFieldExtractionText(text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return cleanedLines.mapNotNull { line ->
            val normalizedLine = normalizeBlockKey(line)
            val normalizedLineCode = normalizeCodeKey(line)
            val cut = DESCRIPTION_BOUNDARY_PATTERN.matcher(line)
            val bounded = if (cut.find()) line.substring(0, cut.start()).trim() else line
            if (isCouponBlockCodeLine(line) && bounded.isBlank()) {
                return@mapNotNull null
            }
            if (isCouponBlockActionLine(line) ||
                isCouponBlockChromeLine(line) ||
                isStandaloneCodeCandidate(line) ||
                isRatingLine(line) ||
                isCouponBlockExpiryLine(line) ||
                (normalizedStore.isNotBlank() && normalizedLine == normalizedStore) ||
                (normalizedCode.isNotBlank() && normalizedLineCode == normalizedCode)
            ) {
                return@mapNotNull null
            }
            bounded.takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    private fun buildUsefulDescriptionFallback(lines: List<String>): String? {
        val useful = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { isLikelyStandaloneHeading(it) }

        if (useful.isEmpty()) return null

        val joined = useful.joinToString(" ")
            .replace("\\s+".toRegex(), " ")
            .trim()
        return sanitizeDescription(joined)
            ?.takeIf { it.isMeaningfulDescription() }
    }

    private fun isLikelyStandaloneHeading(line: String): Boolean {
        val words = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size > 2) return false
        if (!line.any { it.isLetter() }) return true
        if (GENERIC_HEADING_PATTERN.matcher(line).find()) return true
        return words.size == 1 && !Regex("(?i)(off|cashback|discount|free|save|won|get|buy|₹|rs\\.?|\\d)").containsMatchIn(line)
    }

    private fun normalizeCodeKey(value: String): String {
        return value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .uppercase(Locale.ROOT)
    }

    private fun extractMultiLineWonOffer(lines: List<String>): String? {
        val start = lines.indexOfFirst { line ->
            Pattern.compile("(?i)^you\\s+won\\b").matcher(line).find()
        }
        if (start < 0) return null

        val parts = mutableListOf<String>()
        for (index in start until lines.size.coerceAtMost(start + 4)) {
            val line = lines[index]
            if (index > start && (
                    isCouponBlockExpiryLine(line) ||
                        isCouponBlockCodeLine(line) ||
                        isCouponBlockActionLine(line) ||
                        isCouponBlockChromeLine(line) ||
                        isStandaloneCodeCandidate(line) ||
                        isRatingLine(line) ||
                        !isOfferContinuationLine(line)
                    )
            ) {
                break
            }
            parts.add(line)
        }

        return parts.joinToString(" ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .takeIf { it.isMeaningfulDescription() }
    }

    private fun isOfferContinuationLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        return Pattern.compile(
            "(?i)^(?:on|for|from|with|above|min(?:imum)?|orders?|products?|plans?|membership|annual)\\b|\\b(?:off|cashback|discount|free|order|orders|above|minimum|min\\.?|₹|rs\\.?)\\b",
            Pattern.UNICODE_CASE
        ).matcher(trimmed).find()
    }

    private fun buildOfferLineCandidate(lines: List<String>, startIndex: Int): String? {
        if (startIndex !in lines.indices) return null
        val first = lines[startIndex].trim()
        if (!OfferTextQuality.isLikelyOfferText(first)) return null
        val parts = mutableListOf(first)
        for (index in (startIndex + 1)..lines.lastIndex) {
            val next = lines[index].trim()
            if (next.isBlank()) continue
            if (
                isCouponBlockExpiryLine(next) ||
                    isCouponBlockCodeLine(next) ||
                    isCouponBlockActionLine(next) ||
                    isCouponBlockChromeLine(next) ||
                    OfferTextQuality.isLegalOrSupportNoise(next) ||
                    isStandaloneCodeCandidate(next) ||
                    isRatingLine(next) ||
                    (OfferTextQuality.isLikelyDateOrContextNoise(next) && !isOfferContinuationLine(next))
            ) {
                break
            }
            if (!isOfferContinuationLine(next) && OfferTextQuality.score(next) < 2) {
                break
            }
            parts += next
            if (parts.size >= 3) break
        }
        return sanitizeDescription(parts.joinToString(" "))
    }

    private fun isStandaloneCodeCandidate(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.contains(' ')) return false
        if (!trimmed.any(Char::isLetter) || !trimmed.any(Char::isDigit)) return false
        return Pattern.compile("^[A-Z0-9][A-Z0-9_-]{5,39}$", Pattern.UNICODE_CASE)
            .matcher(trimmed)
            .matches()
    }

    private fun isRatingLine(line: String): Boolean {
        return Pattern.compile("^\\s*[0-5](?:[.,]\\d{1,2})?\\s*$")
            .matcher(line)
            .matches()
    }

    private fun sanitizeDescription(value: String?): String? {
        val bounded = value?.let { raw ->
            val withoutInlineCode = raw.replace(Regex("(?i)\\b(?:coupon\\s+code|promo\\s+code|code)\\s*[:\\-–—].*$"), "")
                .replace(Regex("""\s+\bDAYS\b\s*$"""), "")
                .replace(Regex("""(?m)^\s*\d+\s*[.)]\s*"""), "")
            val matcher = DESCRIPTION_BOUNDARY_PATTERN.matcher(withoutInlineCode)
            if (matcher.find()) withoutInlineCode.substring(0, matcher.start()) else withoutInlineCode
        }
        val cleaned = LocalLlmOcrService.cleanDescription(bounded)
            .let(::normalizeCommercialPriceOffer)
        return cleaned.ifBlank { null }
    }

    private fun normalizeCommercialPriceOffer(value: String): String {
        if (value.isBlank()) return value
        val correctedSalePrice = WORTH_FOR_RUPEE_ARTIFACT_PATTERN.replace(value) { match ->
            val worth = match.groupValues[1].replace(",", "").toIntOrNull()
            val saleRaw = match.groupValues[2].replace(",", "")
            val sale = saleRaw.toIntOrNull()
            val saleWithoutArtifact = saleRaw.drop(1).toIntOrNull()
            if (worth != null &&
                sale != null &&
                saleWithoutArtifact != null &&
                sale > worth &&
                saleWithoutArtifact < worth
            ) {
                "worth ₹$worth for ₹$saleWithoutArtifact"
            } else {
                "worth ₹${match.groupValues[1]} for ₹${match.groupValues[2]}"
            }
        }
        return WORTH_FOR_MISSING_RUPEE_PATTERN.replace(correctedSalePrice) { match ->
            val label = match.groupValues[1]
            val amount = match.groupValues[2]
            "$label ₹$amount"
        }.let(::normalizeRupeeGlyphArtifacts)
    }

    private fun normalizeRupeeGlyphArtifacts(value: String): String {
        if (value.isBlank()) return value
        return RUPEE_GLYPH_AMOUNT_ARTIFACT_PATTERN.replace(value) { match ->
            val prefix = match.groupValues[1]
            val rawAmount = match.groupValues[2].replace(",", "")
            val suffix = match.groupValues[3]
            val repaired = repairRupeeGlyphAmount(rawAmount)
            if (repaired != null) {
                "$prefix ₹$repaired$suffix"
            } else {
                match.value
            }
        }
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

        buildCombinedOffCashbackSummary(lines)?.let { return it }

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

    private fun buildCombinedOffCashbackSummary(lines: List<String>): String? {
        for (index in lines.indices) {
            val current = lines[index].trim()
            if (current.isBlank()) continue
            val next = lines.getOrNull(index + 1)?.trim().orEmpty()
            val combined = normalizeRupeeGlyphArtifacts("$current $next")
                .replace(Regex("""\s+"""), " ")
                .replace("*", "")
                .trim()
            val match = OFF_PLUS_CASHBACK_PATTERN.find(combined) ?: continue
            val offAmount = match.groupValues[1]
            val cashbackAmount = match.groupValues[2]
            return "Flat ₹$offAmount Off + ₹$cashbackAmount Cashback"
        }
        return null
    }

    private fun extractDominantAmount(line: String): Int? {
        var best: Int? = null
        val matcher = DIGIT_RUN_PATTERN.matcher(line)
        while (matcher.find()) {
            val raw = matcher.group(1)?.replace(",", "") ?: continue
            val repaired = repairRupeeGlyphAmount(raw)
            val value = (repaired ?: raw).toIntOrNull() ?: continue
            if (value < 50) continue
            if (best == null || value > best) {
                best = value
            }
        }
        return best
    }

    private fun repairRupeeGlyphAmount(raw: String): String? {
        if (!raw.startsWith("7") || raw.length !in 3..4) return null
        val repaired = raw.drop(1)
        val amount = repaired.toIntOrNull() ?: return null
        if (amount < 50) return null
        return repaired
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
        if (!GenericFieldHeuristics.isMeaningfulDescription(this)) {
            return false
        }

        val normalized = trim()

        val hasAlphaNumeric = normalized.any { it.isLetterOrDigit() }
        if (!hasAlphaNumeric) {
            return false
        }

        if (looksLikeDashboardStats(normalized)) {
            return false
        }

        if (OfferTextQuality.isLikelyDateOrContextNoise(normalized)) {
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
    fun extractCashbackDetail(text: String): String? {
        // Look for specific percentage patterns first
        val percentagePatterns = listOf(
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
            Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%")
        )

        for (pattern in percentagePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val raw = matcher.group(0)
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (!raw.isNullOrBlank()) {
                        safeLogDebug(TAG) { "Found percentage discount text: $raw" }
                        DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                    }
                    if (amount != null) {
                        DescriptionUtils.formatCashbackDetail(amount, "percent")?.let { return it }
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing percentage", e)
                }
            }
        }

        // Now check for currency amount patterns
        val amountPatterns = listOf(
            Regex("(?i)(?:upto|up\\s+to|flat|get)\\s*((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP)\\s*)?(\\d+(?:[.,]\\d+)?)"),
            Regex("(?i)((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP))\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:off|cashback|back|discount)"),
            Regex("(?i)(?:save|discount of)\\s*((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP))\\s*(\\d+(?:[.,]\\d+)?)")
        )

        for (pattern in amountPatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    val raw = match.value
                    val amountText = match.groupValues.getOrNull(2)?.replace(",", "")?.takeIf { it.isNotBlank() }
                    val amount = amountText?.toDoubleOrNull()
                    if (raw.isNotBlank()) {
                        safeLogDebug(TAG) { "Found fixed currency amount text: $raw" }
                        DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                    }
                    if (amount != null) {
                        val currencyToken = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                        val currency = CurrencyUtils.detectSymbol(currencyToken) ?: CurrencyUtils.detectSymbol(raw)
                        DescriptionUtils.formatCashbackDetail(amount, "amount", currency)?.let { return it }
                    }
                } catch (e: Exception) {
                    safeLogError(TAG, "Error parsing amount", e)
                }
            }
        }

        val voucherAmountPattern = Pattern.compile("(?i)(?:up to |voucher up to )(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val voucherAmountMatcher = voucherAmountPattern.matcher(text)
        if (voucherAmountMatcher.find()) {
            try {
                val raw = voucherAmountMatcher.group(0)
                val amount = voucherAmountMatcher.group(1)?.toDoubleOrNull()
                if (!raw.isNullOrBlank()) {
                    safeLogDebug(TAG) { "Found voucher amount text: $raw" }
                    DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                }
                if (amount != null) {
                    DescriptionUtils.formatCashbackDetail(amount, "amount", "INR")?.let { return it }
                }
            } catch (e: Exception) {
                safeLogError(TAG, "Error parsing voucher amount", e)
            }
        }

        // Look for simple currency amounts
        val simpleAmountPattern = Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val simpleAmountMatcher = simpleAmountPattern.matcher(text)
        if (simpleAmountMatcher.find()) {
            try {
                val raw = simpleAmountMatcher.group(0)
                val amount = simpleAmountMatcher.group(1)?.toDoubleOrNull()
                if (!raw.isNullOrBlank()) {
                    safeLogDebug(TAG) { "Found simple cashback amount text: $raw" }
                    DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                }
                if (amount != null) {
                    DescriptionUtils.formatCashbackDetail(amount, "amount", "INR")?.let { return it }
                }
            } catch (e: Exception) {
                safeLogError(TAG, "Error parsing simple amount", e)
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
        return CouponCodeExtractor.extract(text)
    }

    /**
     * Extract category from text
     * @param text The text to extract from
     * @return The extracted category or null if not found
     */
    fun extractCategory(text: String): String? {
        val lowerText = text.lowercase()

        for (category in CATEGORIES) {
            if (lowerText.contains(category.lowercase())) {
                safeLogDebug(TAG) { "Found category from text: $category" }
                return category
            }
        }

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            if (keywords.any { lowerText.contains(it) }) {
                safeLogDebug(TAG) { "Found category from keyword: $category" }
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
            Pattern.compile("(?i)min(?:imum)?\\s+(?:order|purchase)\\s+(?:of)?\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:orders?|purchases?)\\s+above\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)valid\\s+on\\s+(?:orders|purchases)\\s+above\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)")
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

        val paymentMethods = listOf(
            "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet"
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
            "view", "apply", "tap", "click", "pastm", "patm", "just", "expires",
            "expired", "cashback"
        )

        private val GENERIC_LEADING_STORE_TOKENS = setOf(
            "details", "detail", "coupon", "coupons", "voucher", "vouchers",
            "offer", "offers", "store", "brand", "merchant", "shop"
        )

        private val GENERIC_DESCRIPTION_PATTERN = Pattern.compile("(?i)^(coupon\\s*offer|offer\\s*details|coupon\\s*details|details|offer)")
        private val MONETARY_LINE_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save).*(off|cashback|discount)")
        private val LEADING_AMOUNT_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save)\\s+(\\d[\\d,]{2,})")
        private val DIGIT_RUN_PATTERN = Pattern.compile("(\\d[\\d,]{2,})")
        private val WORTH_FOR_RUPEE_ARTIFACT_PATTERN = Regex(
            "(?i)\\bworth\\s+(?:₹|rs\\.?)?\\s*(\\d[\\d,]{2,})\\s+for\\s+(?:₹|rs\\.?)?\\s*(7\\d{3})\\b"
        )
        private val WORTH_FOR_MISSING_RUPEE_PATTERN = Regex(
            "(?i)\\b(worth|for)\\s+(?!₹|rs\\.?)\\s*(\\d[\\d,]{2,})\\b"
        )
        private val RUPEE_GLYPH_AMOUNT_ARTIFACT_PATTERN = Regex(
            "(?i)((?:\\b(?:flat|up\\s*to|upto|extra|save))|\\+)\\s+(7\\d{2,3})(\\s+(?:off|cashback|discount)\\b)"
        )
        private val OFF_PLUS_CASHBACK_PATTERN = Regex(
            "(?i)\\bflat\\s+(?:₹|rs\\.?)?\\s*(\\d[\\d,]*)\\s+off\\s*\\+\\s*(?:₹|rs\\.?)?\\s*(\\d[\\d,]*)\\s+cashback\\b"
        )
        private val GENERIC_HEADING_PATTERN = Pattern.compile("(?i)(offer|details|coupon|code|cashback)")
        private val RUPEE_VALUE_PATTERN = "(?i)(?:₹|rs\\.?\\s*)?\\d[\\d,]{2,}(?=\\s*(?:cashback|off|discount|\\+|$))".toRegex()
        private val STORE_COUNTER_ARTIFACT_PATTERN =
            Pattern.compile("^([\\p{L}\\p{M}]{3,})(\\d+(?:[.,]\\d+)?)$", Pattern.UNICODE_CASE)
        private const val ALL_CAPS_LOGO_PREFERENCE_MARGIN = 3.0
        private val DESCRIPTION_BOUNDARY_PATTERN = Pattern.compile(
            "(?i)\\b(?:details|offer\\s+details|redeem\\s+now|redeem|copy|copy\\s+code|use\\s+code|apply\\s+code|redeem\\s+code|code\\s*[:\\-–—]?|coupon\\s+code|promo\\s+code|expires?\\s+in|valid\\s+(?:till|until))\\b",
            Pattern.UNICODE_CASE
        )

        private val CATEGORIES = listOf(
            "Food", "Travel", "Shopping", "Electronics", "Fashion", "Beauty",
            "Health", "Entertainment", "Education", "Services"
        )

        private val CATEGORY_KEYWORDS = listOf(
            "Fashion" to listOf(
                "apparel", "clothing", "shirt", "shirts", "t-shirt", "tshirts", "t shirts",
                "polo", "jeans", "dress", "kurta", "footwear", "shoes", "sneakers"
            ),
            "Travel" to listOf(
                "bus", "flight", "flights", "hotel", "hotels", "train", "cab", "ride",
                "ticket", "tickets", "booking", "trip"
            ),
            "Food" to listOf(
                "food", "restaurant", "restaurants", "meal", "pizza", "burger", "dining",
                "grocery", "groceries"
            ),
            "Electronics" to listOf(
                "electronics", "mobile", "phone", "laptop", "headphones", "earbuds",
                "speaker", "charger"
            ),
            "Beauty" to listOf(
                "beauty", "salon", "skin", "skincare", "makeup", "grooming", "hair"
            ),
            "Entertainment" to listOf(
                "movie", "movies", "ott", "stream", "streaming", "subscription", "concert"
            )
        )

        private val GENERIC_TRAILING_TOKENS = setOf(
            "ANNUAL",
            "PLAN",
            "PLANS",
            "OFFER",
            "OFFERS",
            "SALE",
            "DEAL",
            "DEALS",
            "REWARDS",
            "PROGRAM",
            "MEMBERSHIP",
            "SUBSCRIPTION",
            "CARD"
        )
    }
}
