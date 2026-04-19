package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Conservative field-level merger for the default+VLM outputs. Rules exist
 * to prevent VLM hallucinations from overwriting trustworthy OCR-anchored
 * values.
 */
object VlmMerger {

    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val GENERIC_PHRASES = listOf("save money", "shop now", "discount", "offer")

    fun merge(primary: JSONObject, vlm: JSONObject, ocrText: String): JSONObject {
        val result = JSONObject(primary.toString())

        mergeRedeemCode(result, vlm, ocrText)
        mergeStoreName(result, vlm)
        mergeExpiryDate(result, vlm)
        mergeDescription(result, vlm)

        if (allKnown(result)) {
            result.put(CouponSchemaKeys.NEEDS_ATTENTION, false)
        }
        return result
    }

    private fun mergeRedeemCode(result: JSONObject, vlm: JSONObject, ocrText: String) {
        if (!isUnknown(result.optString(CouponSchemaKeys.REDEEM_CODE))) return
        val candidate = vlm.optString(CouponSchemaKeys.REDEEM_CODE).trim()
        if (candidate.isBlank() || isUnknown(candidate)) return
        if (!ocrText.uppercase(Locale.US).contains(candidate.uppercase(Locale.US))) return
        result.put(CouponSchemaKeys.REDEEM_CODE, candidate)
    }

    private fun mergeStoreName(result: JSONObject, vlm: JSONObject) {
        if (!isUnknown(result.optString(CouponSchemaKeys.STORE_NAME))) return
        val candidate = vlm.optString(CouponSchemaKeys.STORE_NAME).trim()
        val evidence = vlm.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE)
        if (candidate.isBlank() || isUnknown(candidate)) return
        if (evidence == null || evidence.length() == 0) return
        result.put(CouponSchemaKeys.STORE_NAME, candidate)
        result.put(CouponSchemaKeys.STORE_NAME_SOURCE, "vision")
        result.put(CouponSchemaKeys.STORE_NAME_EVIDENCE, evidence)
    }

    private fun mergeExpiryDate(result: JSONObject, vlm: JSONObject) {
        if (!isUnknown(result.optString(CouponSchemaKeys.EXPIRY_DATE))) return
        val candidate = vlm.optString(CouponSchemaKeys.EXPIRY_DATE).trim()
        if (!parsesToIso(candidate)) return
        result.put(CouponSchemaKeys.EXPIRY_DATE, candidate)
    }

    private fun mergeDescription(result: JSONObject, vlm: JSONObject) {
        val existing = result.optString(CouponSchemaKeys.DESCRIPTION).trim()
        val candidate = vlm.optString(CouponSchemaKeys.DESCRIPTION).trim()
        if (candidate.length <= existing.length) return
        val lc = candidate.lowercase(Locale.US)
        if (GENERIC_PHRASES.any { it == lc }) return
        result.put(CouponSchemaKeys.DESCRIPTION, candidate)
    }

    private fun allKnown(obj: JSONObject): Boolean {
        listOf(
            CouponSchemaKeys.STORE_NAME,
            CouponSchemaKeys.REDEEM_CODE,
            CouponSchemaKeys.EXPIRY_DATE
        ).forEach {
            if (isUnknown(obj.optString(it))) return false
        }
        return true
    }

    private fun isUnknown(v: String?): Boolean =
        v.isNullOrBlank() || v.trim().equals("unknown", ignoreCase = true)

    private fun parsesToIso(v: String): Boolean {
        if (!ISO_DATE.matches(v)) return false
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v) != null }.getOrDefault(false)
    }
}
