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
}
