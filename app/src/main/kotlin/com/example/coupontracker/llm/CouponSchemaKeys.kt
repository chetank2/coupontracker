package com.example.coupontracker.llm

/**
 * Canonical set of keys produced by the LLM grammar and expected by the
 * coupon parser. Any deviation between grammar output, JNI fallback output,
 * and the parser allowlist is a contract bug — this object is the single
 * source of truth referenced by all three.
 */
object CouponSchemaKeys {
    const val STORE_NAME = "storeName"
    const val DESCRIPTION = "description"
    const val REDEEM_CODE = "redeemCode"
    const val EXPIRY_DATE = "expiryDate"
    const val STORE_NAME_SOURCE = "storeNameSource"
    const val STORE_NAME_EVIDENCE = "storeNameEvidence"
    const val NEEDS_ATTENTION = "needsAttention"

    val CANONICAL_ORDER: List<String> = listOf(
        STORE_NAME,
        DESCRIPTION,
        REDEEM_CODE,
        EXPIRY_DATE,
        STORE_NAME_SOURCE,
        STORE_NAME_EVIDENCE,
        NEEDS_ATTENTION
    )

    val ALLOWED_SET: Set<String> = CANONICAL_ORDER.toSet()

    // --- Schema v2 (additive) ---
    const val REDEEM_CODES = "redeemCodes"
    const val PRIMARY_REDEEM_CODE = "primaryRedeemCode"
    const val CATEGORY = "category"
    const val STORE_URL = "storeUrl"
    const val PAYMENT_METHOD = "paymentMethod"
    const val MINIMUM_PURCHASE = "minimumPurchase"
    const val MAXIMUM_DISCOUNT = "maximumDiscount"
    const val OFFER_TYPE = "offerType"

    val V2_OPTIONAL_KEYS: Set<String> = setOf(
        REDEEM_CODES,
        PRIMARY_REDEEM_CODE,
        CATEGORY,
        STORE_URL,
        PAYMENT_METHOD,
        MINIMUM_PURCHASE,
        MAXIMUM_DISCOUNT,
        OFFER_TYPE
    )

    val ALLOWED_SET_V2: Set<String> = ALLOWED_SET + V2_OPTIONAL_KEYS
}
