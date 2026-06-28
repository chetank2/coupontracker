package com.example.coupontracker.extraction.rules

import android.util.Log
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.RedeemCodeSanitizer
import java.io.Serializable
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
    private val storeNameExtractor = StoreNameExtractor { message ->
        safeLogDebug(TAG) { message }
    }

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
        val redeemCode = RedeemCodeResolver.resolve(
            text = text,
            extractionText = extractionText,
            scopedText = scopedText,
            storeName = storeName
        )
        val description = extractDescription(scopedText, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractDescription(extractionText, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractDescription(text, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractRawOfferDescription(extractionText, redeemCode)
            ?: extractRawOfferDescription(text, redeemCode)
        val metadata = CouponMetadataExtractor.extract(
            text = scopedText,
            cashbackDetail = cashbackDetail,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )

        val result = CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackDetail = cashbackDetail,
            redeemCode = redeemCode,
            category = metadata.category,
            rating = metadata.rating,
            status = metadata.status,
            discountType = metadata.discountType,
            minimumPurchase = metadata.minimumPurchase,
            maximumDiscount = metadata.maximumDiscount,
            paymentMethod = metadata.paymentMethod,
            platformType = metadata.platformType,
            usageLimit = metadata.usageLimit
        )

        safeLogDebug(TAG) { "Extracted coupon info: $result" }
        return result
    }

    fun extractCouponBlockForStore(text: String, storeName: String): String? {
        return CouponBlockSelector.selectForStore(text, storeName)
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

    /**
     * Extract store name from text
     * @param text The text to extract from
     * @return The extracted store name or null if not found
     */
    fun extractStoreName(text: String): String? {
        return storeNameExtractor.extract(text)
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        return extractDescription(text, storeName = null, redeemCode = null, sourceText = text)
    }

    private fun extractDescription(
        text: String,
        storeName: String?,
        redeemCode: String?,
        sourceText: String? = text
    ): String? {
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
            sanitizeDescription(multiLineWonOffer, sourceText)?.let { return it }
        }

        val splitAtPriceOffer = extractSplitAtPriceOffer(lines)
        if (splitAtPriceOffer != null) {
            safeLogDebug(TAG) { "Found split at-price offer description: $splitAtPriceOffer" }
            sanitizeDescription(splitAtPriceOffer, sourceText)?.let { return it }
        }

        // Look for "Offer:" pattern
        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(descriptionText)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Offer:' pattern: $offer" }
            sanitizeDescription(offer, sourceText)?.let { return it }
        }

        // Look for "You won X products at ₹Y + ₹Z cashback" pattern
        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(descriptionText)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'You won products' pattern: $desc" }
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val wonOfferPattern = Pattern.compile("(?i)(You\\s+won\\s+.+?)(?=\\n|$)")
        val wonOfferMatcher = wonOfferPattern.matcher(descriptionText)
        if (wonOfferMatcher.find()) {
            val desc = wonOfferMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'You won' pattern: $desc" }
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        // Special case for coupons with "Get upto ₹X" pattern
        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(descriptionText)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            safeLogDebug(TAG) { "Found description from 'Get upto' pattern: $desc" }
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        // Look for "Up to X% off" pattern
        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(descriptionText)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Up to X%' pattern: $desc" }
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        // Look for "Flat ₹X OFF" pattern
        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(descriptionText)
        if (flatOffMatcher.find()) {
            val desc = flatOffMatcher.group(1)?.trim()
            safeLogDebug(TAG) { "Found description from 'Flat ₹X OFF' pattern: $desc" }
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val candidates = mutableListOf<String>()

        fun addCandidate(raw: String?) {
            val sanitized = sanitizeDescription(raw, sourceText)
            if (sanitized != null && sanitized.isMeaningfulDescription()) {
                candidates.add(sanitized)
            }
        }

        val scoredLineCandidate = lines
            .mapNotNull { line ->
                val candidate = buildOfferLineCandidate(lines, lines.indexOf(line), sourceText)
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
            Pattern.compile("(?i)(get\\s+.{3,80}(?:@|at)\\s*(?:₹|rs\\.?)?\\s*\\d[\\d,]*(?:\\*)?.{0,20})"),
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
        sanitizeDescription(regexOffer, text)
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

        return sanitizeDescription(parts.joinToString(" "), text)
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
                (normalizedStore.isNotBlank() && isStoreLogoPrefixLine(normalizedLine, normalizedStore)) ||
                (normalizedCode.isNotBlank() && normalizedLineCode == normalizedCode)
            ) {
                return@mapNotNull null
            }
            bounded.takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    private fun isStoreLogoPrefixLine(normalizedLine: String, normalizedStore: String): Boolean {
        val words = normalizedLine.split(" ").filter { it.isNotBlank() }
        return words.size == 2 && words.last() == normalizedStore && words.first().length <= 2
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

    private fun extractSplitAtPriceOffer(lines: List<String>): String? {
        for (index in lines.indices) {
            val line = lines[index].trim()
            val next = lines.getOrNull(index + 1)?.trim().orEmpty()
            val amount = Regex("""^(\d{2,6})\s*\*$""").find(next)?.groupValues?.getOrNull(1)
            if (amount != null &&
                Regex("""(?i)\b(?:get|buy)\b.{3,80}(?:@|at)\s*$""").containsMatchIn(line)
            ) {
                return "$line ₹$amount*"
            }
            Regex("""(?i)\bget\b.{3,80}(?:@|at)\s*(?:₹|rs\.?)?\s*\d[\d,]*(?:\*)?""")
                .find(line)
                ?.value
                ?.let { return it }
        }
        return null
    }

    private fun isOfferContinuationLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        return Pattern.compile(
            "(?i)^(?:on|for|from|with|above|min(?:imum)?|orders?|products?|plans?|membership|annual)\\b|\\b(?:off|cashback|discount|free|order|orders|above|minimum|min\\.?|₹|rs\\.?)\\b",
            Pattern.UNICODE_CASE
        ).matcher(trimmed).find()
    }

    private fun buildOfferLineCandidate(
        lines: List<String>,
        startIndex: Int,
        sourceText: String? = null
    ): String? {
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
        return sanitizeDescription(parts.joinToString(" "), sourceText)
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

    private fun sanitizeDescription(value: String?, sourceText: String? = null): String? {
        val bounded = value?.let { raw ->
            val withoutInlineCode = raw.replace(Regex("(?i)\\b(?:coupon\\s+code|promo\\s+code|code)\\s*[:\\-–—].*$"), "")
                .replace(Regex("""\s+\bDAYS\b\s*$"""), "")
                .replace(Regex("""(?m)^\s*\d+\s*[.)]\s*"""), "")
            val matcher = DESCRIPTION_BOUNDARY_PATTERN.matcher(withoutInlineCode)
            if (matcher.find()) withoutInlineCode.substring(0, matcher.start()) else withoutInlineCode
        }
        val cleaned = LocalLlmOcrService.cleanDescription(bounded)
            .replace(Regex("""(?i)^[A-Z][A-Z0-9&.'-]{2,20}\s+(?=(?:you\s+won|buy|get|save|flat|up\s*to|upto)\b)"""), "")
            .replace(Regex("""(?i)(@|at)\s+(?!₹|rs\.?)\s*(\d[\d,]{1,2}|[1-9]\d{2})\s*\*"""), "$1 ₹$2*")
            .replace(Regex("""(?i)\s+only\s+on\s+[\p{L}\p{M}\p{N}'&.\- ]{2,80}\s+website\b.*$"""), "")
            .replace(Regex("""(?i)\s+buy\s+now\b.*$"""), "")
            .let { normalizeCommercialPriceOffer(it, sourceText) }
            .let(::ensureRupeeSymbol)
            .let(::normalizeWalletStoreCounterOffer)
        return cleaned.ifBlank { null }
    }

    private fun normalizeCommercialPriceOffer(value: String, sourceText: String? = null): String {
        if (value.isBlank()) return value
        return WORTH_FOR_MISSING_RUPEE_PATTERN.replace(value) { match ->
            val label = match.groupValues[1]
            val amount = match.groupValues[2]
            "$label ₹$amount"
        }.let { normalizeRupeeGlyphArtifacts(it, sourceText) }
    }

    private fun normalizeWalletStoreCounterOffer(value: String): String {
        if (value.isBlank()) return value
        return WALLET_STORE_COUNTER_ORDER_PATTERN.replace(value) { match ->
            val orderLabel = match.groupValues[1]
            val amount = match.groupValues[2]
            val merchant = match.groupValues[3]
            "$orderLabel above ₹$amount on $merchant"
        }
    }

    private fun normalizeRupeeGlyphArtifacts(value: String, sourceText: String? = null): String {
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
        }.let { normalizeMissingRupeeAmountArtifacts(it, sourceText) }
    }

    private fun normalizeMissingRupeeAmountArtifacts(value: String, sourceText: String? = null): String {
        val splitAmounts = splitRupeeArtifactAmounts(sourceText.orEmpty())
        return MISSING_RUPEE_AMOUNT_ARTIFACT_PATTERN.replace(value) { match ->
            val label = match.groupValues[1]
            val amount = match.groupValues[2]
            val marker = match.groupValues[3]
            val repaired = amount.drop(1)
            if (repaired in splitAmounts) {
                "$label ₹$repaired$marker"
            } else {
                match.value
            }
        }
    }

    private fun splitRupeeArtifactAmounts(rawText: String): Set<String> {
        val lines = rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return buildSet {
            for (index in 0 until lines.lastIndex) {
                val current = lines[index]
                val next = lines[index + 1]
                val hasPriceLabel = Regex("""(?i)(?:\b(?:at|from|for)\s*$|^\s*(?:at|from|for)\s*$)""")
                    .containsMatchIn(current)
                val amount = Regex("""^(\d{2,3})\s*\*$""").find(next)?.groupValues?.getOrNull(1)
                if (hasPriceLabel && amount != null) {
                    add(amount)
                }
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
        return ExpiryDateExtractor.extract(
            text = text,
            baseDate = baseDate,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Parse expiry date from text
     * @param text The text to parse
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The parsed date or null if not found
     */
    fun parseExpiryDate(text: String, baseDate: Date? = null): Date? {
        return ExpiryDateExtractor.parse(
            text = text,
            baseDate = baseDate,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract cashback amount from text
     * @param text The text to extract from
     * @return The extracted cashback amount or null if not found
     */
    fun extractCashbackDetail(text: String): String? {
        return CouponAmountExtractor.extractCashbackDetail(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
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
        return CouponMetadataExtractor.extractCategory(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract rating from text
     * @param text The text to extract from
     * @return The extracted rating or null if not found
     */
    fun extractRating(text: String): String? {
        return CouponMetadataExtractor.extractRating(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract status from text
     * @param text The text to extract from
     * @return The extracted status or null if not found
     */
    fun extractStatus(text: String): String? {
        return CouponMetadataExtractor.extractStatus(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract minimum purchase amount from text
     * @param text The text to extract from
     * @return The extracted minimum purchase amount or null if not found
     */
    fun extractMinimumPurchase(text: String): Double? {
        return CouponAmountExtractor.extractMinimumPurchase(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract maximum discount amount from text
     * @param text The text to extract from
     * @return The extracted maximum discount amount or null if not found
     */
    fun extractMaximumDiscount(text: String): Double? {
        return CouponAmountExtractor.extractMaximumDiscount(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract payment method from text
     * @param text The text to extract from
     * @return The extracted payment method or null if not found
     */
    fun extractPaymentMethod(text: String): String? {
        return CouponMetadataExtractor.extractPaymentMethod(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract platform type from text
     * @param text The text to extract from
     * @return The extracted platform type or null if not found
     */
    fun extractPlatformType(text: String): String? {
        return CouponMetadataExtractor.extractPlatformType(text)
    }

    /**
     * Extract usage limit from text
     * @param text The text to extract from
     * @return The extracted usage limit or null if not found
     */
    fun extractUsageLimit(text: String): Int? {
        return CouponMetadataExtractor.extractUsageLimit(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    companion object {
        private val GENERIC_DESCRIPTION_PATTERN = Pattern.compile("(?i)^(coupon\\s*offer|offer\\s*details|coupon\\s*details|details|offer)")
        private val MONETARY_LINE_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save).*(off|cashback|discount)")
        private val LEADING_AMOUNT_PATTERN = Pattern.compile("(?i)(flat|up\\s*to|upto|extra|save)\\s+(\\d[\\d,]{2,})")
        private val DIGIT_RUN_PATTERN = Pattern.compile("(\\d[\\d,]{2,})")
        private val WORTH_FOR_MISSING_RUPEE_PATTERN = Regex(
            "(?i)\\b(worth|for)\\s+(?!₹|rs\\.?)\\s*(\\d[\\d,]{2,})\\b"
        )
        private val RUPEE_GLYPH_AMOUNT_ARTIFACT_PATTERN = Regex(
            "(?i)((?:\\b(?:flat|up\\s*to|upto|extra|save))|\\+)\\s+(7\\d{2,3})(\\s+(?:off|cashback|discount)\\b)"
        )
        private val MISSING_RUPEE_AMOUNT_ARTIFACT_PATTERN = Regex(
            "(?i)\\b(at|from|for)\\s+(7\\d{3})(\\s*\\*)(?=\\s|$)"
        )
        private val OFF_PLUS_CASHBACK_PATTERN = Regex(
            "(?i)\\bflat\\s+(?:₹|rs\\.?)?\\s*(\\d[\\d,]*)\\s+off\\s*\\+\\s*(?:₹|rs\\.?)?\\s*(\\d[\\d,]*)\\s+cashback\\b"
        )
        private val WALLET_STORE_COUNTER_ORDER_PATTERN = Regex(
            "(?i)\\b(orders?|purchases?)\\s+\\d(?:[.,]\\d+)?\\s+[\\p{L}\\p{M}\\p{N}&.'-]{3,}\\s+above\\s+(?:₹|rs\\.?)?\\s*(\\d[\\d,]*)\\s+on\\s+([\\p{L}\\p{M}\\p{N}&.'-]{3,})\\b"
        )
        private val GENERIC_HEADING_PATTERN = Pattern.compile("(?i)(offer|details|coupon|code|cashback)")
        private val RUPEE_VALUE_PATTERN = "(?i)(?:₹|rs\\.?\\s*)?\\d[\\d,]{2,}(?=\\s*(?:cashback|off|discount|\\+|$))".toRegex()
        private val DESCRIPTION_BOUNDARY_PATTERN = Pattern.compile(
            "(?i)\\b(?:details|offer\\s+details|redeem\\s+now|redeem|copy|copy\\s+code|use\\s+code|apply\\s+code|redeem\\s+code|code\\s*[:\\-–—]?|coupon\\s+code|promo\\s+code|expires?\\s+in|valid\\s+(?:till|until))\\b",
            Pattern.UNICODE_CASE
        )

    }
}
