package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONException
import org.json.JSONObject

/**
 * Schema-v2 contract. Additive: v1-compliant payloads remain valid here.
 * Extra v2 fields are optional; if present, they must have the correct type
 * and, for `offerType`, one of the allowed enum values.
 */
object CouponJsonContractV2 {

    val ALLOWED_OFFER_TYPES: Set<String> =
        setOf("cashback", "discount", "freebie", "points", "unknown")

    val RECOGNIZED_KEYS: Set<String> = CouponJsonContract.RECOGNIZED_KEYS +
        CouponSchemaKeys.V2_OPTIONAL_KEYS

    fun validate(jsonText: String): CouponJsonContract.ContractReport {
        val obj = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return CouponJsonContract.ContractReport(
                valid = false,
                missingKeys = CouponJsonContract.REQUIRED_KEYS,
                unknownKeys = emptySet(),
                structuralErrors = listOf("parse: ${e.message ?: "invalid JSON"}")
            )
        }

        val v1Report = CouponJsonContract.validate(
            obj.let { filtered ->
                val copy = JSONObject(filtered.toString())
                CouponSchemaKeys.V2_OPTIONAL_KEYS.forEach { copy.remove(it) }
                copy
            }
        )

        val errors = v1Report.structuralErrors.toMutableList()

        if (obj.has(CouponSchemaKeys.REDEEM_CODES) &&
            obj.optJSONArray(CouponSchemaKeys.REDEEM_CODES) == null) {
            errors += "${CouponSchemaKeys.REDEEM_CODES} must be a JSON array"
        }
        if (obj.has(CouponSchemaKeys.OFFER_TYPE)) {
            val value = obj.optString(CouponSchemaKeys.OFFER_TYPE)
            if (value !in ALLOWED_OFFER_TYPES) {
                errors += "${CouponSchemaKeys.OFFER_TYPE} must be one of $ALLOWED_OFFER_TYPES"
            }
        }
        listOf(
            CouponSchemaKeys.PRIMARY_REDEEM_CODE,
            CouponSchemaKeys.CATEGORY,
            CouponSchemaKeys.STORE_URL,
            CouponSchemaKeys.PAYMENT_METHOD,
            CouponSchemaKeys.MINIMUM_PURCHASE,
            CouponSchemaKeys.MAXIMUM_DISCOUNT
        ).forEach { key ->
            if (obj.has(key) && obj.opt(key) !is String) {
                errors += "$key must be a string when present"
            }
        }

        val unknownV2 = obj.keys().asSequence().toSet() - RECOGNIZED_KEYS
        return CouponJsonContract.ContractReport(
            valid = v1Report.missingKeys.isEmpty() && unknownV2.isEmpty() && errors.isEmpty(),
            missingKeys = v1Report.missingKeys,
            unknownKeys = unknownV2,
            structuralErrors = errors
        )
    }
}
