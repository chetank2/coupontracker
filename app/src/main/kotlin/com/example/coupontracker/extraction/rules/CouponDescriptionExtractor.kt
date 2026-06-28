package com.example.coupontracker.extraction.rules

import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.RedeemCodeSanitizer
import java.util.Locale
import java.util.regex.Pattern

class CouponDescriptionExtractor(
    private val logDebug: (String) -> Unit = {}
) {
    private val storeNameExtractor = StoreNameExtractor(logDebug)

    fun extract(
        text: String,
        storeName: String? = null,
        redeemCode: String? = null,
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
                logDebug("Found multi-line offer description: $combined")
                if (combined.isMeaningfulDescription()) {
                    return combined
                }
            }
        }

        val multiLineWonOffer = extractMultiLineWonOffer(lines)
        if (multiLineWonOffer != null) {
            logDebug("Found multi-line won offer description: $multiLineWonOffer")
            sanitizeDescription(multiLineWonOffer, sourceText)?.let { return it }
        }

        val splitAtPriceOffer = extractSplitAtPriceOffer(lines)
        if (splitAtPriceOffer != null) {
            logDebug("Found split at-price offer description: $splitAtPriceOffer")
            sanitizeDescription(splitAtPriceOffer, sourceText)?.let { return it }
        }

        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(descriptionText)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            logDebug("Found description from 'Offer:' pattern: $offer")
            sanitizeDescription(offer, sourceText)?.let { return it }
        }

        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(descriptionText)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            logDebug("Found description from 'You won products' pattern: $desc")
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val wonOfferPattern = Pattern.compile("(?i)(You\\s+won\\s+.+?)(?=\\n|$)")
        val wonOfferMatcher = wonOfferPattern.matcher(descriptionText)
        if (wonOfferMatcher.find()) {
            val desc = wonOfferMatcher.group(1)?.trim()
            logDebug("Found description from 'You won' pattern: $desc")
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(descriptionText)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            logDebug("Found description from 'Get upto' pattern: $desc")
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(descriptionText)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            logDebug("Found description from 'Up to X%' pattern: $desc")
            sanitizeDescription(desc, sourceText)?.let { return it }
        }

        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(descriptionText)
        if (flatOffMatcher.find()) {
            val desc = flatOffMatcher.group(1)?.trim()
            logDebug("Found description from 'Flat ₹X OFF' pattern: $desc")
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
            logDebug("Found description from scored offer line: $scoredLineCandidate")
            addCandidate(scoredLineCandidate)
        }

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
                logDebug("Found description from discount pattern: $desc")
                addCandidate(desc)
            }
        }

        val sentences = descriptionText.split(Pattern.compile("[.!?]"))
        if (sentences.isNotEmpty() && sentences[0].length > 10) {
            val desc = sentences[0].trim()
            if (!OfferTextQuality.isLikelyDateOrContextNoise(desc)) {
                logDebug("Using first sentence as description: $desc")
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

    fun extractRawOfferDescription(text: String, redeemCode: String?): String? {
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
        val lines = CouponTextBlocks.prepareFieldText(text)
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
                    CouponTextBlocks.isExpiryLine(line) ||
                        CouponTextBlocks.isCodeLine(line) ||
                        CouponTextBlocks.isActionLine(line) ||
                        CouponTextBlocks.isChromeLine(line) ||
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
        val normalizedStore = storeName?.let(CouponTextBlocks::normalizeKey).orEmpty()
        val normalizedCode = redeemCode
            ?.let { RedeemCodeSanitizer.sanitizePreserve(it) }
            ?.let(::normalizeCodeKey)
            .orEmpty()
        val cleanedLines = CouponTextBlocks.prepareFieldText(text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return cleanedLines.mapNotNull { line ->
            val normalizedLine = CouponTextBlocks.normalizeKey(line)
            val normalizedLineCode = normalizeCodeKey(line)
            val cut = DESCRIPTION_BOUNDARY_PATTERN.matcher(line)
            val bounded = if (cut.find()) line.substring(0, cut.start()).trim() else line
            if (CouponTextBlocks.isCodeLine(line) && bounded.isBlank()) {
                return@mapNotNull null
            }
            if (CouponTextBlocks.isActionLine(line) ||
                CouponTextBlocks.isChromeLine(line) ||
                isStandaloneCodeCandidate(line) ||
                isRatingLine(line) ||
                CouponTextBlocks.isExpiryLine(line) ||
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
                    CouponTextBlocks.isExpiryLine(line) ||
                        CouponTextBlocks.isCodeLine(line) ||
                        CouponTextBlocks.isActionLine(line) ||
                        CouponTextBlocks.isChromeLine(line) ||
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
                CouponTextBlocks.isExpiryLine(next) ||
                    CouponTextBlocks.isCodeLine(next) ||
                    CouponTextBlocks.isActionLine(next) ||
                    CouponTextBlocks.isChromeLine(next) ||
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

        val store = storeNameExtractor.extract(text)?.takeIf { it.isNotBlank() } ?: findHeadingStoreFallback(lines)

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

    private companion object {
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
