package com.example.coupontracker.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `confidenceMapToJson returns empty json object for null or empty input`() {
        assertEquals("{}", converters.confidenceMapToJson(null))
        assertEquals("{}", converters.confidenceMapToJson(emptyMap()))
    }

    @Test
    fun `jsonToConfidenceMap parses json into map and handles blanks`() {
        assertEquals(emptyMap(), converters.jsonToConfidenceMap(null))
        assertEquals(emptyMap(), converters.jsonToConfidenceMap(""))

        val map = mapOf("storeName" to 0.9f, "code" to 0.75f)
        val json = converters.confidenceMapToJson(map)
        assertEquals(map, converters.jsonToConfidenceMap(json))
    }

    @Test
    fun `jsonToStringList handles nulls and roundtrips`() {
        assertEquals(emptyList(), converters.jsonToStringList(null))
        assertEquals(emptyList(), converters.jsonToStringList(""))

        val values = listOf("one", "two", "three")
        val json = converters.stringListToJson(values)
        assertEquals(values, converters.jsonToStringList(json))
    }
}
