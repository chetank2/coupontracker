package com.example.coupontracker.util

import java.util.Locale

/**
 * Guards cleanup output against model-only claims.
 *
 * Clean can normalize OCR text, but it must not introduce critical values that
 * are absent from the saved OCR text.
 */
object OcrEvidenceValidator {
    private val tokenRegex = Regex("[a-z0-9]+")

    fun isPhraseSupported(candidate: String?, rawOcr: String?): Boolean {
        if (candidate.isNullOrBlank() || rawOcr.isNullOrBlank()) return false

        val candidateTokens = tokens(candidate)
            .filterNot { it.length <= 2 && !it.any(Char::isDigit) }
        if (candidateTokens.isEmpty()) return false

        val ocrTokens = tokens(rawOcr).toSet()
        val compactCandidate = candidateTokens.joinToString("")
        val compactOcr = tokens(rawOcr).joinToString("")

        if (compactCandidate.length >= 4 && compactOcr.contains(compactCandidate)) {
            return true
        }

        return candidateTokens.all { it in ocrTokens }
    }

    fun unsupportedReason(fieldName: String, candidate: String?): String {
        val value = candidate?.trim().orEmpty()
        return if (value.isBlank()) {
            "Reader could not verify the $fieldName from OCR text."
        } else {
            "Reader could not verify $fieldName '$value' from OCR text."
        }
    }

    private fun tokens(value: String): List<String> {
        return tokenRegex.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .toList()
    }
}
