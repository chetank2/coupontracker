package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCouponModelTest {

    private val canonical = """{"storeName":"AJIO","description":"Flat 50%","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}"""

    private fun stubBitmap(): Bitmap = mockk(relaxed = true)

    @Test
    fun `replay returns recorded json for matching hash`() = runBlocking {
        val sha = "abc123"
        val recordings = mapOf(sha to canonical)
        val hasher: (Bitmap) -> String = { sha }
        val model = ReplayCouponModel(recordings, hasher)

        val result = model.extractFromImage(stubBitmap(), null, "prompt")
        assertEquals(canonical, result.canonicalJson)
        assertFalse(result.usedFallback)
        assertTrue(result.latencyMs >= 0)
    }

    @Test(expected = IllegalStateException::class)
    fun `replay fails loudly on unknown hash`() = runBlocking {
        val hasher: (Bitmap) -> String = { "missing" }
        val model = ReplayCouponModel(emptyMap(), hasher)
        model.extractFromImage(stubBitmap(), null, "prompt")
        Unit
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `replay rejects text-only calls`() = runBlocking {
        val model = ReplayCouponModel(emptyMap()) { _ -> "x" }
        model.extractFromText("ocr", "prompt", null)
        Unit
    }
}
