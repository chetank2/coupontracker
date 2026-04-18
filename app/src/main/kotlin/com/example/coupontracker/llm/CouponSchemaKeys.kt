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
}
