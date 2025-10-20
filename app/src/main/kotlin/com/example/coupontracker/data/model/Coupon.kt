package com.example.coupontracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "coupons")
data class Coupon(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val storeName: String,
    val description: String,
    val normalizedDescription: String? = null,
    val expiryDate: Date? = null,
    val cashbackAmount: Double, // Legacy field - will be migrated
    val redeemCode: String?,
    
    // New typed cashback fields
    val cashbackType: String? = null, // "percent", "amount", "text"
    val cashbackValueNum: Double? = null, // Numeric value only
    val cashbackCurrency: String? = "INR", // Currency for amounts
    val offerText: String? = null, // Deprecated: kept for migrations; UI should use description instead
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
    val platformType: String? = null,

    // Existing tracking fields
    val extractionConfidenceBreakdown: Map<String, Float> = emptyMap(),
    val rating: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
) {
    /**
     * Gets the typed cashback information, falling back to legacy cashbackAmount if needed.
     */
    fun getCashbackInfo(): CashbackInfo {
        return if (cashbackType != null && cashbackValueNum != null) {
            // Use new typed fields
            val type = when (cashbackType) {
                "percent" -> CashbackType.PERCENT
                "amount" -> CashbackType.AMOUNT
                "text" -> CashbackType.TEXT
                else -> CashbackType.AMOUNT // Default fallback
            }
            CashbackInfo(type, cashbackValueNum, cashbackCurrency ?: "INR")
        } else {
            // Fallback to legacy field with heuristic detection
            CashbackInfo.fromLegacyAmount(cashbackAmount, description)
        }
    }

    /**
     * Gets the display text for cashback, preferring offerText if available.
     */
    fun getCashbackDisplayText(): String {
        return description.takeIf { it.isNotBlank() } ?: getCashbackInfo().getDisplayText()
    }

    /**
     * Gets the numeric value for sorting and comparison.
     */
    fun getCashbackNumericValue(): Double {
        return cashbackValueNum ?: cashbackAmount
    }
}