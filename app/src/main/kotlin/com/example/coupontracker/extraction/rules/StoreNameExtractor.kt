package com.example.coupontracker.extraction.rules

import com.example.coupontracker.extraction.quality.OfferTextQuality
import java.util.Locale
import java.util.regex.Pattern

class StoreNameExtractor(
    private val logDebug: (String) -> Unit = {}
) {
    private val storeCandidatePolicy = StoreCandidatePolicy()

    fun extract(text: String): String? {
        val brandPattern = Pattern.compile("(?i)Brand:\\s*([\\p{L}\\p{M}\\p{N}]+)", Pattern.UNICODE_CASE)
        val brandMatcher = brandPattern.matcher(text)
        if (brandMatcher.find()) {
            val brand = cleanCandidate(brandMatcher.group(1), text)
            if (brand != null && storeCandidatePolicy.isAcceptedStoreCandidate(brand, text)) {
                logDebug("Found brand from 'Brand:' pattern: $brand")
                return brand
            }
        }

        extractStoreFromCommercialPhrase(text)?.let { commercialStore ->
            logDebug("Found store name from commercial phrase: $commercialStore")
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
            if (CouponTextBlocks.isCodeLine(line)) {
                return
            }
            if (OfferTextQuality.isLegalOrSupportNoise(line)) {
                return
            }
            val candidate = cleanCandidate(raw, text) ?: return
            val normalized = candidate.lowercase(Locale.ROOT)
            if (!storeCandidatePolicy.isAcceptedStoreCandidate(candidate, text)) {
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
                logDebug("Selected store name candidate: $bestCandidate")
                return bestCandidate
            }
        }

        extractStoreFromOfferPhrase(text)?.let { offerStore ->
            logDebug("Found store name from offer phrase: $offerStore")
            return offerStore
        }

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
                if (!storeCandidatePolicy.isAcceptedStoreCandidate(name, text)) continue
                logDebug("Found store name from pattern: $name")
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
                if (storeCandidatePolicy.isAcceptedStoreCandidate(candidate, text)) {
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

        val lines = CouponTextBlocks.prepareFieldText(text).lines()
        for (line in lines) {
            if (!line.contains(Regex("(?i)\\b(won|off|cashback|discount|bonus|reward|voucher|membership)\\b"))) {
                continue
            }
            for (pattern in patterns) {
                val matcher = pattern.matcher(line)
                while (matcher.find()) {
                    val candidate = cleanCandidate(matcher.group(1), text) ?: continue
                    if (storeCandidatePolicy.isAcceptedStoreCandidate(candidate, text)) {
                        return candidate
                    }
                }
            }
        }

        return null
    }

    private fun cleanCandidate(raw: String?, fullText: String? = null): String? {
        return storeCandidatePolicy.cleanCandidate(raw, fullText)
    }

    private companion object {
        private const val ALL_CAPS_LOGO_PREFERENCE_MARGIN = 3.0
    }
}
