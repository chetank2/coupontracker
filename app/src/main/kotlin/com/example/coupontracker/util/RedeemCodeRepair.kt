package com.example.coupontracker.util

object RedeemCodeRepair {
    private const val COMMON_SUFFIX = "SUZY"
    private const val OTTPLAY_MISSING_O = "TTPHONEBUFF"

    fun repair(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sanitized = RedeemCodeSanitizer.sanitize(raw) ?: return null

        // Fix common single-character OCR drops (e.g., SUZY losing the first S)
        if (raw.contains(COMMON_SUFFIX.substring(1), ignoreCase = true) && !sanitized.endsWith(COMMON_SUFFIX)) {
            val candidate = sanitized.dropLast(COMMON_SUFFIX.length - 1) + COMMON_SUFFIX
            if (candidate.length <= sanitized.length + 1) {
                return candidate
            }
        }

        if (sanitized.equals(OTTPLAY_MISSING_O, ignoreCase = true) && raw.contains("PHONEBUFF", ignoreCase = true)) {
            return "O$sanitized"
        }
        return sanitized
    }
}

