package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VlmMergerTest {

    private fun payload(
        store: String = "unknown", desc: String = "", code: String = "unknown",
        date: String = "unknown", source: String = "fallback",
        evidence: List<String> = emptyList(), attention: Boolean = true
    ): JSONObject = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, desc)
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, date)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, source)
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray(evidence))
        put(CouponSchemaKeys.NEEDS_ATTENTION, attention)
    }

    @Test
    fun `redeemCode adopted only if present in OCR text`() {
        val primary = payload(code = "unknown")
        val vlm = payload(code = "VLMHALLUC")
        val merged = VlmMerger.merge(primary, vlm, ocrText = "no such code here")
        assertEquals("unknown", merged.getString(CouponSchemaKeys.REDEEM_CODE))

        val merged2 = VlmMerger.merge(primary, vlm, ocrText = "VLMHALLUC on the page")
        assertEquals("VLMHALLUC", merged2.getString(CouponSchemaKeys.REDEEM_CODE))
    }

    @Test
    fun `storeName adopted only with evidence`() {
        val primary = payload(store = "unknown")
        val noEvidence = payload(store = "AJIO", evidence = emptyList())
        val withEvidence = payload(store = "AJIO", evidence = listOf("AJIO logo"))

        assertEquals("unknown",
            VlmMerger.merge(primary, noEvidence, "any").getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("AJIO",
            VlmMerger.merge(primary, withEvidence, "any").getString(CouponSchemaKeys.STORE_NAME))
    }

    @Test
    fun `expiry adopted only if parser accepts`() {
        val primary = payload(date = "unknown")
        val valid = payload(date = "2026-06-01")
        val gibberish = payload(date = "sometime next year")

        assertEquals("2026-06-01",
            VlmMerger.merge(primary, valid, "any").getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("unknown",
            VlmMerger.merge(primary, gibberish, "any").getString(CouponSchemaKeys.EXPIRY_DATE))
    }

    @Test
    fun `description adopted only when VLM version longer and non-generic`() {
        val primary = payload(desc = "offer")
        val generic = payload(desc = "save money")
        val substantive = payload(desc = "Flat 50% off on first order above 999")

        assertEquals("offer",
            VlmMerger.merge(primary, generic, "any").getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("Flat 50% off on first order above 999",
            VlmMerger.merge(primary, substantive, "any").getString(CouponSchemaKeys.DESCRIPTION))
    }

    @Test
    fun `needsAttention cleared when all fields now known`() {
        val primary = payload(attention = true)
        val complete = payload(
            store = "AJIO", desc = "Flat 50% off first order",
            code = "SAVE50", date = "2026-06-01",
            evidence = listOf("AJIO"), attention = false
        )
        val merged = VlmMerger.merge(primary, complete, ocrText = "SAVE50 AJIO Flat 50% off")
        assertFalse(merged.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }
}
