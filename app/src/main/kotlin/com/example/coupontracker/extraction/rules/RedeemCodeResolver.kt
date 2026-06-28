package com.example.coupontracker.extraction.rules

object RedeemCodeResolver {

    fun resolve(
        text: String,
        extractionText: String,
        scopedText: String,
        storeName: String?
    ): String? {
        return extractCodeBeforeSelectedOffer(extractionText)
            ?: extractCodeBeforeSelectedOffer(text)
            ?: extractRedeemCodeAfterSelectedAnchor(extractionText, storeName)
            ?: extractRedeemCodeAfterSelectedAnchor(text, storeName)
            ?: extractRedeemCodeFromSameCardFallback(extractionText, storeName)
            ?: extractRedeemCodeFromSameCardFallback(text, storeName)
            ?: CouponCodeExtractor.extract(scopedText)
    }

    private fun extractRedeemCodeFromSameCardFallback(text: String, storeName: String?): String? {
        val prepared = CouponTextBlocks.prepareFieldText(text)
        if (!canUseWholeTextCodeFallback(prepared, storeName)) {
            return null
        }
        return CouponCodeExtractor.extract(prepared)
    }

    private fun extractCodeBeforeSelectedOffer(text: String): String? {
        val lines = CouponTextBlocks.prepareFieldText(text).lines().map { it.trim() }.filter { it.isNotBlank() }
        val offerIndex = lines.indexOfFirst(CouponTextBlocks::looksLikeSelectedCardOfferLine)
        if (offerIndex <= 0) return null
        val windowStart = ((offerIndex - 1) downTo 0)
            .firstOrNull { index ->
                CouponTextBlocks.isExpiryLine(lines[index]) ||
                    CouponTextBlocks.isActionLine(lines[index]) ||
                    CouponTextBlocks.isChromeLine(lines[index])
            }
            ?.plus(1)
            ?: 0
        return CouponCodeExtractor.findInLines(lines, windowStart, offerIndex)
    }

    private fun extractRedeemCodeAfterSelectedAnchor(text: String, storeName: String?): String? {
        val prepared = CouponTextBlocks.prepareFieldText(text)
        val lines = prepared.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val storeKey = storeName?.let(CouponTextBlocks::normalizeKey).orEmpty()
        val anchorIndex = lines.indexOfFirst { line ->
            (storeKey.isNotBlank() && CouponTextBlocks.normalizeKey(line).contains(storeKey)) ||
                CouponTextBlocks.looksLikeSelectedCardOfferLine(line)
        }
        if (anchorIndex < 0) return null

        val windowStart = ((anchorIndex - 1) downTo 0)
            .firstOrNull { index ->
                CouponTextBlocks.isExpiryLine(lines[index]) ||
                    CouponTextBlocks.isActionLine(lines[index]) ||
                    CouponTextBlocks.isChromeLine(lines[index])
            }
            ?.plus(1)
            ?: 0
        CouponCodeExtractor.findInLines(lines, windowStart, anchorIndex)?.let { return it }

        for (index in anchorIndex..lines.lastIndex) {
            val line = lines[index]
            if (CouponTextBlocks.isExpiryLine(line)) break
            if (!line.contains("code", ignoreCase = true)) continue
            CouponCodeExtractor.extractAtLine(lines, index)?.let { return it }
        }

        val scoped = lines.drop(anchorIndex).joinToString("\n")
        return CouponCodeExtractor.extract(scoped)
    }

    private fun canUseWholeTextCodeFallback(text: String, storeName: String?): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val codeIndex = lines.indexOfFirst(CouponTextBlocks::isCodeLine)
        if (codeIndex < 0) return false

        val storeKey = storeName?.let(CouponTextBlocks::normalizeKey).orEmpty()
        val anchorIndex = lines.indexOfFirst { line ->
            (storeKey.isNotBlank() && CouponTextBlocks.normalizeKey(line).contains(storeKey)) ||
                CouponTextBlocks.looksLikeSelectedCardOfferLine(line)
        }

        if (anchorIndex >= 0 && codeIndex < anchorIndex) {
            return false
        }

        return true
    }
}
