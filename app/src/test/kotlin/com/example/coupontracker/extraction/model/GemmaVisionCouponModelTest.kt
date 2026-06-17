package com.example.coupontracker.extraction.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaVisionCouponModelTest {

    @Test
    fun `compact OCR anchors keep coupon lines and drop chrome or actions`() {
        val ocr = """
            5:27
            Paytm
            Gmail
            5G
            100%
            vouchers active: 25 lifetime: 279
            you won 100% cashback on skincare essentials from Mamaearth
            Mamaearth 4.27
            code: C1C4A129JPB7OBE
            Details
            Redeem now
            EXPIRES IN 14 DAYS
        """.trimIndent()

        val compact = GemmaVisionCouponModel.compactOcrAnchors(ocr)

        assertTrue(compact.contains("you won 100% cashback on skincare essentials from Mamaearth"))
        assertTrue(compact.contains("code: C1C4A129JPB7OBE"))
        assertTrue(compact.contains("EXPIRES IN 14 DAYS"))
        assertFalse(compact.contains("Paytm"))
        assertFalse(compact.contains("Gmail"))
        assertFalse(compact.contains("Redeem now"))
    }

    @Test
    fun `multimodal prompt stays compact for long mixed OCR`() {
        val longOcr = buildString {
            repeat(20) { appendLine("Sunscreen WAIR FALL COTOo MEW alMeOYED") }
            appendLine("you won 5 products at ₹999 + ₹150 cashback via CRED pay on XYXX")
            appendLine("code: CRDLUKES799")
            appendLine("EXPIRES IN 14 HOURS")
            repeat(20) { appendLine("Details Redeem now 5G LTE") }
        }

        val prompt = GemmaVisionCouponModel.buildMultimodalPrompt(
            basePrompt = "Read coupon. Return JSON only.",
            ocrText = longOcr
        )

        assertTrue(prompt.length < 520)
        assertTrue(prompt.contains("EXPIRES IN 14 HOURS"))
        assertTrue(prompt.contains("₹150 cashback"))
        assertFalse(prompt.contains("WAIR FALL"))
    }
}
