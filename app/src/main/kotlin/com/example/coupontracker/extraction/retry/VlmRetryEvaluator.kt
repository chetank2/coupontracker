package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VlmRetryEvaluator @Inject constructor() {

    companion object {
        const val MIN_OCR_TEXT_CHARS = 20
    }

    fun evaluate(
        canonical: JSONObject,
        ocrText: String,
        ocrSpansCount: Int = 0
    ): List<VlmRetryTrigger> {
        val triggers = mutableListOf<VlmRetryTrigger>()
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.REDEEM_CODE)))
            triggers += VlmRetryTrigger.RedeemCodeMissing
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.STORE_NAME)))
            triggers += VlmRetryTrigger.StoreNameUnknown
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.EXPIRY_DATE)))
            triggers += VlmRetryTrigger.ExpiryMissing
        if (canonical.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false))
            triggers += VlmRetryTrigger.NeedsAttentionFlag
        if (ocrText.length < MIN_OCR_TEXT_CHARS)
            triggers += VlmRetryTrigger.OcrTooShort
        return triggers
    }

    fun shouldRetry(canonical: JSONObject, ocrText: String, ocrSpansCount: Int): Boolean =
        evaluate(canonical, ocrText, ocrSpansCount).isNotEmpty()

    private fun unknownOrBlank(value: String?): Boolean =
        value.isNullOrBlank() || value.trim().equals("unknown", ignoreCase = true)
}
