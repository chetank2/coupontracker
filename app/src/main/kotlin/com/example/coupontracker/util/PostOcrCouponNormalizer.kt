package com.example.coupontracker.util

import com.example.coupontracker.data.util.DescriptionUtils
import java.util.Locale

data class PostOcrCouponNormalization(
    val description: String?,
    val cashbackDetail: String?,
    val needsAttention: Boolean,
    val issues: List<String>,
)

/**
 * Deterministic post-OCR cleanup for user-facing coupon fields.
 *
 * OCR can read useful text while still returning app chrome, repeated CTA labels,
 * and full raw blocks. This class keeps that raw text out of `Coupon.description`
 * by selecting only offer-like lines and explicit terms/details.
 */
object PostOcrCouponNormalizer {

    val expirySynonyms = listOf(
        "expires",
        "expiry",
        "valid till",
        "valid until",
        "ends on",
        "offer ends",
        "valid through",
    )

    val codeSynonyms = listOf(
        "code",
        "coupon code",
        "promo code",
        "apply code",
        "use code",
        "voucher",
    )

    val offerSynonyms = listOf(
        "off",
        "discount",
        "cashback",
        "save",
        "flat",
        "free",
        "up to",
        "upto",
        "extra",
    )

    private val textExtractor = TextExtractor()
    private val cleaner = OcrTextCleaner

    private val codeLineRegex = Regex("""(?i)\b(?:${codeSynonyms.joinToString("|") { Regex.escape(it) }})\b""")
    private val expiryLineRegex = Regex("""(?i)\b(?:${expirySynonyms.joinToString("|") { Regex.escape(it) }})\b""")
    private val offerLineRegex = Regex(
        pattern = """(?i)(\b(?:${offerSynonyms.joinToString("|") { Regex.escape(it) }})\b|\d{1,3}\s*%|₹\s*\d|rs\.?\s*\d|buy\s+\d+\s+get\s+\d+)""",
    )
    private val termsLineRegex = Regex("""(?i)\b(min(?:imum)?\s+order|min(?:imum)?\s+purchase|max(?:imum)?\s+discount|payment|valid on|only on|terms?|t&c|usage limit)\b""")
    private val junkLineRegex = Regex(
        pattern = """(?i)^(copy|copy code|tap to copy|apply|apply now|use now|redeem now|view details|details|terms|about|order now|claim now|got it|ok)$""",
    )
    private val statusBarRegex = Regex("""(?i)\b(?:5g|4g|lte|volte|wifi|battery)\b|^\d{1,2}:\d{2}$|^\d{1,3}%$""")

    fun normalize(
        currentDescription: String,
        ocrText: String?,
        storeName: String?,
        redeemCode: String?,
    ): PostOcrCouponNormalization {
        val cleanedLines = cleanLines(ocrText.orEmpty(), storeName, redeemCode)
        val current = cleanDescriptionLines(currentDescription, storeName, redeemCode)

        val currentOffer = current.bestOfferLine(storeName)
        val extractedOffer = cleanedLines.bestOfferLine(storeName)
            ?: textExtractor.extractDescription(cleanedLines.joinToString("\n"))
                ?.takeIf { isAcceptableDescription(it, storeName, redeemCode) }

        val offer = listOf(currentOffer, extractedOffer)
            .filterNotNull()
            .map(::compactLine)
            .firstOrNull { isAcceptableDescription(it, storeName, redeemCode) }

        val cashbackDetail = listOfNotNull(
            offer?.let(DescriptionUtils::formatCashbackDetail),
            cleanedLines.firstOrNull { line ->
                line.contains("cashback", ignoreCase = true) && isOfferLine(line)
            }?.let(DescriptionUtils::formatCashbackDetail),
        ).firstOrNull { GenericFieldHeuristics.hasMeaningfulCashback(it) }

        val terms = (cleanedLines + current)
            .filter(::isTermsLine)
            .filterNot { it.equals(offer, ignoreCase = true) }
            .take(3)

        val issues = mutableListOf<String>()
        if (offer == null) issues += "missing_clean_offer"
        if (currentDescription.lineSequence().filter { it.isNotBlank() }.count() >= 6) {
            issues += "raw_ocr_description_replaced"
        }

        val description = buildList {
            if (offer != null) add(offer)
            terms.forEach { add(it) }
        }.distinctBy { normalizeKey(it) }
            .joinToString("\n")
            .ifBlank { null }

        return PostOcrCouponNormalization(
            description = description,
            cashbackDetail = cashbackDetail,
            needsAttention = offer == null || issues.isNotEmpty(),
            issues = issues,
        )
    }

    private fun cleanLines(
        rawText: String,
        storeName: String?,
        redeemCode: String?,
    ): List<String> {
        val cleaned = cleaner.cleanForLlmExtraction(rawText).cleanedText
        return cleaned
            .lineSequence()
            .flatMap { it.split("•").asSequence() }
            .mapNotNull { raw ->
                val line = compactLine(raw)
                line.takeIf { isUsefulLine(it, storeName, redeemCode) }
            }
            .distinctBy(::normalizeKey)
            .toList()
    }

    private fun cleanDescriptionLines(
        description: String,
        storeName: String?,
        redeemCode: String?,
    ): List<String> {
        return description
            .lineSequence()
            .mapNotNull { raw ->
                val line = compactLine(raw)
                line.takeIf { isUsefulLine(it, storeName, redeemCode) }
            }
            .distinctBy(::normalizeKey)
            .toList()
    }

    private fun isUsefulLine(
        line: String,
        storeName: String?,
        redeemCode: String?,
    ): Boolean {
        if (line.length < 3) return false
        if (line.length > 140) return false
        if (junkLineRegex.matches(line)) return false
        if (statusBarRegex.containsMatchIn(line)) return false
        val normalized = normalizeKey(line)
        if (normalized == normalizeKey(storeName.orEmpty())) return false
        if (normalized == normalizeKey(redeemCode.orEmpty())) return false
        if (codeLineRegex.containsMatchIn(line) && line.length <= 24) return false
        if (expiryLineRegex.containsMatchIn(line) && !isOfferLine(line)) return false
        if (GenericFieldHeuristics.isGenericOrMissing(line)) return false
        return true
    }

    private fun isAcceptableDescription(
        value: String,
        storeName: String?,
        redeemCode: String?,
    ): Boolean {
        val line = compactLine(value)
        if (!isUsefulLine(line, storeName, redeemCode)) return false
        if (!isOfferLine(line)) return false
        if (line.equals("Coupon offer", ignoreCase = true)) return false
        if (line.equals("Saved coupon", ignoreCase = true)) return false
        return true
    }

    private fun isOfferLine(line: String): Boolean {
        if (!offerLineRegex.containsMatchIn(line)) return false
        if (DescriptionUtils.formatCashbackDetail(line)?.contains("0.0") == true) return false
        return true
    }

    private fun List<String>.bestOfferLine(storeName: String?): String? {
        return asSequence()
            .filter(::isOfferLine)
            .mapIndexed { index, line -> IndexedValue(index, line) }
            .maxWithOrNull(
                compareBy<IndexedValue<String>> { scoreOfferLine(it.value, storeName) }
                    .thenByDescending { -it.index }
            )
            ?.value
    }

    private fun scoreOfferLine(line: String, storeName: String?): Int {
        val normalized = line.lowercase(Locale.ROOT)
        var score = 0
        if (normalized.contains("₹") || normalized.contains("rs")) score += 1
        if (Regex("""\d{1,3}\s*%""").containsMatchIn(normalized)) score += 1
        if (normalized.contains("flat") || normalized.contains("save") || normalized.contains("get")) score += 1
        if (!storeName.isNullOrBlank() && normalized.contains(storeName.lowercase(Locale.ROOT))) score += 3
        if (normalized.contains("extra") || normalized.contains("additional")) score -= 2
        if (normalized.contains("bank") ||
            normalized.contains("card") ||
            normalized.contains("hdfc") ||
            normalized.contains("axis") ||
            normalized.contains("icici") ||
            normalized.contains("sbi") ||
            normalized.contains("payment")
        ) {
            score -= 2
        }
        return score
    }

    private fun isTermsLine(line: String): Boolean = termsLineRegex.containsMatchIn(line)

    private fun compactLine(raw: String): String {
        return raw
            .replace(Regex("""^[•*\-]+\s*"""), "")
            .replace(Regex("""(?i)(?<![A-Z0-9])z\s*(?=\d{2,}(?:[,\d]*)(?:\b|\s))"""), "₹")
            .replace(Regex("""(?i)\b(?:copy|copy code|tap to copy|apply code)\b"""), " ")
            .replace(Regex("""(?i)\bcashback\s*:?\s*0+(?:\.0+)?\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', ',', '-', ':')
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9%₹]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
