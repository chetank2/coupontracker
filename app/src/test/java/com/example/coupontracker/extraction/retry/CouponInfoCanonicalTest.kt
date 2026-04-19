package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.util.CouponInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CouponInfoCanonicalTest {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `populated CouponInfo emits all seven canonical keys`() {
        val date = isoFmt.parse("2026-06-01")!!
        val info = CouponInfo(
            storeName = "AJIO",
            description = "Flat 50% off",
            redeemCode = "SAVE50",
            expiryDate = date,
            storeNameSource = "ocr",
            storeNameEvidence = listOf("AJIO"),
            needsAttention = false
        )

        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("AJIO", obj.getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("Flat 50% off", obj.getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("SAVE50", obj.getString(CouponSchemaKeys.REDEEM_CODE))
        assertEquals("2026-06-01", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("ocr", obj.getString(CouponSchemaKeys.STORE_NAME_SOURCE))
        assertEquals(1, obj.getJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE).length())
        assertFalse(obj.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }

    @Test
    fun `blank fields become literal unknown`() {
        val info = CouponInfo(
            storeName = "",
            description = "",
            redeemCode = "",
            expiryDate = null,
            storeNameSource = null,
            storeNameEvidence = emptyList(),
            needsAttention = true
        )

        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("unknown", obj.getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.REDEEM_CODE))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.STORE_NAME_SOURCE))
        assertEquals(0, obj.getJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE).length())
        assertTrue(obj.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }

    @Test
    fun `null redeemCode becomes unknown`() {
        val info = CouponInfo(storeName = "AJIO", redeemCode = null)
        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("unknown", obj.getString(CouponSchemaKeys.REDEEM_CODE))
    }

    @Test
    fun `expiryDate Date is formatted yyyy-MM-dd`() {
        val date = isoFmt.parse("2027-01-15")!!
        val info = CouponInfo(storeName = "X", expiryDate = date)
        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("2027-01-15", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
    }

    @Test
    fun `output contains exactly the seven canonical keys`() {
        val info = CouponInfo(storeName = "X")
        val obj = JSONObject(info.toCanonicalJsonString())
        val keys = obj.keys().asSequence().toSet()
        assertEquals(CouponSchemaKeys.ALLOWED_SET, keys)
    }
}
