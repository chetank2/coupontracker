package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONException
import org.json.JSONObject

/**
 * Runtime-facing canonical coupon contract. One place for:
 * - the allowlist used by parser JSON repair,
 * - schema-validity checks used by tests and the benchmark,
 * - the alias remap (`couponCode` → `redeemCode`) that tolerates older LLM outputs.
 *
 * Raw field-name constants live in `CouponSchemaKeys`. This object is the
 * behaviour layer that composes them.
 */
object CouponJsonContract {

    val REQUIRED_KEYS: Set<String> = CouponSchemaKeys.ALLOWED_SET

    val ALIAS_KEYS: Set<String> = setOf("couponCode")

    val RECOGNIZED_KEYS: Set<String> = REQUIRED_KEYS + ALIAS_KEYS

    data class ContractReport(
        val valid: Boolean,
        val missingKeys: Set<String>,
        val unknownKeys: Set<String>,
        val structuralErrors: List<String>
    )

    fun validate(jsonText: String): ContractReport {
        val obj = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return ContractReport(
                valid = false,
                missingKeys = REQUIRED_KEYS,
                unknownKeys = emptySet(),
                structuralErrors = listOf("parse: ${e.message ?: "invalid JSON"}")
            )
        }
        return validate(obj)
    }

    fun validate(obj: JSONObject): ContractReport {
        val keys = obj.keys().asSequence().toSet()
        val missing = REQUIRED_KEYS - keys
        val unknown = keys - RECOGNIZED_KEYS
        val errors = mutableListOf<String>()

        if (obj.has(CouponSchemaKeys.STORE_NAME_EVIDENCE) &&
            obj.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE) == null) {
            errors += "${CouponSchemaKeys.STORE_NAME_EVIDENCE} must be a JSON array"
        }
        if (obj.has(CouponSchemaKeys.NEEDS_ATTENTION) &&
            obj.opt(CouponSchemaKeys.NEEDS_ATTENTION) !is Boolean) {
            errors += "${CouponSchemaKeys.NEEDS_ATTENTION} must be a boolean"
        }

        return ContractReport(
            valid = missing.isEmpty() && unknown.isEmpty() && errors.isEmpty(),
            missingKeys = missing,
            unknownKeys = unknown,
            structuralErrors = errors
        )
    }
}
