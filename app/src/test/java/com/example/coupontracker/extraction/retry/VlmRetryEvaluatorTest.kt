package com.example.coupontracker.extraction.retry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRetryEvaluatorTest {

    private val valid = JSONObject("""{"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}""")

    @Test
    fun `no trigger when payload complete`() {
        val triggers = VlmRetryEvaluator().evaluate(valid, ocrText = "AJIO SAVE50 Flat 50% valid 01 Jun 2026")
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `missing redeem code fires trigger`() {
        val missing = JSONObject(valid.toString()).put("redeemCode", "unknown")
        val triggers = VlmRetryEvaluator().evaluate(missing, ocrText = "any")
        assertTrue(triggers.any { it is VlmRetryTrigger.RedeemCodeMissing })
    }

    @Test
    fun `needsAttention fires trigger`() {
        val flag = JSONObject(valid.toString()).put("needsAttention", true)
        val triggers = VlmRetryEvaluator().evaluate(flag, ocrText = "any")
        assertTrue(triggers.any { it is VlmRetryTrigger.NeedsAttentionFlag })
    }

    @Test
    fun `ocr shorter than threshold fires trigger`() {
        val triggers = VlmRetryEvaluator().evaluate(valid, ocrText = "hi")
        assertTrue(triggers.any { it is VlmRetryTrigger.OcrTooShort })
    }

    @Test
    fun `shouldRetry is true if any trigger fires`() {
        val missing = JSONObject(valid.toString()).put("storeName", "unknown")
        assertEquals(true, VlmRetryEvaluator().shouldRetry(missing, ocrText = "any", ocrSpansCount = 5))
    }
}
