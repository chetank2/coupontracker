package com.example.coupontracker.util

import org.junit.Assert.*
import org.junit.Test

class GenericFieldHeuristicsTest {
    
    @Test
    fun `should detect null and blank values as generic`() {
        assertTrue(GenericFieldHeuristics.isGenericOrMissing(null))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing(""))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("   "))
    }
    
    @Test
    fun `should detect single generic words`() {
        // UI labels
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("voucher"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("Vouchers"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("COUPON"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("offers"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("Deal"))
        
        // Generic descriptions
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("description"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("INFO"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("content"))
        
        // Generic store names
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("store"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("SHOP"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("merchant"))
        
        // Placeholders
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("unknown"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("DEFAULT"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("placeholder"))
    }
    
    @Test
    fun `should detect generic phrases`() {
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("voucher offers"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("COUPON DEALS"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("store info"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("default placeholder"))
    }
    
    @Test
    fun `should detect very short meaningless text`() {
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("1"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("22"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("a"))
        assertTrue(GenericFieldHeuristics.isGenericOrMissing("ab"))
    }
    
    @Test
    fun `should NOT detect legitimate store names as generic`() {
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Amazon"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Flipkart"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("McDonald's"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Zomato"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Big Bazaar"))
    }
    
    @Test
    fun `should NOT detect legitimate descriptions as generic`() {
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Get 20% off on electronics"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Free delivery on orders above ₹500"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Buy 2 get 1 free"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("Flat ₹100 cashback"))
    }
    
    @Test
    fun `should NOT detect legitimate coupon codes as generic`() {
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("SAVE20"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("ELECTRONICS50"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("FIRST100"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("WELCOME25"))
        assertFalse(GenericFieldHeuristics.isGenericOrMissing("ABC123"))
    }
    
    @Test
    fun `should detect duplicate fields correctly`() {
        assertTrue(GenericFieldHeuristics.areDuplicateFields("AMAZON", "amazon"))
        assertTrue(GenericFieldHeuristics.areDuplicateFields("  SAVE20  ", "save20"))
        assertTrue(GenericFieldHeuristics.areDuplicateFields("Flipkart", "FLIPKART"))
    }
    
    @Test
    fun `should NOT detect different fields as duplicates`() {
        assertFalse(GenericFieldHeuristics.areDuplicateFields("Amazon", "SAVE20"))
        assertFalse(GenericFieldHeuristics.areDuplicateFields("Flipkart", "ELECTRONICS50"))
        assertFalse(GenericFieldHeuristics.areDuplicateFields("Zomato", "FOOD25"))
    }
    
    @Test
    fun `should handle null values in duplicate detection`() {
        assertFalse(GenericFieldHeuristics.areDuplicateFields(null, "SAVE20"))
        assertFalse(GenericFieldHeuristics.areDuplicateFields("Amazon", null))
        assertFalse(GenericFieldHeuristics.areDuplicateFields(null, null))
        assertFalse(GenericFieldHeuristics.areDuplicateFields("", "SAVE20"))
        assertFalse(GenericFieldHeuristics.areDuplicateFields("Amazon", ""))
    }
    
    @Test
    fun `should detect zero or meaningless numeric values`() {
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(null))
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(0.0))
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(-5.0))
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(Double.NaN))
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(Double.POSITIVE_INFINITY))
        assertTrue(GenericFieldHeuristics.isZeroOrMeaningless(Double.NEGATIVE_INFINITY))
    }
    
    @Test
    fun `should NOT detect valid numeric values as meaningless`() {
        assertFalse(GenericFieldHeuristics.isZeroOrMeaningless(10.0))
        assertFalse(GenericFieldHeuristics.isZeroOrMeaningless(25.5))
        assertFalse(GenericFieldHeuristics.isZeroOrMeaningless(100.0))
        assertFalse(GenericFieldHeuristics.isZeroOrMeaningless(0.01))
    }
}
