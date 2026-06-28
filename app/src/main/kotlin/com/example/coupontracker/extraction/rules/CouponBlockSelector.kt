package com.example.coupontracker.extraction.rules

import java.util.regex.Pattern

object CouponBlockSelector {
    fun selectForStore(text: String, storeName: String): String? {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.size < 3 || storeName.isBlank()) return null

        val storeKey = CouponTextBlocks.normalizeKey(storeName)
        if (storeKey.length < 3) return null

        val storeIndices = lines.indices.filter { index ->
            CouponTextBlocks.normalizeKey(lines[index]).contains(storeKey)
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
            if (CouponTextBlocks.isCodeLine(line)) break
            if (CouponTextBlocks.isActionLine(line)) break
            if (CouponTextBlocks.isChromeLine(line)) break

            start = previous
            previousCount += 1
            if (CouponTextBlocks.isExpiryLine(line)) break
            previous -= 1
        }

        var end = anchor
        var next = anchor + 1
        var seenCode = false
        while (next < lines.size && next - anchor <= 8) {
            val line = lines[next]
            if (CouponTextBlocks.isExpiryLine(line) && next > anchor + 1) break
            if (CouponTextBlocks.isChromeLine(line)) break

            end = next
            if (CouponTextBlocks.isCodeLine(line)) {
                seenCode = true
            }
            if (seenCode && CouponTextBlocks.isActionLine(line)) break
            next += 1
        }

        if (start == anchor && end == anchor) return null
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun scoreStoreBlockAnchor(line: String, storeName: String): Int {
        val normalized = CouponTextBlocks.normalizeKey(line)
        val storeOnly = normalized == CouponTextBlocks.normalizeKey(storeName)
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
}
