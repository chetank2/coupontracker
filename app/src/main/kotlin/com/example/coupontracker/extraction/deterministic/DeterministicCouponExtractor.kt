package com.example.coupontracker.extraction.deterministic

import com.example.coupontracker.extraction.region.CouponRegionizer.RegionMode
import com.example.coupontracker.util.IndianDateParser
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Regex-first extractor that produces deterministic coupon fields from OCR text.
 */
class DeterministicCouponExtractor(
    private val storeCanon: StoreCanon,
    private val rewardDropPhrases: List<String>
) {

    data class Result(
        val normalizedText: String,
        val flatText: String,
        val offer: String?,
        val storeCandidate: String?,
        val code: String?,
        val expiryText: String?,
        val expiryDate: LocalDate?,
        val offerMatched: Boolean,
        val codeMatched: Boolean,
        val noCodeRequired: Boolean = false,
        val regionMode: RegionMode = RegionMode.DEFAULT
    ) {
        val hasCriticalFields: Boolean
            get() = !offer.isNullOrBlank() && !storeCandidate.isNullOrBlank()

        fun requiresFallback(): Boolean = !hasCriticalFields
    }

    private val offerPatterns = listOf(
        Regex("""(?i)flat\s*₹?\s*\d+(?:[/-])?\s*off"""),
        Regex("""(?i)upto?\s*₹?\s*\d+%?\s*off"""),
        Regex("""(?i)buy\s*\d+.*?@\s*₹?\s*\d+"""),
        Regex("""(?i)you\s*won\s+.*?worth\s*₹?\s*\d+.*?for\s*₹?\s*\d+""")
    )

    private val codePattern = Regex("""(?=\b[A-Z0-9-]{6,}\b)(?=.*[A-Z])(?=.*\d)[A-Z0-9-]+""")
    private val expiryPattern = Regex("""(?i)(?:expires|valid\s*(?:till|until|by)|ends\s*on)\s*[:\-]?\s*(\d{1,2}\s*[A-Za-z]{3,9}\s*\d{4})""")
    private val relativeExpiryPattern = Regex("""(?i)\bexpires?\s+in\s+(\d+)\s+days?\b""")
    private val codeStoplist = setOf("ONTIME", "NOW", "JOIN", "REDEEM")

    fun extract(rawText: String, mode: RegionMode, captureTimestamp: Date? = null): Result {
        val normalized = normalizeText(rawText, mode)
        val flat = normalized.replace("\n", " ")
        val upperFlat = flat.uppercase(Locale.ROOT)
        val baseDate = captureTimestamp
            ?.toInstant()
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDate()
            ?: LocalDate.now()

        val storeCandidate = storeCanon.findInText(flat)
            ?: storeCanon.findInText(rawText)
        val offerMatch = offerPatterns.firstOrNull { pattern -> pattern.find(flat) != null }
        val offerText = offerMatch?.find(flat)?.value

        val codeCandidate = codePattern.findAll(upperFlat)
            .map { it.value }
            .firstOrNull { value -> value.uppercase(Locale.ROOT) !in codeStoplist }
        val noCodeRequired = hasNoCodeEvidence(flat)
        val expiryMatch = expiryPattern.find(flat)
        val expiryRaw = expiryMatch?.groupValues?.getOrNull(1)
        val relativeExpiryMatch = relativeExpiryPattern.find(flat)
        val relativeExpiryText = relativeExpiryMatch?.value
        val expiryDate = when {
            expiryRaw != null -> IndianDateParser.parseExpiryIST(expiryRaw, baseDate).date
            relativeExpiryMatch != null -> {
                val days = relativeExpiryMatch.groupValues.getOrNull(1)?.toLongOrNull()
                days?.let { baseDate.plusDays(it) }
            }
            else -> null
        }

        return Result(
            normalizedText = normalized,
            flatText = flat,
            offer = offerText?.let { normalizeOffer(it) },
            storeCandidate = storeCandidate,
            code = codeCandidate,
            expiryText = expiryRaw ?: relativeExpiryText,
            expiryDate = expiryDate,
            offerMatched = offerMatch != null,
            codeMatched = codeCandidate != null,
            noCodeRequired = noCodeRequired,
            regionMode = mode
        )
    }

    private fun hasNoCodeEvidence(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Regex("\\bno\\s+code(?:\\s+needed|required)?\\b").containsMatchIn(normalized)
    }

    private fun normalizeText(rawText: String, mode: RegionMode): String {
        val withoutCarriage = rawText.replace("\r", "\n")
        val glyphFixed = replaceGlyphs(withoutCarriage)
        val collapsedWhitespace = glyphFixed.replace(Regex("[\t\u00A0]+"), " ")
        val lines = collapsedWhitespace.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val filteredLines = if (mode == RegionMode.REWARD && rewardDropPhrases.isNotEmpty()) {
            val lowerDrop = rewardDropPhrases.map { it.lowercase(Locale.ROOT) }
            lines.filter { line ->
                val lower = line.lowercase(Locale.ROOT)
                lowerDrop.none { lower.contains(it) }
            }
        } else {
            lines
        }
        return filteredLines.joinToString(separator = "\n")
    }

    private fun replaceGlyphs(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == 'T' && looksLikeRupee(text, index) -> builder.append('₹')
                char == 'O' && isWithinNumber(text, index) -> builder.append('0')
                else -> builder.append(char)
            }
            index++
        }
        return builder.toString()
    }

    private fun looksLikeRupee(text: String, index: Int): Boolean {
        for (i in index + 1 until text.length) {
            val c = text[i]
            if (c.isWhitespace()) continue
            return c.isDigit()
        }
        return false
    }

    private fun isWithinNumber(text: String, index: Int): Boolean {
        val prevIsDigit = index > 0 && text[index - 1].isDigit()
        val nextIsDigit = index + 1 < text.length && text[index + 1].isDigit()
        return prevIsDigit || nextIsDigit
    }

    private fun normalizeOffer(rawOffer: String): String {
        return rawOffer.replace(Regex("""\s+"""), " ").trim()
    }
}
