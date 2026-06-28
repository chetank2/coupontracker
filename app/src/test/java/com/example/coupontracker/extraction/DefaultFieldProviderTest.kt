package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultFieldProviderTest {

    private val provider = DefaultFieldProvider()

    @Test
    fun `missing code stays missing without explicit no-code evidence`() {
        val defaults = provider.provideDefaults(
            context = context("Flat 25% off on accessories\nValid today"),
            missingFields = setOf(FieldType.COUPON_CODE)
        )

        assertFalse(defaults.containsKey(FieldType.COUPON_CODE))
    }

    @Test
    fun `explicit no-code evidence can default code state`() {
        val defaults = provider.provideDefaults(
            context = context("Flat 25% off on accessories\nNo code needed"),
            missingFields = setOf(FieldType.COUPON_CODE)
        )

        assertEquals("NO_CODE_NEEDED", defaults[FieldType.COUPON_CODE]?.value)
        assertEquals("explicit_no_code_evidence", defaults[FieldType.COUPON_CODE]?.source)
    }

    @Test
    fun `coupon offer is not emitted as a default description`() {
        val defaults = provider.provideDefaults(
            context = context("Coupon offer"),
            missingFields = setOf(FieldType.DESCRIPTION)
        )

        assertNull(defaults[FieldType.DESCRIPTION])
    }

    private fun context(text: String): ExtractionContext {
        return ExtractionContext(
            imageUri = "test://coupon",
            ocrText = text,
            ocrBlocks = emptyList(),
            metadata = emptyMap()
        )
    }
}
