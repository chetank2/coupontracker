package com.example.coupontracker.llm

import org.junit.Assert.assertNull
import org.junit.Test

class JniFallbackContractTest {

    @Test
    fun `native inference failure emits no coupon shaped fallback payload`() {
        assertNull(JniFallbackFixtures.INFERENCE_FAILURE_RESPONSE)
    }
}
