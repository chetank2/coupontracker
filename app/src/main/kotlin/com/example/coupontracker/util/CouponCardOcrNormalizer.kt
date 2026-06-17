package com.example.coupontracker.util

import com.example.coupontracker.ocr.OcrTextSpan
import kotlin.math.abs

/**
 * Converts boxed OCR from a selected coupon crop into field-extraction text.
 *
 * Coupon screenshots often contain text inside the hero/product image. That text is
 * visually above the actual offer body, so plain OCR flattening mixes product labels
 * with merchant, offer, code, and expiry fields. This normalizer keeps structural
 * coupon rows: expiry badge, offer headline block, merchant/rating row, and code row.
 */
object CouponCardOcrNormalizer {
    private val expiryPattern = Regex("""(?i)\bexpires?\s+in\s+\d+\s+(?:hours?|days?)\b|\bexpires?\b""")
    private val codePattern = Regex("""(?i)\b(?:code|coupon\s+code|promo\s+code)\b\s*[:\-–—]?""")
    private val actionPattern = Regex("""(?i)\b(?:details|redeem\s+now|redeem|copy|copy\s+code)\b""")
    private val offerPattern = Regex(
        """(?i)\b(?:you\s+won|get|save|flat|off|cashback|discount|bonus|reward|products?|membership|voucher|essentials?)\b|₹|rs\.?"""
    )
    private val ratingPattern = Regex("""^\s*[0-5](?:[.,]\d{1,2})?\s*$""")

    fun normalize(width: Int, height: Int, spans: List<OcrTextSpan>): String {
        if (width <= 0 || height <= 0 || spans.isEmpty()) return ""

        val filtered = OcrChromeFilter.filter(width, height, spans)
        val lines = groupLines(filtered)
        if (lines.isEmpty()) return ""

        val offerIndex = findOfferStart(lines)
        if (offerIndex < 0) {
            return flattenLines(lines)
        }

        val selected = linkedSetOf<Int>()

        findExpiryForOffer(lines, offerIndex)?.let { selected.add(it) }

        var index = offerIndex
        while (index < lines.size) {
            val text = lines[index].text
            if (index > offerIndex && (
                    expiryPattern.containsMatchIn(text) ||
                        codePattern.containsMatchIn(text) ||
                        actionPattern.containsMatchIn(text.trim())
                    )
            ) {
                break
            }

            selected.add(index)

            val next = lines.getOrNull(index + 1)?.text.orEmpty()
            if (index > offerIndex && !isOfferContinuation(next) && !isLikelyMerchantOrRating(next)) {
                break
            }
            index += 1
        }

        findCodeAfterOffer(lines, offerIndex)?.let { selected.add(it) }

        return selected.sorted()
            .map { lines[it].text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun findOfferStart(lines: List<OcrLine>): Int {
        val lowerBodyStart = lines.maxOfOrNull { it.centerY }?.let { 0 } ?: 0
        return lines.indices
            .filter { index ->
                val line = lines[index]
                val text = line.text
                !expiryPattern.containsMatchIn(text) &&
                    !codePattern.containsMatchIn(text) &&
                    !actionPattern.containsMatchIn(text.trim()) &&
                    offerPattern.containsMatchIn(text)
            }
            .maxWithOrNull(
                compareBy<Int> { scoreOfferLine(lines[it].text) }
                    .thenBy { lines[it].centerY }
            )
            ?: lowerBodyStart - 1
    }

    private fun scoreOfferLine(text: String): Int {
        val lower = text.lowercase()
        var score = 0
        if (Regex("""\byou\s+won\b""").containsMatchIn(lower)) score += 12
        if ("cashback" in lower) score += 8
        if (" off" in lower || "discount" in lower) score += 6
        if ("₹" in text || Regex("""(?i)\brs\.?\b""").containsMatchIn(text)) score += 4
        if (Regex("""\bfrom\s+\p{L}{3,}\b""").containsMatchIn(text)) score += 5
        if (text.length >= 20) score += 2
        return score
    }

    private fun findExpiryForOffer(lines: List<OcrLine>, offerIndex: Int): Int? {
        val before = lines.indices
            .filter { it <= offerIndex && expiryPattern.containsMatchIn(lines[it].text) }
            .maxOrNull()
        if (before != null) return before

        return lines.indices
            .filter { it > offerIndex && expiryPattern.containsMatchIn(lines[it].text) }
            .minOrNull()
    }

    private fun findCodeAfterOffer(lines: List<OcrLine>, offerIndex: Int): Int? {
        return lines.indices
            .filter { it > offerIndex && codePattern.containsMatchIn(lines[it].text) }
            .minOrNull()
    }

    private fun isOfferContinuation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (actionPattern.containsMatchIn(trimmed) || codePattern.containsMatchIn(trimmed) || expiryPattern.containsMatchIn(trimmed)) {
            return false
        }
        return offerPattern.containsMatchIn(trimmed) ||
            Regex("""(?i)^(?:on|from|for|via|with|above|min(?:imum)?|orders?|products?)\b""").containsMatchIn(trimmed)
    }

    private fun isLikelyMerchantOrRating(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (ratingPattern.matches(trimmed)) return true
        if (trimmed.length > 32) return false
        if (trimmed.split(Regex("""\s+""")).size > 3) return false
        return trimmed.any(Char::isLetter)
    }

    private fun groupLines(spans: List<OcrTextSpan>): List<OcrLine> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedWith(compareBy<OcrTextSpan> { centerY(it) }.thenBy { it.boundingBox.left })
        val lineTolerance = 14
        val buckets = mutableListOf<MutableList<OcrTextSpan>>()

        sorted.forEach { span ->
            val bucket = buckets.firstOrNull { existing ->
                abs(centerY(existing.first()) - centerY(span)) <= lineTolerance
            }
            if (bucket != null) {
                bucket.add(span)
            } else {
                buckets.add(mutableListOf(span))
            }
        }

        return buckets.map { bucket ->
            val ordered = bucket.sortedBy { it.boundingBox.left }
            OcrLine(
                text = ordered.joinToString(" ") { it.text.trim() }
                    .replace(Regex("""\s+"""), " ")
                    .trim(),
                centerY = ordered.map(::centerY).average().toInt()
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun flattenLines(lines: List<OcrLine>): String {
        return lines.joinToString("\n") { it.text }
    }

    private fun centerY(span: OcrTextSpan): Int {
        return (span.boundingBox.top + span.boundingBox.bottom) / 2
    }

    private data class OcrLine(
        val text: String,
        val centerY: Int
    )
}
