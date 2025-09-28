package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import com.example.coupontracker.data.model.CouponInfo
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.service.TelemetryService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.*

class LocalLlmOcrServiceTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockLlmRuntime: LlmRuntimeManager
    private lateinit var mockTelemetryService: TelemetryService
    private lateinit var mockBitmap: Bitmap
    private lateinit var localLlmOcrService: LocalLlmOcrService
    
    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockLlmRuntime = mockk(relaxed = true)
        mockTelemetryService = mockk(relaxed = true)
        mockBitmap = mockk(relaxed = true)
        
        // Mock bitmap properties
        every { mockBitmap.isRecycled } returns false
        every { mockBitmap.width } returns 800
        every { mockBitmap.height } returns 600
        
        // Mock LLM runtime availability
        every { mockLlmRuntime.isModelLoaded() } returns true
        every { mockLlmRuntime.getMemoryStats() } returns mockk {
            every { modelLoadedMemoryMB } returns 512
        }
        
        localLlmOcrService = LocalLlmOcrService(mockContext, mockLlmRuntime, mockTelemetryService)
    }
    
    @Test
    fun `should reject generic store name and trigger fallback`() = runTest {
        // Arrange: LLM returns generic "Vouchers" as store name
        val genericResponse = """
        {
            "storeName": "Vouchers",
            "description": "Get discount",
            "redeemCode": "SAVE20",
            "cashbackAmount": "20%",
            "expiryDate": "2024-12-31",
            "minOrderAmount": "₹500"
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns genericResponse
        
        // Mock fallback OCR to return meaningful data
        mockFallbackOcr()
        
        // Act & Assert: Should trigger fallback due to generic store name
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Verify fallback was used (indicated by different store name)
        assertEquals("Test Store", result.storeName)
        
        // Verify telemetry recorded the failure
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should reject duplicate store name and redeem code`() = runTest {
        // Arrange: LLM returns same value for store and code
        val duplicateResponse = """
        {
            "storeName": "AMAZON",
            "description": "Shopping discount",
            "redeemCode": "AMAZON",
            "cashbackAmount": "15%",
            "expiryDate": "2024-12-31",
            "minOrderAmount": "₹1000"
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns duplicateResponse
        mockFallbackOcr()
        
        // Act & Assert: Should trigger fallback due to duplicate fields
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Verify fallback was used
        assertEquals("Test Store", result.storeName)
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should reject all generic content`() = runTest {
        // Arrange: LLM returns all generic/boilerplate content
        val allGenericResponse = """
        {
            "storeName": "Store",
            "description": "Offer",
            "redeemCode": "Coupon",
            "cashbackAmount": null,
            "expiryDate": null,
            "minOrderAmount": null
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns allGenericResponse
        mockFallbackOcr()
        
        // Act & Assert: Should trigger fallback due to all generic content
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        assertEquals("Test Store", result.storeName)
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should accept valid LLM response without triggering fallback`() = runTest {
        // Arrange: LLM returns valid, meaningful data
        val validResponse = """
        {
            "storeName": "Amazon",
            "description": "Get 20% off on electronics",
            "redeemCode": "ELECTRONICS20",
            "cashbackAmount": "20%",
            "expiryDate": "2024-12-31",
            "minOrderAmount": "₹2000"
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns validResponse
        
        // Act: Process the image
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Assert: Should use LLM result directly without fallback
        assertEquals("Amazon", result.storeName)
        assertEquals("Get 20% off on electronics", result.description)
        assertEquals("ELECTRONICS20", result.redeemCode)
        assertEquals(20.0, result.cashbackAmount, 0.01)
        assertEquals(2000.0, result.minimumPurchase, 0.01)
        
        // Verify successful telemetry
        verify { mockTelemetryService.recordInference(any(), true, any(), any(), null) }
        
        // Verify no fallback OCR was attempted
        verify(exactly = 0) { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should handle header text like 'vouchers' as generic`() = runTest {
        // Arrange: LLM extracts UI header text as store name
        val headerResponse = """
        {
            "storeName": "vouchers",
            "description": "Available coupons",
            "redeemCode": "HEADER123",
            "cashbackAmount": "10%",
            "expiryDate": "2024-11-30",
            "minOrderAmount": "₹500"
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns headerResponse
        mockFallbackOcr()
        
        // Act & Assert: Should reject header text and use fallback
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        assertEquals("Test Store", result.storeName)
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should handle mixed valid and generic content appropriately`() = runTest {
        // Arrange: LLM returns mix of valid and generic content
        val mixedResponse = """
        {
            "storeName": "Flipkart",
            "description": "description",
            "redeemCode": "FLIP50",
            "cashbackAmount": "₹50",
            "expiryDate": "2024-12-25",
            "minOrderAmount": null
        }
        """.trimIndent()
        
        every { mockLlmRuntime.runInference(any(), any()) } returns mixedResponse
        
        // Act: Process the image
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Assert: Should keep valid fields, replace generic description with default
        assertEquals("Flipkart", result.storeName)
        assertEquals("Coupon offer", result.description) // Generic "description" replaced
        assertEquals("FLIP50", result.redeemCode)
        assertEquals(50.0, result.cashbackAmount, 0.01)

        // Should pass validation since we have meaningful store name and code
        verify { mockTelemetryService.recordInference(any(), true, any(), any(), null) }
    }

    @Test
    fun `cleanDescription removes noise and normalizes casing`() {
        val rawDescription = "12:40 9 X\nfree body mist OF YOUR CHOICE"

        val cleaned = LocalLlmOcrService.cleanDescription(rawDescription)

        assertEquals("Free body mist of your choice", cleaned)
    }

    @Test
    fun `normalizeStoreName removes short alphanumeric prefixes`() {
        val normalized = LocalLlmOcrService.normalizeStoreName("F2 Souvenir")

        assertEquals("Souvenir", normalized)
    }

    @Test
    fun `should handle LLM timeout and use fallback`() = runTest {
        // Arrange: LLM times out
        every { mockLlmRuntime.runInference(any(), any()) } returns null
        mockFallbackOcr()
        
        // Act: Process the image
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Assert: Should use fallback OCR
        assertEquals("Test Store", result.storeName)
        verify { mockTelemetryService.recordTimeout(any(), any()) }
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    @Test
    fun `should handle invalid JSON response and use fallback`() = runTest {
        // Arrange: LLM returns invalid JSON
        every { mockLlmRuntime.runInference(any(), any()) } returns "invalid json response"
        mockFallbackOcr()
        
        // Act: Process the image
        val result = localLlmOcrService.processCouponImage(mockBitmap)
        
        // Assert: Should use fallback OCR due to JSON parsing error
        assertEquals("Test Store", result.storeName)
        verify { mockTelemetryService.recordInference(any(), false, any(), any(), any()) }
    }
    
    private fun mockFallbackOcr() {
        // Mock the fallback OCR chain to return test data
        // This simulates successful ML Kit + TextExtractor processing
        mockkStatic("com.google.mlkit.vision.text.TextRecognition")
        
        val mockTextExtractor = mockk<TextExtractor>()
        every { mockTextExtractor.extractCouponInfoSync(any(), any()) } returns CouponInfo(
            storeName = "Test Store",
            description = "Test coupon from fallback OCR",
            redeemCode = "FALLBACK123",
            cashbackAmount = 25.0,
            expiryDate = Date(),
            minimumPurchase = 1000.0
        )
        
        // Mock the TextExtractor constructor
        mockkConstructor(TextExtractor::class)
        every { anyConstructed<TextExtractor>().extractCouponInfoSync(any(), any()) } returns CouponInfo(
            storeName = "Test Store",
            description = "Test coupon from fallback OCR",
            redeemCode = "FALLBACK123",
            cashbackAmount = 25.0,
            expiryDate = Date(),
            minimumPurchase = 1000.0
        )
    }
}
