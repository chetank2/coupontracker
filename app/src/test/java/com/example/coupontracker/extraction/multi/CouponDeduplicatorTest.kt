package com.example.coupontracker.extraction.multi

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CouponDeduplicatorTest {

    private fun coupon(store: String, code: String, expiry: String): JSONObject = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, "x")
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, expiry)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray())
        put(CouponSchemaKeys.NEEDS_ATTENTION, false)
    }

    @Test
    fun `identical triple collapses`() {
        val list = listOf(
            coupon("AJIO", "SAVE50", "2026-06-01"),
            coupon("AJIO", "SAVE50", "2026-06-01")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `different codes preserved`() {
        val list = listOf(
            coupon("AJIO", "SAVE50", "2026-06-01"),
            coupon("AJIO", "SAVE60", "2026-06-01")
        )
        assertEquals(2, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `case-insensitive and whitespace-insensitive store match`() {
        val list = listOf(
            coupon("Ajio", "SAVE50", "2026-06-01"),
            coupon(" AJIO ", "SAVE50", "2026-06-01")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `unknown fields treated as their own group`() {
        val list = listOf(
            coupon("unknown", "unknown", "unknown"),
            coupon("unknown", "unknown", "unknown")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }
}
