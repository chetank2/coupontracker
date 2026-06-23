package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericFieldHeuristicsTest {

    @Test
    fun `meaningful description passes when keywords present`() {
        assertTrue(GenericFieldHeuristics.isMeaningfulDescription("Flat 20% off on electronics"))
    }

    @Test
    fun `meaningful description passes when contains numbers`() {
        assertTrue(GenericFieldHeuristics.isMeaningfulDescription("5% off"))
    }

    @Test
    fun `weak description without detail fails`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("Leaf bass wireless"))
    }

    @Test
    fun `truncated description fails`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("Flat 50% off..."))
    }

    @Test
    fun `generic placeholder description fails`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("Coupon offer"))
    }

    @Test
    fun `fetch failure phrase description fails`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("Description not fetched fully"))
    }

    @Test
    fun `standalone percent is not meaningful cashback`() {
        assertFalse(GenericFieldHeuristics.hasMeaningfulCashback("82%"))
    }

    @Test
    fun `percent with offer context is meaningful cashback`() {
        assertTrue(GenericFieldHeuristics.hasMeaningfulCashback("82% off"))
    }

    @Test
    fun `currency amount is meaningful cashback`() {
        assertTrue(GenericFieldHeuristics.hasMeaningfulCashback("₹100"))
    }

    @Test
    fun `numbered purchase intent is meaningful description`() {
        assertTrue(GenericFieldHeuristics.isMeaningfulDescription("Buy any 4 products at merchant website"))
    }

    @Test
    fun `expiry badge fragments are not meaningful fields`() {
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("HOURS"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("IN 04 HOURS"))
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("IN 04 X O IN O4"))
    }

    @Test
    fun `wallet status labels are generic fields`() {
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("ACTIVE"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("claimed"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("unredeemed"))
    }

    @Test
    fun `date fragments are not meaningful descriptions`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("5TH"))
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("05 May, 2025"))
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("Ends 5th May"))
    }

    @Test
    fun `savings descriptions require concrete value`() {
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("you won off"))
        assertFalse(GenericFieldHeuristics.isMeaningfulDescription("you won off Onion Shampoo ZEN"))
        assertTrue(GenericFieldHeuristics.isMeaningfulDescription("you won 80% off on Skullcandy"))
        assertTrue(GenericFieldHeuristics.isMeaningfulDescription("you've won neck fan at ₹1100"))
    }
}
