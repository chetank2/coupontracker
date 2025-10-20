package com.example.coupontracker.schema

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptGeneratorTest {

    @Test
    fun `prompt lists all required fields`() {
        val prompt = PromptGenerator.generateSystemPrompt(CouponSchema.SCHEMA)
        listOf("storeName", "redeemCode", "expiryDate").forEach { field ->
            assertTrue("Prompt should mention $field", prompt.contains(field))
        }
    }
}

