package com.example.coupontracker.schema

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactPromptGeneratorTest {

    @Test
    fun `system prompt emphasizes non null store name`() {
        val prompt = CompactPromptGenerator.generateSystemPrompt(CouponSchema.SCHEMA)

        assertTrue(
            prompt.contains("never null when brand text exists"),
            "System prompt must instruct the model to emit storeName when brand text is available"
        )
        assertFalse(
            prompt.contains("Key fields"),
            "Legacy prompt framing should be removed to keep token count low"
        )
        assertTrue(
            prompt.startsWith("<|im_start|>system"),
            "Prompt should still be wrapped in the system ChatML role"
        )
    }
}
