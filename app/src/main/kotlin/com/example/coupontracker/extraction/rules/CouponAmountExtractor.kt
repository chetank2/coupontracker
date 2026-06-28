package com.example.coupontracker.extraction.rules

import com.example.coupontracker.data.util.CurrencyUtils
import com.example.coupontracker.data.util.DescriptionUtils
import java.util.Locale
import java.util.regex.Pattern

object CouponAmountExtractor {
    fun extractCashbackDetail(
        text: String,
        logDebug: (String) -> Unit = {},
        logError: (String, Throwable) -> Unit = { _, _ -> }
    ): String? {
        // Look for specific percentage patterns first
        val percentagePatterns = listOf(
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
            Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%")
        )

        for (pattern in percentagePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val raw = matcher.group(0)
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (!raw.isNullOrBlank()) {
                        logDebug("Found percentage discount text: $raw")
                        DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                    }
                    if (amount != null) {
                        DescriptionUtils.formatCashbackDetail(amount, "percent")?.let { return it }
                    }
                } catch (e: Exception) {
                    logError("Error parsing percentage", e)
                }
            }
        }

        // Now check for currency amount patterns
        val amountPatterns = listOf(
            Regex("(?i)(?:upto|up\\s+to|flat|get)\\s*((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP)\\s*)?(\\d+(?:[.,]\\d+)?)"),
            Regex("(?i)((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP))\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:off|cashback|back|discount)"),
            Regex("(?i)(?:save|discount of)\\s*((?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP))\\s*(\\d+(?:[.,]\\d+)?)")
        )

        for (pattern in amountPatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    val raw = match.value
                    val amountText = match.groupValues.getOrNull(2)?.replace(",", "")?.takeIf { it.isNotBlank() }
                    val amount = amountText?.toDoubleOrNull()
                    if (raw.isNotBlank()) {
                        logDebug("Found fixed currency amount text: $raw")
                        DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                    }
                    if (amount != null) {
                        val currencyToken = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                        val currency = CurrencyUtils.detectSymbol(currencyToken) ?: CurrencyUtils.detectSymbol(raw)
                        DescriptionUtils.formatCashbackDetail(amount, "amount", currency)?.let { return it }
                    }
                } catch (e: Exception) {
                    logError("Error parsing amount", e)
                }
            }
        }

        val voucherAmountPattern = Pattern.compile("(?i)(?:up to |voucher up to )(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val voucherAmountMatcher = voucherAmountPattern.matcher(text)
        if (voucherAmountMatcher.find()) {
            try {
                val raw = voucherAmountMatcher.group(0)
                val amount = voucherAmountMatcher.group(1)?.toDoubleOrNull()
                if (!raw.isNullOrBlank()) {
                    logDebug("Found voucher amount text: $raw")
                    DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                }
                if (amount != null) {
                    DescriptionUtils.formatCashbackDetail(amount, "amount", "INR")?.let { return it }
                }
            } catch (e: Exception) {
                logError("Error parsing voucher amount", e)
            }
        }

        // Look for simple currency amounts
        val simpleAmountPattern = Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val simpleAmountMatcher = simpleAmountPattern.matcher(text)
        if (simpleAmountMatcher.find()) {
            try {
                val raw = simpleAmountMatcher.group(0)
                val amount = simpleAmountMatcher.group(1)?.toDoubleOrNull()
                if (!raw.isNullOrBlank()) {
                    logDebug("Found simple cashback amount text: $raw")
                    DescriptionUtils.formatCashbackDetail(raw)?.let { return it }
                }
                if (amount != null) {
                    DescriptionUtils.formatCashbackDetail(amount, "amount", "INR")?.let { return it }
                }
            } catch (e: Exception) {
                logError("Error parsing simple amount", e)
            }
        }

        return null
    }

    fun inferDiscountType(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        val normalized = detail.lowercase(Locale.ROOT)
        return when {
            normalized.contains("%") -> "PERCENTAGE"
            normalized.contains("₹") ||
                normalized.contains("rs") ||
                normalized.contains("inr") ||
                Regex("\\d").containsMatchIn(normalized) -> "AMOUNT"
            else -> null
        }
    }

    fun extractMinimumPurchase(
        text: String,
        logDebug: (String) -> Unit = {},
        logError: (String, Throwable) -> Unit = { _, _ -> }
    ): Double? {
        val patterns = listOf(
            Pattern.compile("(?i)min(?:imum)?\\s+(?:order|purchase)\\s+(?:of)?\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:orders?|purchases?)\\s+above\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)valid\\s+on\\s+(?:orders|purchases)\\s+above\\s*(?:Rs\\.?\\s*|₹\\s*)?(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        logDebug("Found minimum purchase amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    logError("Error parsing minimum purchase amount", e)
                }
            }
        }

        return null
    }

    fun extractMaximumDiscount(
        text: String,
        logDebug: (String) -> Unit = {},
        logError: (String, Throwable) -> Unit = { _, _ -> }
    ): Double? {
        val patterns = listOf(
            Pattern.compile("(?i)max(?:imum)?\\s+(?:discount|cashback)\\s*(?:of)?\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)up\\s+to\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:discount|cashback)\\s+up\\s+to\\s*(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        logDebug("Found maximum discount amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    logError("Error parsing maximum discount amount", e)
                }
            }
        }

        return null
    }
}
