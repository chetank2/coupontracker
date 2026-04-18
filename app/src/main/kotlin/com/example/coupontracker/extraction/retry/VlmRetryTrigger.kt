package com.example.coupontracker.extraction.retry

sealed class VlmRetryTrigger(val code: String) {
    object RedeemCodeMissing : VlmRetryTrigger("redeem_code_missing")
    object StoreNameUnknown : VlmRetryTrigger("store_name_unknown")
    object ExpiryMissing : VlmRetryTrigger("expiry_missing")
    object NeedsAttentionFlag : VlmRetryTrigger("needs_attention")
    object OcrTooShort : VlmRetryTrigger("ocr_too_short")
    data class FieldDisagreement(val field: String) :
        VlmRetryTrigger("disagreement_$field")
}
