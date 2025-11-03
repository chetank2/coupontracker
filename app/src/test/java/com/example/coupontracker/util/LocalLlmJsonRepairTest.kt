package com.example.coupontracker.util

import com.example.coupontracker.util.LocalLlmOcrService.Companion.isLikelyTruncatedJson
import com.example.coupontracker.util.LocalLlmOcrService.Companion.repairIncompleteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmJsonRepairTest {

    @Test
    fun `isLikelyTruncatedJson detects missing closing quote`() {
        val truncated = """{"storeName":"AJIO","description":"Scratch card","expiryDate":"31 May,202"""
        assertTrue(isLikelyTruncatedJson(truncated))
    }

    @Test
    fun `isLikelyTruncatedJson accepts complete payload`() {
        val complete = """{"storeName":"AJIO","description":"Offer","redeemCode":"TBNEIZ","expiryDate":"2025-05-31"}"""
        assertFalse(isLikelyTruncatedJson(complete))
    }

    @Test
    fun `repairIncompleteJson closes braces and quotes`() {
        val truncated = """{"storeName":"AJIO","description":"Deal","redeemCode":"TBNEIZ","expiryDate":"31 May,202"""
        val result = repairIncompleteJson(truncated)

        assertTrue(result.wasTruncated)
        assertFalse(isLikelyTruncatedJson(result.repairedJson))
        assertTrue(result.repairedJson.trim().endsWith("}"))
    }

    @Test
    fun `repairIncompleteJson removes trailing partial field`() {
        val truncated = """{"storeName":"AJIO","description":"Deal","store"""
        val result = repairIncompleteJson(truncated)

        assertTrue(result.wasTruncated)
        assertEquals("""{"storeName":"AJIO","description":"Deal"}""", result.repairedJson)
    }
}

