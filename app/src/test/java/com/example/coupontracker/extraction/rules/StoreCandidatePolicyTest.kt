package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCandidatePolicyTest {
    private val policy = StoreCandidatePolicy()

    @Test
    fun `cleanCandidate removes generic store labels and trailing plan words`() {
        assertEquals("Aha", policy.cleanCandidate("Details Aha Annual Plan"))
    }

    @Test
    fun `cleanCandidate strips wallet counter suffix when standalone store appears`() {
        val fullText = """
            vouchers
            NOVA4.31
            NOVA
            polo t-shirts
        """.trimIndent()

        assertEquals("NOVA", policy.cleanCandidate("NOVA4.31", fullText))
    }

    @Test
    fun `cleanCandidate rejects numeric dashboard counters`() {
        assertNull(policy.cleanCandidate("428"))
    }

    @Test
    fun `isAcceptedStoreCandidate rejects common coupon words`() {
        assertFalse(policy.isAcceptedStoreCandidate("cashback", "cashback"))
        assertTrue(policy.isAcceptedStoreCandidate("NOVA", "NOVA polo t-shirts"))
    }
}
