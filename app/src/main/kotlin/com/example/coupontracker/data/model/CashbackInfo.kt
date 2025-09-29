package com.example.coupontracker.data.model

/**
 * Represents typed cashback information to distinguish between percentages, amounts, and text.
 * This prevents UI confusion like showing "75%" as "₹75".
 */
data class CashbackInfo(
    val type: CashbackType,
    val valueNum: Double,
    val currency: String = "INR"
) {
    /**
     * Returns a user-friendly display string based on the cashback type.
     */
    fun getDisplayText(): String {
        return when (type) {
            CashbackType.PERCENT -> "${valueNum.toInt()}%"
            CashbackType.AMOUNT -> "₹${valueNum.toInt()}"
            CashbackType.TEXT -> valueNum.toString() // Fallback, should use offer_text
        }
    }

    /**
     * Returns the numeric value for comparison and sorting.
     */
    fun getNumericValue(): Double = valueNum

    companion object {
        /**
         * Creates a CashbackInfo from a legacy cashbackAmount value.
         * Uses heuristics to determine if it's a percentage or amount.
         */
        fun fromLegacyAmount(amount: Double, description: String? = null): CashbackInfo {
            // Heuristic: if amount is <= 100 and description contains "%", treat as percentage
            val isPercent = amount <= 100.0 && 
                           (description?.contains("%") == true || 
                            description?.contains("percent", ignoreCase = true) == true ||
                            description?.contains("off", ignoreCase = true) == true)
            
            return if (isPercent) {
                CashbackInfo(CashbackType.PERCENT, amount)
            } else {
                CashbackInfo(CashbackType.AMOUNT, amount)
            }
        }

        /**
         * Creates a CashbackInfo from a text string (e.g., "75% OFF", "₹500 Back").
         */
        fun fromText(text: String): CashbackInfo {
            val cleanText = text.trim().uppercase()
            
            // Check for percentage
            val percentMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*%").find(cleanText)
            if (percentMatch != null) {
                val value = percentMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                return CashbackInfo(CashbackType.PERCENT, value)
            }
            
            // Check for amount (₹, Rs, INR, etc.)
            val amountMatch = Regex("(?:₹|RS\\.?|INR)\\s*(\\d+(?:,\\d{3})*(?:\\.\\d{2})?)").find(cleanText)
            if (amountMatch != null) {
                val value = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                return CashbackInfo(CashbackType.AMOUNT, value)
            }
            
            // Check for plain number followed by "OFF" or similar
            val numberMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:OFF|BACK|CASHBACK|DISCOUNT)").find(cleanText)
            if (numberMatch != null) {
                val value = numberMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                // If it's a small number, likely percentage; if large, likely amount
                val type = if (value <= 100) CashbackType.PERCENT else CashbackType.AMOUNT
                return CashbackInfo(type, value)
            }
            
            // Fallback: treat as text type with value 0
            return CashbackInfo(CashbackType.TEXT, 0.0)
        }
    }
}

enum class CashbackType {
    PERCENT,  // e.g., 75%
    AMOUNT,   // e.g., ₹500
    TEXT      // Fallback for complex text that can't be parsed
}
