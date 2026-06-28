package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CouponBlockSelectorTest {

    @Test
    fun `selects matching wallet card and excludes previous card code`() {
        val text = """
            vouchers
            active : 25 lifetime : 279
            code: PREVIOUS123
            Details
            Redeem now
            EXPIRES IN 29 DAYS
            you won Lenskart Gold Max membership at just ₹49
            Lenskart
            4.47
            code: LENS123
            Details
            Redeem now
            EXPIRES IN 28 DAYS
        """.trimIndent()

        val block = CouponBlockSelector.selectForStore(text, "Lenskart")

        assertEquals(
            """
            EXPIRES IN 29 DAYS
            you won Lenskart Gold Max membership at just ₹49
            Lenskart
            4.47
            code: LENS123
            Details
            """.trimIndent(),
            block
        )
    }

    @Test
    fun `returns null when store is isolated with no coupon context`() {
        val text = """
            vouchers
            Lenskart
            active
        """.trimIndent()

        assertNull(CouponBlockSelector.selectForStore(text, "Lenskart"))
    }

    @Test
    fun `stops before next coupon expiry after selected card`() {
        val text = """
            Get ₹500 off accessories
            Croma
            Code: CROMA500
            Redeem now
            EXPIRES IN 7 DAYS
            Other store
        """.trimIndent()

        val block = CouponBlockSelector.selectForStore(text, "Croma")

        assertEquals(
            """
            Get ₹500 off accessories
            Croma
            Code: CROMA500
            Redeem now
            """.trimIndent(),
            block
        )
    }
}
