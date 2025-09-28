package com.example.coupontracker.util

import android.content.Context
import com.example.coupontracker.data.model.CouponInfo
import com.example.coupontracker.llm.LlmRuntimeManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalLlmOcrServiceParserTest {

    private lateinit var service: LocalLlmOcrService

    @Before
    fun setup() {
        val context = mockk<Context>(relaxed = true)
        val llmRuntimeManager = mockk<LlmRuntimeManager>(relaxed = true)
        service = LocalLlmOcrService(context, llmRuntimeManager)
    }

    @Test
    fun `cashback amount removed when OCR lacks supporting digits`() {
        val llmResponse = """
            {
                "storeName": "BeautyMart",
                "description": "Save 65% on beauty items",
                "cashbackAmount": "65%",
                "redeemCode": "GLOW65",
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()

        val result = invokeParse(llmResponse, "Mega sale on beauty products")

        assertNull(result.cashbackAmount)
        assertFalse(result.description.contains("65"))
    }

    @Test
    fun `cashback amount retained when OCR confirms digits`() {
        val llmResponse = """
            {
                "storeName": "BeautyMart",
                "description": "Save 65% on beauty items",
                "cashbackAmount": "65%",
                "redeemCode": "GLOW65",
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()

        val result = invokeParse(llmResponse, "Get 65% off beauty products today")

        assertEquals(65.0, result.cashbackAmount!!, 0.001)
        assertTrue(result.description.contains("65%"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeParse(response: String, ocrText: String?): CouponInfo {
        val method = service.javaClass.getDeclaredMethod(
            "parseLlmResponseToCouponInfo",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(service, response, ocrText) as CouponInfo
    }
}
