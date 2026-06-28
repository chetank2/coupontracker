package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingFieldPolicyTest {

    @Test
    fun `no-code default requires explicit evidence`() {
        assertTrue(MissingFieldPolicy.hasExplicitNoCodeEvidence("Discount automatically applied"))
        assertTrue(MissingFieldPolicy.hasExplicitNoCodeEvidence("No coupon code needed"))
        assertFalse(MissingFieldPolicy.hasExplicitNoCodeEvidence("Use code SAVE25"))
    }

    @Test
    fun `review defaults use durable coupon constants`() {
        assertEquals(Coupon.Defaults.UNKNOWN_STORE, MissingFieldPolicy.unknownStoreName())
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, MissingFieldPolicy.explicitNoCodeValue())
        assertEquals("Needs review: description not visible", MissingFieldPolicy.reviewDescription())
    }

    @Test
    fun `low confidence description rejects generic coupon offer`() {
        assertNull(MissingFieldPolicy.lowConfidenceDescriptionFromOcr("Coupon offer"))
    }

    @Test
    fun `placeholder candidate keeps missing code reviewable`() {
        val candidate = FieldCandidate(
            value = "UNKNOWN",
            confidence = 0.1f,
            source = "test",
            context = "test"
        )

        assertTrue(
            MissingFieldPolicy.isPlaceholderCandidate(
                fieldType = FieldType.COUPON_CODE,
                candidate = candidate,
                validStoreName = { true }
            )
        )
    }
}
