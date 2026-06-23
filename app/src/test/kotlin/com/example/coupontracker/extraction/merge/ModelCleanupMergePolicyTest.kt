package com.example.coupontracker.extraction.merge

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ModelCleanupMergePolicyTest {

    @Test
    fun `qwen text cleanup preserves strong ocr fields and flags model regression`() {
        val expiry = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2025-05-31")!!
        val current = Coupon(
            id = 1L,
            storeName = "Man Company",
            description = "May, 2025, PM About The Company Scratch card received on offer o vo",
            expiryDate = expiry,
            redeemCode = "TMCPE6990425SQTJ",
            imageUri = null,
            rawOcrText = "Man Company scratch card code TMCPE6990425SQTJ expires 31 May 2025"
        )
        val modelJson = JSONObject()
            .put(CouponSchemaKeys.STORE_NAME, "unknown")
            .put(CouponSchemaKeys.DESCRIPTION, "Scratch card received on offer")
            .put(CouponSchemaKeys.REDEEM_CODE, "ovo")
            .put(CouponSchemaKeys.EXPIRY_DATE, "may-16th , unknown year")
            .put(CouponSchemaKeys.STORE_NAME_SOURCE, "unknown")
            .put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray())
            .put(CouponSchemaKeys.NEEDS_ATTENTION, false)

        val result = ModelCleanupMergePolicy.mergeQwenTextCleanup(
            current = current,
            modelJson = modelJson,
            cleanupInputText = current.rawOcrText,
            cleanedBy = "Qwen offer cleaner",
            now = expiry
        )

        assertEquals("Man Company", result.coupon.storeName)
        assertEquals("TMCPE6990425SQTJ", result.coupon.redeemCode)
        assertEquals(expiry, result.coupon.expiryDate)
        assertEquals("May, 2025, PM About The Company Scratch card received on offer o vo", result.coupon.description)
        assertEquals(Coupon.CleanupStatus.FAILED, result.coupon.cleanupStatus)
        assertEquals(null, result.coupon.lastCleanedBy)
        assertEquals(null, result.coupon.extractionSource)
        assertTrue(result.coupon.needsAttention)
        assertTrue(result.regressedFields.contains(CouponSchemaKeys.DESCRIPTION))
        assertTrue(result.regressedFields.contains(CouponSchemaKeys.STORE_NAME))
        assertTrue(result.regressedFields.contains(CouponSchemaKeys.REDEEM_CODE))
        assertTrue(result.regressedFields.contains(CouponSchemaKeys.EXPIRY_DATE))
        assertTrue(result.runPath.contains("rejected_invalid_non_iso_model_expiry"))
        assertTrue(result.runPath.contains("\"cleanupDecision\":\"rejected_preserved\""))
    }

    @Test
    fun `qwen text cleanup keeps existing description when model offer is unknown`() {
        val current = Coupon(
            id = 2L,
            storeName = "Kapiva",
            description = "Get 15 percent off supplements",
            redeemCode = "KAP15",
            imageUri = null,
            rawOcrText = "Kapiva Get 15 percent off supplements code KAP15"
        )
        val modelJson = JSONObject()
            .put(CouponSchemaKeys.STORE_NAME, "Kapiva")
            .put(CouponSchemaKeys.DESCRIPTION, "unknown")
            .put(CouponSchemaKeys.REDEEM_CODE, "KAP15")
            .put(CouponSchemaKeys.EXPIRY_DATE, "unknown")
            .put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
            .put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray().put("Kapiva"))
            .put(CouponSchemaKeys.NEEDS_ATTENTION, false)

        val result = ModelCleanupMergePolicy.mergeQwenTextCleanup(
            current = current,
            modelJson = modelJson,
            cleanupInputText = current.rawOcrText,
            cleanedBy = "Qwen offer cleaner"
        )

        assertEquals("Get 15 percent off supplements", result.coupon.description)
        assertEquals("Kapiva", result.coupon.storeName)
        assertEquals("KAP15", result.coupon.redeemCode)
        assertEquals(Coupon.CleanupStatus.FAILED, result.coupon.cleanupStatus)
        assertEquals(null, result.coupon.extractionSource)
    }

    @Test
    fun `qwen text cleanup rejects unsupported amount changes`() {
        val current = Coupon(
            id = 3L,
            storeName = "PokerBaazi",
            description = "you won 100% bonus up to 775,000 on your first deposit from PokerBaazi",
            redeemCode = "PBJP75",
            imageUri = null,
            rawOcrText = "PokerBaazi PBJP75 you won 100% bonus up to 775,000 on your first deposit from PokerBaazi"
        )
        val modelJson = JSONObject()
            .put(CouponSchemaKeys.STORE_NAME, "PokerBaazi")
            .put(CouponSchemaKeys.DESCRIPTION, "you won 100% bonus up to $7,750 on your first deposit from PokerBaazi")
            .put(CouponSchemaKeys.REDEEM_CODE, "PBJP75")
            .put(CouponSchemaKeys.EXPIRY_DATE, "unknown")
            .put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
            .put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray().put("PokerBaazi"))
            .put(CouponSchemaKeys.NEEDS_ATTENTION, false)

        val result = ModelCleanupMergePolicy.mergeQwenTextCleanup(
            current = current,
            modelJson = modelJson,
            cleanupInputText = current.rawOcrText,
            cleanedBy = "Qwen offer cleaner"
        )

        assertEquals("you won 100% bonus up to 775,000 on your first deposit from PokerBaazi", result.coupon.description)
        assertEquals(Coupon.CleanupStatus.FAILED, result.coupon.cleanupStatus)
        assertEquals(null, result.coupon.extractionSource)
        assertTrue(result.regressedFields.contains(CouponSchemaKeys.DESCRIPTION))
        assertTrue(result.runPath.contains("\"numbersSupported\":false"))
    }

    @Test
    fun `qwen text cleanup only marks cleaned when supported model improvement is accepted`() {
        val current = Coupon(
            id = 4L,
            storeName = "Kapiva",
            description = "15 percent supplements",
            redeemCode = "KAP15",
            imageUri = null,
            rawOcrText = "Kapiva Get 15 percent off supplements code KAP15"
        )
        val modelJson = JSONObject()
            .put(CouponSchemaKeys.STORE_NAME, "Kapiva")
            .put(CouponSchemaKeys.DESCRIPTION, "Get 15 percent off supplements")
            .put(CouponSchemaKeys.REDEEM_CODE, "KAP15")
            .put(CouponSchemaKeys.EXPIRY_DATE, "unknown")
            .put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
            .put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray().put("Kapiva"))
            .put(CouponSchemaKeys.NEEDS_ATTENTION, false)

        val result = ModelCleanupMergePolicy.mergeQwenTextCleanup(
            current = current,
            modelJson = modelJson,
            cleanupInputText = current.rawOcrText,
            cleanedBy = "Qwen offer cleaner"
        )

        assertEquals("Get 15 percent off supplements", result.coupon.description)
        assertEquals(Coupon.CleanupStatus.CLEANED, result.coupon.cleanupStatus)
        assertEquals("Qwen offer cleaner", result.coupon.lastCleanedBy)
        assertEquals(Coupon.ExtractionSource.QWEN_CLEANED, result.coupon.extractionSource)
        assertEquals(false, result.coupon.needsAttention)
        assertTrue(result.runPath.contains("\"acceptedModelChange\":true"))
        assertTrue(result.runPath.contains("\"cleanupDecision\":\"accepted\""))
    }
}
