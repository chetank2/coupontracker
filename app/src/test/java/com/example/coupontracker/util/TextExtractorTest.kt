package com.example.coupontracker.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TextExtractorTest {

    private val extractor = TextExtractor()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @Test
    fun `test extractStoreName from ABHIBUS coupon`() {
        val text = """
            ABHIBUS
            Get upto ₹500 off
            Code: ABSGYWVAJ1D0A8
            Claim Now
        """.trimIndent()
        
        val storeName = extractor.extractStoreName(text)
        assertNotNull("Store name should not be null", storeName)
        assertEquals("ABHIBUS", storeName)
    }
    
    @Test
    fun `test extractDescription from ABHIBUS coupon`() {
        val text = """
            ABHIBUS
            Get upto ₹500 off
            Code: ABSGYWVAJ1D0A8
            Claim Now
        """.trimIndent()
        
        val description = extractor.extractDescription(text)
        assertNotNull("Description should not be null", description)
        assertTrue(description!!.contains("500"))
    }
    
    @Test
    fun `test extractRedeemCode from ABHIBUS coupon`() {
        val text = """
            ABHIBUS
            Get upto ₹500 off
            Code: ABSGYWVAJ1D0A8
            Claim Now
        """.trimIndent()
        
        val redeemCode = extractor.extractRedeemCode(text)
        assertNotNull("Redeem code should not be null", redeemCode)
        assertEquals("ABSGYWVAJ1D0A8", redeemCode)
    }
    
    @Test
    fun `test extractCashbackAmount from ABHIBUS coupon`() {
        val text = """
            ABHIBUS
            Get upto ₹500 off
            Code: ABSGYWVAJ1D0A8
            Claim Now
        """.trimIndent()
        
        val cashbackAmount = extractor.extractCashbackAmount(text)
        assertNotNull("Cashback amount should not be null", cashbackAmount)
        assertEquals(500.0, cashbackAmount!!, 0.01)
    }
    
    @Test
    fun `test extractStoreName from NEWMEE coupon`() {
        val text = """
            NEWMEE
            Flat ₹250 OFF
            Code: NEWMEE250SW
            Claim Now
        """.trimIndent()
        
        val storeName = extractor.extractStoreName(text)
        assertNotNull("Store name should not be null", storeName)
        assertEquals("NEWMEE", storeName)
    }
    
    @Test
    fun `test extractDescription from NEWMEE coupon`() {
        val text = """
            NEWMEE
            Flat ₹250 OFF
            Code: NEWMEE250SW
            Claim Now
        """.trimIndent()
        
        val description = extractor.extractDescription(text)
        assertNotNull("Description should not be null", description)
        assertTrue(description!!.contains("250"))
    }
    
    @Test
    fun `test extractRedeemCode from NEWMEE coupon`() {
        val text = """
            NEWMEE
            Flat ₹250 OFF
            Code: NEWMEE250SW
            Claim Now
        """.trimIndent()
        
        val redeemCode = extractor.extractRedeemCode(text)
        assertNotNull("Redeem code should not be null", redeemCode)
        assertEquals("NEWMEE250SW", redeemCode)
    }
    
    @Test
    fun `test extractCashbackAmount from NEWMEE coupon`() {
        val text = """
            NEWMEE
            Flat ₹250 OFF
            Code: NEWMEE250SW
            Claim Now
        """.trimIndent()
        
        val cashbackAmount = extractor.extractCashbackAmount(text)
        assertNotNull("Cashback amount should not be null", cashbackAmount)
        assertEquals(250.0, cashbackAmount!!, 0.01)
    }

    @Test
    fun `extractCouponInfoSync populates normalized fields`() {
        val text = """
            AMAZON PAY
            Flat ₹150 OFF on orders above ₹499
            Use code AMAZ150
            Valid till 31/12/2025
        """.trimIndent()

        val info = extractor.extractCouponInfoSync(text)
        assertEquals("AMAZON", info.storeName)
        assertEquals("cashback", info.benefitType)
        assertEquals(150.0, info.benefitValue!!, 0.01)
        assertEquals("INR", info.currency)
        assertEquals("AMAZ150", info.redeemCode)
        assertNotNull("ISO expiry should be populated", info.expiryIso)
        assertTrue("Confidence should be non-zero", info.confidence > 0f)
    }
    
    @Test
    fun `test extractStoreName from IXIGO coupon`() {
        val text = """
            IXIGO
            Get upto 30% off
            Code: SWGGS01BO719GFHS
            Claim Now
        """.trimIndent()
        
        val storeName = extractor.extractStoreName(text)
        assertNotNull("Store name should not be null", storeName)
        assertEquals("IXIGO", storeName)
    }
    
    @Test
    fun `test extractDescription from IXIGO coupon`() {
        val text = """
            IXIGO
            Get upto 30% off
            Code: SWGGS01BO719GFHS
            Claim Now
        """.trimIndent()
        
        val description = extractor.extractDescription(text)
        assertNotNull("Description should not be null", description)
        assertTrue(description!!.contains("30"))
    }
    
    @Test
    fun `test extractRedeemCode from IXIGO coupon`() {
        val text = """
            IXIGO
            Get upto 30% off
            Code: SWGGS01BO719GFHS
            Claim Now
        """.trimIndent()
        
        val redeemCode = extractor.extractRedeemCode(text)
        assertNotNull("Redeem code should not be null", redeemCode)
        assertEquals("SWGGS01BO719GFHS", redeemCode)
    }
    
    @Test
    fun `test extractCashbackAmount from IXIGO coupon`() {
        val text = """
            IXIGO
            Get upto 30% off
            Code: SWGGS01BO719GFHS
            Claim Now
        """.trimIndent()
        
        val cashbackAmount = extractor.extractCashbackAmount(text)
        assertNotNull("Cashback amount should not be null", cashbackAmount)
        assertEquals(30.0, cashbackAmount!!, 0.01)
    }
    
    @Test
    fun `test extractStoreName from BOAT coupon`() {
        val text = """
            BOAT
            Up to 80% Off
            Code: BTXSWG7GYZRB
            Claim Now
        """.trimIndent()
        
        val storeName = extractor.extractStoreName(text)
        assertNotNull("Store name should not be null", storeName)
        assertEquals("BOAT", storeName)
    }
    
    @Test
    fun `test extractDescription from BOAT coupon`() {
        val text = """
            BOAT
            Up to 80% Off
            Code: BTXSWG7GYZRB
            Claim Now
        """.trimIndent()
        
        val description = extractor.extractDescription(text)
        assertNotNull("Description should not be null", description)
        assertTrue(description!!.contains("80"))
    }
    
    @Test
    fun `test extractRedeemCode from BOAT coupon`() {
        val text = """
            BOAT
            Up to 80% Off
            Code: BTXSWG7GYZRB
            Claim Now
        """.trimIndent()
        
        val redeemCode = extractor.extractRedeemCode(text)
        assertNotNull("Redeem code should not be null", redeemCode)
        assertEquals("BTXSWG7GYZRB", redeemCode)
    }
    
    @Test
    fun `test extractCashbackAmount from BOAT coupon`() {
        val text = """
            BOAT
            Up to 80% Off
            Code: BTXSWG7GYZRB
            Claim Now
        """.trimIndent()
        
        val cashbackAmount = extractor.extractCashbackAmount(text)
        assertNotNull("Cashback amount should not be null", cashbackAmount)
        assertEquals(80.0, cashbackAmount!!, 0.01)
    }
    
    @Test
    fun `test extractExpiryDate from text with expires in days`() {
        val text = """
            Expires in 7 days
            ABHIBUS
            Get upto ₹500 off
            Code: ABSGYWVAJ1D0A8
            Claim Now
        """.trimIndent()
        
        val expiryDate = extractor.parseExpiryDate(text)
        assertNotNull("Expiry date should not be null", expiryDate)
    }
} 
