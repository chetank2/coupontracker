package com.example.coupontracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.data.util.CurrencyUtils
import com.example.coupontracker.data.util.DescriptionUtils
import java.util.Date

@Entity(tableName = "coupons")
data class Coupon(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val storeName: String,
    val description: String,
    val normalizedDescription: String? = null,
    val expiryDate: Date? = null,
    val redeemCode: String?,
    val imageUri: String?,
    val imagePhash: String? = null,
    val imageSignature: String? = null,
    val category: String? = null,
    val status: String? = null,

    // New fields
    val minimumPurchase: Double? = null,
    val maximumDiscount: Double? = null,
    val isPriority: Boolean = false,
    val paymentMethod: String? = null,
    val usageLimit: Int? = null,
    val usageCount: Int = 0,
    val reminderDate: Date? = null,
    val reminderLeadTimeMinutes: Int? = null,
    val platformType: String? = null,

    // Extraction telemetry
    val extractionQualityScore: Int? = null,
    val extractionConfidenceBreakdown: Map<String, Float> = emptyMap(),
    val extractionStage: String? = null,
    val extractionRunPath: String? = null,
    val extractionTimestamp: Date? = null,

    // Existing tracking fields
    val rating: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),

    // Provenance metadata
    val needsAttention: Boolean = false,
    val storeNameSource: String? = null,
    val storeNameEvidence: List<String> = emptyList()
) {
    fun withAdditionalDetails(vararg details: String?): Coupon {
        val mergedDescription = DescriptionUtils.appendDetails(description, *details)
        return copy(
            description = mergedDescription,
            normalizedDescription = CouponDedupUtils.normalizeDescription(mergedDescription)
        )
    }

    fun getCashbackDisplayText(): String {
        val detail = DescriptionUtils.extractCashbackLine(description) ?: return ""
        return detail.removePrefix("Cashback:").trim()
    }

    fun getCashbackNumericValue(): Double {
        val display = getCashbackDisplayText()
        val percentMatch = Regex("(\\d+(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE).find(display)
        if (percentMatch != null) {
            return percentMatch.groupValues[1].toDoubleOrNull() ?: 0.0
        }
        val amountMatch = Regex("[₹\\$€£]?\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)").find(display)
        if (amountMatch != null) {
            return amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    fun getCashbackInfo(): CashbackInfo {
        val display = getCashbackDisplayText()
        val currencySymbol = CurrencyUtils.detectSymbol(display)
        return when {
            display.contains("%", ignoreCase = true) -> CashbackInfo(CashbackType.PERCENT, getCashbackNumericValue())
            currencySymbol != null ->
                CashbackInfo(CashbackType.AMOUNT, getCashbackNumericValue(), currencySymbol)
            display.isNotBlank() -> CashbackInfo(CashbackType.TEXT, 0.0)
            else -> CashbackInfo(CashbackType.TEXT, 0.0)
        }
    }
}
