package com.example.coupontracker.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JniFallbackContractTest {

    private fun parsed(): JSONObject = JSONObject(JniFallbackFixtures.CANONICAL_FALLBACK_JSON)

    @Test
    fun `fallback json contains exactly the seven canonical keys`() {
        val json = parsed()
        val keys = json.keys().asSequence().toSet()
        assertEquals(CouponSchemaKeys.ALLOWED_SET, keys)
    }

    @Test
    fun `fallback json contains no diagnostic metadata fields`() {
        val json = parsed()
        val forbidden = listOf("status", "mode", "partial", "confidence", "reason", "rawText")
        forbidden.forEach { key ->
            assertFalse("fallback JSON must not contain `$key`", json.has(key))
        }
    }

    @Test
    fun `fallback json contains no seeded demo values`() {
        val raw = JniFallbackFixtures.CANONICAL_FALLBACK_JSON
        assertFalse("fallback must not seed Demo Store", raw.contains("Demo Store"))
        assertFalse("fallback must not seed DEMO50", raw.contains("DEMO50"))
    }

    @Test
    fun `storeNameEvidence is an empty array, not a string`() {
        val json = parsed()
        val evidence = json.optJSONArray("storeNameEvidence")
        assertTrue("storeNameEvidence must be a JSON array", evidence != null)
        assertEquals(0, evidence!!.length())
    }

    @Test
    fun `needsAttention is true in the fallback payload`() {
        val json = parsed()
        assertTrue(json.getBoolean("needsAttention"))
    }

    @Test
    fun `canonical keys match the shared CouponSchemaKeys constant`() {
        val json = parsed()
        CouponSchemaKeys.CANONICAL_ORDER.forEach { key ->
            assertTrue("missing canonical key `$key`", json.has(key))
        }
    }
}
