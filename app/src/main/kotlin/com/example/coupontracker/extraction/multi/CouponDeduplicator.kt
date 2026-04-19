package com.example.coupontracker.extraction.multi

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.util.Locale

object CouponDeduplicator {

    fun dedupe(coupons: List<JSONObject>): List<JSONObject> {
        val seen = linkedMapOf<Triple<String, String, String>, JSONObject>()
        coupons.forEach { c ->
            val key = Triple(
                norm(c.optString(CouponSchemaKeys.STORE_NAME)),
                norm(c.optString(CouponSchemaKeys.REDEEM_CODE)),
                norm(c.optString(CouponSchemaKeys.EXPIRY_DATE))
            )
            seen.putIfAbsent(key, c)
        }
        return seen.values.toList()
    }

    private fun norm(v: String?): String =
        v.orEmpty().trim().lowercase(Locale.US).replace("\\s+".toRegex(), " ")
}
