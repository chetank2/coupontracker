package com.example.coupontracker.extraction.multi

import android.net.Uri
import com.example.coupontracker.llm.CouponSchemaKeys
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JsonToCouponConverterTest {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun uri(text: String = "content://example/1"): Uri {
        val u = mockk<Uri>()
        every { u.toString() } returns text
        return u
    }

    private fun canonical(
        store: String = "AJIO", desc: String = "Flat 50% off",
        code: String = "SAVE50", date: String = "2026-06-01",
        attention: Boolean = false
    ) = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, desc)
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, date)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray())
        put(CouponSchemaKeys.NEEDS_ATTENTION, attention)
    }

    @Test
    fun `populated canonical maps to Coupon`() {
        val now = Date()
        val coupon = JsonToCouponConverter.convert(canonical(), uri(), capturedAt = now)
        assertEquals("AJIO", coupon.storeName)
        assertEquals("Flat 50% off", coupon.description)
        assertEquals("SAVE50", coupon.redeemCode)
        assertEquals(isoFmt.parse("2026-06-01"), coupon.expiryDate)
        assertEquals("content://example/1", coupon.imageUri)
        assertEquals(now, coupon.createdAt)
    }

    @Test
    fun `unknown strings become blank`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(store = "unknown", desc = "unknown"), uri()
        )
        assertEquals("", coupon.storeName)
        assertEquals("", coupon.description)
    }

    @Test
    fun `unknown redeemCode becomes null`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(code = "unknown"), uri()
        )
        assertNull(coupon.redeemCode)
    }

    @Test
    fun `unknown expiry becomes null`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "unknown"), uri()
        )
        assertNull(coupon.expiryDate)
    }

    @Test
    fun `malformed expiry becomes null without throwing`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "not-a-date"), uri()
        )
        assertNull(coupon.expiryDate)
    }

    @Test
    fun `valid ISO expiry parses to Date`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "2027-01-15"), uri()
        )
        assertNotNull(coupon.expiryDate)
        assertEquals(isoFmt.parse("2027-01-15"), coupon.expiryDate)
    }
}
