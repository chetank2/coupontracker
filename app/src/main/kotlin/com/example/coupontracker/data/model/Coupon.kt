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
    val storeNameEvidence: List<String> = emptyList(),

    // Schema v2 (additive; populated only when SchemaVersionFlag.isV2Enabled())
    val redeemCodes: String? = null,        // JSON-encoded array of strings
    val primaryRedeemCode: String? = null,
    val storeUrl: String? = null,
    val offerType: String? = null,          // one of: cashback, discount, freebie, points, unknown

    // Background cleanup metadata
    val cleanupStatus: String = CleanupStatus.NONE,
    val cleanupStartedAt: Date? = null,
    val cleanupFinishedAt: Date? = null,
    val cleanupError: String? = null,
    val lastCleanedBy: String? = null,
    val rawOcrText: String? = null,
    val ocrConfidence: Float? = null,
    val extractionSource: String? = null
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
        return detail.substringAfter(":", "").trim()
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

    object CleanupStatus {
        const val NONE = "NONE"
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val CLEANED = "CLEANED"
        const val FAILED = "FAILED"
    }

    object Status {
        const val ACTIVE = "Active"
        const val USED = "Used"
    }

    object Defaults {
        const val UNKNOWN_STORE = "Unknown Store"
    }

    object ExtractionSource {
        const val OCR_FAST = "OCR_FAST"
        const val QWEN_CLEANED = "QWEN_CLEANED"
        const val OCR_VERIFIED = "OCR_VERIFIED"
        const val VISION_VERIFIED = "VISION_VERIFIED"
        const val USER_EDITED = "USER_EDITED"
    }
}
