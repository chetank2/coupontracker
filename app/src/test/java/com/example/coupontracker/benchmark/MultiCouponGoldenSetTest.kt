package com.example.coupontracker.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCouponGoldenSetTest {

    @Test
    fun `multi coupon manifest loads and fixtures match expected count`() = runBlocking {
        val loader = javaClass.classLoader!!
        val manifestText = loader.getResource("multi/manifest.json")?.readText()
            ?: error("multi manifest missing from classpath")
        val arr = org.json.JSONArray(manifestText)
        assertTrue("multi manifest must not be empty", arr.length() > 0)
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val id = entry.getString("id")
            val expected = entry.getJSONArray("expected")
            val replayText = loader.getResource("multi/replay/$id.json")?.readText()
                ?: error("missing multi/replay/$id.json")
            val replay = org.json.JSONArray(replayText)
            assertTrue("replay fixture must match expected count for $id",
                replay.length() == expected.length())
        }
    }
}
