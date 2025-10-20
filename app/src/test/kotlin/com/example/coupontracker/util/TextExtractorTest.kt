package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextExtractorTest {

    private val extractor = TextExtractor()

    @Test
    fun extractStoreName_filtersCommonStopwords() {
        val text = "JUST launched OTTplay Premium plans"
        val result = extractor.extractStoreName(text)
        assertEquals("OTTplay", result)
    }
}
