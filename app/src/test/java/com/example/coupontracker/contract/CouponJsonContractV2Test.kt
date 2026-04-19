package com.example.coupontracker.contract

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CouponJsonContractV2Test {

    private val v1 = """{"storeName":"AJIO","description":"x","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}"""

    @Test
    fun `v1 payload remains valid under v2 contract`() {
        assertTrue(CouponJsonContractV2.validate(v1).valid)
    }

    @Test
    fun `v2 payload with valid optional fields passes`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"offerType\":\"discount\",\"category\":\"fashion\",\"redeemCodes\":[\"SAVE50\"]")
        assertTrue(CouponJsonContractV2.validate(v2).valid)
    }

    @Test
    fun `invalid offerType flagged`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"offerType\":\"weird\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains("offerType") })
    }

    @Test
    fun `redeemCodes must be array`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"redeemCodes\":\"SAVE50\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains("redeemCodes") })
    }

    @Test
    fun `unknown key still flagged`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"status\":\"fallback\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.unknownKeys.contains("status"))
    }
}
