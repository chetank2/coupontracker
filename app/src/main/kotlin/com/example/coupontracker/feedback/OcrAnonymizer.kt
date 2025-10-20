package com.example.coupontracker.feedback

import java.security.MessageDigest
import java.util.Locale

/**
 * Utility helpers for anonymising OCR payloads before persistence.
 */
object OcrAnonymizer {
    private val whitespaceRegex = Regex("\\s+")
    private val digitRegex = Regex("\\d")

    fun sha256(text: String?): String? {
        val normalized = text?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(normalized.toByteArray())
        return hash.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    fun preview(text: String?, maxLength: Int = 160): String? {
        val normalized = text?.takeIf { it.isNotBlank() } ?: return null
        val collapsed = whitespaceRegex.replace(normalized.trim(), " ")
        val obfuscatedDigits = digitRegex.replace(collapsed) { matchResult ->
            "#".repeat(matchResult.value.length)
        }
        return obfuscatedDigits.take(maxLength)
    }
}
