package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CouponJsonContractTest {

    private val canonicalValid = """
        {"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50",
         "expiryDate":"2026-06-01","storeNameSource":"ocr",
         "storeNameEvidence":["AJIO"],"needsAttention":false}
    """.trimIndent()

    @Test
    fun `valid canonical payload passes`() {
        val report = CouponJsonContract.validate(canonicalValid)
        assertTrue(report.valid)
        assertTrue(report.missingKeys.isEmpty())
        assertTrue(report.unknownKeys.isEmpty())
        assertTrue(report.structuralErrors.isEmpty())
    }

    @Test
    fun `unknown diagnostic key is flagged`() {
        val withDiagnostic = canonicalValid.replace(
            "\"needsAttention\":false",
            "\"needsAttention\":false,\"status\":\"fallback\""
        )
        val report = CouponJsonContract.validate(withDiagnostic)
        assertFalse(report.valid)
        assertEquals(setOf("status"), report.unknownKeys)
    }

    @Test
    fun `missing required key is flagged`() {
        val missingExpiry = canonicalValid.replace(
            ",\"expiryDate\":\"2026-06-01\"", ""
        )
        val report = CouponJsonContract.validate(missingExpiry)
        assertFalse(report.valid)
        assertEquals(setOf(CouponSchemaKeys.EXPIRY_DATE), report.missingKeys)
    }

    @Test
    fun `storeNameEvidence must be array`() {
        val wrongType = canonicalValid.replace(
            "\"storeNameEvidence\":[\"AJIO\"]",
            "\"storeNameEvidence\":\"AJIO\""
        )
        val report = CouponJsonContract.validate(wrongType)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains(CouponSchemaKeys.STORE_NAME_EVIDENCE) })
    }

    @Test
    fun `needsAttention must be boolean`() {
        val wrongType = canonicalValid.replace(
            "\"needsAttention\":false",
            "\"needsAttention\":\"false\""
        )
        val report = CouponJsonContract.validate(wrongType)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains(CouponSchemaKeys.NEEDS_ATTENTION) })
    }

    @Test
    fun `malformed JSON yields parse error`() {
        val report = CouponJsonContract.validate("{not json")
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.startsWith("parse:") })
        assertEquals(CouponJsonContract.REQUIRED_KEYS, report.missingKeys)
    }

    @Test
    fun `couponCode alias is recognized not flagged unknown`() {
        val withAlias = """
            {"storeName":"AJIO","description":"","couponCode":"SAVE50",
             "expiryDate":"2026-06-01","storeNameSource":"ocr",
             "storeNameEvidence":[],"needsAttention":true,"redeemCode":"SAVE50"}
        """.trimIndent()
        val report = CouponJsonContract.validate(withAlias)
        assertTrue(report.unknownKeys.isEmpty())
    }

    @Test
    fun `enforce strips unknown keys`() {
        val input = """{"storeName":"AJIO","description":"","redeemCode":"X","expiryDate":"u","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false,"status":"fallback"}"""
        val cleaned = CouponJsonContract.enforce(input)
        val report = CouponJsonContract.validate(cleaned)
        assertTrue(report.valid)
        assertTrue(report.unknownKeys.isEmpty())
    }

    @Test
    fun `enforce remaps couponCode to redeemCode`() {
        val input = """{"storeName":"AJIO","description":"","couponCode":"X","expiryDate":"u","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":true}"""
        val cleaned = CouponJsonContract.enforce(input)
        val report = CouponJsonContract.validate(cleaned)
        assertTrue(report.valid)
        assertFalse(cleaned.contains("couponCode"))
        assertTrue(cleaned.contains("\"redeemCode\":\"X\""))
    }

    @Test
    fun `enforce returns input unchanged on parse failure`() {
        val garbage = "not json"
        assertEquals(garbage, CouponJsonContract.enforce(garbage))
    }
}
