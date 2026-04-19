package com.example.coupontracker.extraction.multi

import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure converter from a canonical coupon JSON (the seven v1 keys) to a
 * Room `Coupon` entity. Caller supplies non-extraction metadata (uri,
 * timestamp). String fields treat the literal "unknown" as blank.
 *
 * Provenance fields (storeNameSource, storeNameEvidence, needsAttention)
 * are propagated to the Coupon entity's matching columns.
 */
object JsonToCouponConverter {

    private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun convert(
        canonical: JSONObject,
        imageUri: Uri,
        capturedAt: Date = Date()
    ): Coupon {
        val storeName = stringOrEmpty(canonical, CouponSchemaKeys.STORE_NAME)
        val description = stringOrEmpty(canonical, CouponSchemaKeys.DESCRIPTION)
        val redeemCode = stringOrNull(canonical, CouponSchemaKeys.REDEEM_CODE)
        val expiryDate = parseExpiry(canonical.optString(CouponSchemaKeys.EXPIRY_DATE))
        val storeNameSource = stringOrNull(canonical, CouponSchemaKeys.STORE_NAME_SOURCE)
        val storeNameEvidence = parseEvidence(canonical)
        val needsAttention = canonical.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false)

        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            expiryDate = expiryDate,
            imageUri = imageUri.toString(),
            createdAt = capturedAt,
            updatedAt = capturedAt,
            needsAttention = needsAttention,
            storeNameSource = storeNameSource,
            storeNameEvidence = storeNameEvidence
        )
    }

    private fun stringOrEmpty(json: JSONObject, key: String): String {
        val raw = json.optString(key)
        return if (raw.isBlank() || raw.equals("unknown", ignoreCase = true)) "" else raw
    }

    private fun stringOrNull(json: JSONObject, key: String): String? {
        val raw = json.optString(key)
        return if (raw.isBlank() || raw.equals("unknown", ignoreCase = true)) null else raw
    }

    private fun parseExpiry(raw: String?): Date? {
        if (raw.isNullOrBlank() || raw.equals("unknown", ignoreCase = true)) return null
        return try {
            ISO_DATE.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseEvidence(json: JSONObject): List<String> {
        val arr = json.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }
}
