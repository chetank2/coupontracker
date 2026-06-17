package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.extraction.rules.CouponInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Round-trip helper: emit a CouponInfo as the canonical seven-key v1 JSON
 * payload that `VlmRetryRunner` (and `CouponJsonContract`) expects.
 *
 * Rules — match `enforceCanonicalFields` and parser behaviour:
 * - Empty/null string fields become the literal "unknown".
 * - `expiryDate` (Date?) is formatted as `yyyy-MM-dd`, or "unknown" if null.
 * - `storeNameSource` falls back to "unknown" when null/blank.
 * - `storeNameEvidence` is always emitted as a JSON array (possibly empty).
 * - `needsAttention` defaults to its model field; the runner may flip it.
 *
 * Used by the production extraction path to feed the runner without
 * dragging in the LLM response string (which may have been modified by
 * sanitisation since first parse).
 */
fun CouponInfo.toCanonicalJsonString(): String {
    val obj = JSONObject()
    obj.put(CouponSchemaKeys.STORE_NAME, storeName.ifBlank { "unknown" })
    obj.put(CouponSchemaKeys.DESCRIPTION, description.ifBlank { "unknown" })
    obj.put(CouponSchemaKeys.REDEEM_CODE,
        redeemCode?.takeIf { it.isNotBlank() } ?: "unknown")
    obj.put(CouponSchemaKeys.EXPIRY_DATE,
        expiryDate?.let { ISO_DATE_FORMAT.format(it) } ?: "unknown")
    obj.put(CouponSchemaKeys.STORE_NAME_SOURCE,
        storeNameSource?.takeIf { it.isNotBlank() } ?: "unknown")
    obj.put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray(storeNameEvidence))
    obj.put(CouponSchemaKeys.NEEDS_ATTENTION, needsAttention)
    return obj.toString()
}

private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
