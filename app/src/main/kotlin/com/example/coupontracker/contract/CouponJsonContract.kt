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

    /**
     * Strip unknown keys, remap the `couponCode` alias to `redeemCode`, and
     * return the canonical JSON string. If the input does not parse, returns
     * the input unchanged — callers that need strict validation should invoke
     * `validate()` separately.
     */
    fun enforce(jsonText: String): String {
        val candidate = extractFirstJsonObject(jsonText)
        val obj = try {
            JSONObject(candidate)
        } catch (e: JSONException) {
            return jsonText
        }
        val removable = obj.keys().asSequence().filter { it !in RECOGNIZED_KEYS }.toList()
        removable.forEach { obj.remove(it) }
        if (obj.has("couponCode") && !obj.has(CouponSchemaKeys.REDEEM_CODE)) {
            obj.put(CouponSchemaKeys.REDEEM_CODE, obj.get("couponCode"))
        }
        obj.remove("couponCode")
        return obj.toString()
    }

    /**
     * Strip unknown keys against the v2 allowlist (v1 keys + v2 optional
     * keys + couponCode alias). Same alias remap as `enforce`. Used only
     * when SchemaVersionFlag.isV2Enabled() is true; otherwise call `enforce`.
     */
    fun enforceWithV2(jsonText: String): String {
        val candidate = extractFirstJsonObject(jsonText)
        val obj = try {
            JSONObject(candidate)
        } catch (e: JSONException) {
            return jsonText
        }
        val allowed = CouponJsonContractV2.RECOGNIZED_KEYS
        val removable = obj.keys().asSequence().filter { it !in allowed }.toList()
        removable.forEach { obj.remove(it) }
        if (obj.has("couponCode") && !obj.has(CouponSchemaKeys.REDEEM_CODE)) {
            obj.put(CouponSchemaKeys.REDEEM_CODE, obj.get("couponCode"))
        }
        obj.remove("couponCode")
        return obj.toString()
    }

    internal fun extractFirstJsonObject(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        trimmed.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                char == '}' && depth > 0 -> {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        return trimmed.substring(start, index + 1)
                    }
                }
            }
        }
        return trimmed
    }
}
