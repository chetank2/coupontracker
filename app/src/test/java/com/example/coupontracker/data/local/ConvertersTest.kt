package com.example.coupontracker.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {

    @Test
    fun `confidenceMapToJson returns empty json object for null or empty input`() {
        assertEquals("{}", Converters.confidenceMapToJson(null))
        assertEquals("{}", Converters.confidenceMapToJson(emptyMap()))
    }

    @Test
    fun `fromConfidenceJson parses json into map and handles blanks`() {
        assertEquals(emptyMap(), Converters.fromConfidenceJson(null))
        assertEquals(emptyMap(), Converters.fromConfidenceJson(""))

        val map = mapOf("storeName" to 0.9f, "code" to 0.75f)
        val json = Converters.confidenceMapToJson(map)
        assertEquals(map, Converters.fromConfidenceJson(json))
    }
}
