package com.example.coupontracker.ui.viewmodel

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ScannerViewModelDateParsingTest {

    @Test
    fun `parseExpiryDate handles slash separated two digit year`() {
        val result = ScannerViewModel.parseExpiryDate("31/12/24", Locale.UK)

        assertNotNull(result)
    }

    @Test
    fun `parseExpiryDate handles month text with two digit year`() {
        val result = ScannerViewModel.parseExpiryDate("15 Aug 25", Locale.UK)

        assertNotNull(result)
    }

    @Test
    fun `detected crop OCR result is marked provisional for background vision verification`() {
        val coupon = Coupon(
            storeName = "AJIO",
            description = "Flat 50% off",
            redeemCode = "SAVE50",
            imageUri = "file://coupon.png",
            layoutState = Coupon.LayoutState.COMPLETE,
            cleanupStatus = Coupon.CleanupStatus.NONE,
            debugVisionEvidence = "ocr_fields=3"
        )

        val provisional = ScannerViewModel.markDetectedCropOcrProvisional(coupon)

        assertTrue(provisional.needsAttention)
        assertEquals(Coupon.CleanupStatus.PENDING, provisional.cleanupStatus)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, provisional.layoutState)
        assertTrue(provisional.cleanupError.orEmpty().contains("Background vision verification pending"))
        assertTrue(provisional.debugVisionEvidence.orEmpty().contains("background_vision_verification=pending"))
        assertTrue(provisional.debugVisionEvidence.orEmpty().contains("source=single_detected_crop_ocr_only"))
        assertTrue(provisional.debugVisionEvidence.orEmpty().contains("ocr_fields=3"))
    }
}
