package com.example.coupontracker.worker

import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.extraction.model.GemmaVisionCouponModel
import com.example.coupontracker.extraction.vision.VisionCouponCard
import com.example.coupontracker.extraction.vision.VisionFieldExtraction
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.ocr.OcrEngine
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerifyCouponWorkerTest {

    private val worker = VerifyCouponWorker(
        appContext = ApplicationProvider.getApplicationContext(),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        couponRepository = mockk<CouponRepository>(relaxed = true),
        ocrEngine = mockk<OcrEngine>(relaxed = true),
        gemmaVisionCouponModel = mockk<GemmaVisionCouponModel>(relaxed = true),
        securePreferencesManager = mockk<SecurePreferencesManager>(relaxed = true)
    )

    @Test
    fun `crop NOT_VISIBLE expiry clears old expiry and fails review`() {
        val current = coupon(
            storeName = "IDFC FIRST Bank",
            description = "No cost EMI on credit card",
            redeemCode = null,
            rawOcrText = "IDFC FIRST Bank\nNo cost EMI on credit card\nNo coupon code required",
            expiryDate = java.sql.Date.valueOf("2026-06-01"),
            expiryState = Coupon.ExpiryState.PRESENT
        )
        val vision = extraction(
            card(
                storeName = "IDFC FIRST Bank",
                description = "No cost EMI on credit card",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = "store: IDFC FIRST Bank\noffer: No cost EMI on credit card"
            )
        )

        val merged = worker.mergeVisionFieldLabels(current, vision, current.rawOcrText, cropInput())

        assertNull(merged.expiryDate)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, merged.expiryState)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
        assertNull(merged.lastCleanedBy)
    }

    @Test
    fun `crop UNKNOWN expiry preserves old expiry as inconclusive`() {
        val existingExpiry = java.sql.Date.valueOf("2026-06-01")
        val current = coupon(
            storeName = "IDFC FIRST Bank",
            description = "No cost EMI on credit card",
            redeemCode = null,
            rawOcrText = "IDFC FIRST Bank\nNo cost EMI on credit card\nNo coupon code required",
            expiryDate = existingExpiry,
            expiryState = Coupon.ExpiryState.PRESENT
        )
        val vision = extraction(
            card(
                storeName = "IDFC FIRST Bank",
                description = "No cost EMI on credit card",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.UNKNOWN,
                evidence = "store: IDFC FIRST Bank\noffer: No cost EMI on credit card"
            )
        )

        val merged = worker.mergeVisionFieldLabels(current, vision, current.rawOcrText, cropInput())

        assertEquals(existingExpiry, merged.expiryDate)
        assertEquals(Coupon.ExpiryState.PRESENT, merged.expiryState)
    }

    @Test
    fun `unsupported old code is removed and cannot become vision verified`() {
        val current = coupon(
            storeName = "MakeMyTrip",
            description = "Flat 15% off",
            redeemCode = "BACKGROUND123",
            rawOcrText = "MakeMyTrip\nFlat 15% off",
            codeState = Coupon.CodeState.PRESENT
        )
        val vision = extraction(
            card(
                storeName = "MakeMyTrip",
                description = "Flat 15% off",
                redeemCode = "BACKGROUND123",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = "store: MakeMyTrip\noffer: Flat 15% off\ncode: BACKGROUND123"
            )
        )

        val merged = worker.mergeVisionFieldLabels(current, vision, current.rawOcrText, cropInput())

        assertNull(merged.redeemCode)
        assertEquals(Coupon.CodeState.UNKNOWN, merged.codeState)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
        assertEquals(Coupon.ExtractionSource.OCR_VERIFIED, merged.extractionSource)
    }

    @Test
    fun `unsupported current store cannot be trusted from targeted crop`() {
        val current = coupon(
            storeName = "Background Store",
            description = "Flat 15% off",
            redeemCode = null,
            rawOcrText = "Foreground Store\nFlat 15% off\nNo code needed",
            codeState = Coupon.CodeState.UNKNOWN,
            expiryState = Coupon.ExpiryState.UNKNOWN
        )
        val vision = extraction(
            card(
                storeName = null,
                description = "Flat 15% off",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = "offer: Flat 15% off\ncodeState: NO_CODE_NEEDED\nexpiryState: NOT_VISIBLE"
            )
        )

        val merged = worker.mergeVisionFieldLabels(current, vision, current.rawOcrText, cropInput())

        assertEquals("Background Store", merged.storeName)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
        assertNull(merged.lastCleanedBy)
        assertEquals(Coupon.ExtractionSource.OCR_VERIFIED, merged.extractionSource)
    }

    @Test
    fun `fully supported crop can be vision verified`() {
        val current = coupon(
            storeName = "MakeMyTrip",
            description = "Flat 15% off",
            redeemCode = null,
            rawOcrText = "MakeMyTrip\nFlat 15% off\nNo code needed",
            codeState = Coupon.CodeState.UNKNOWN,
            expiryState = Coupon.ExpiryState.UNKNOWN
        )
        val vision = extraction(
            card(
                storeName = "MakeMyTrip",
                description = "Flat 15% off",
                codeState = Coupon.CodeState.NO_CODE_NEEDED,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = "store: MakeMyTrip\noffer: Flat 15% off\ncodeState: NO_CODE_NEEDED"
            )
        )

        val merged = worker.mergeVisionFieldLabels(current, vision, current.rawOcrText, cropInput())

        assertNull(merged.redeemCode)
        assertNull(merged.expiryDate)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, merged.codeState)
        assertEquals(Coupon.ExpiryState.NOT_VISIBLE, merged.expiryState)
        assertEquals(Coupon.CleanupStatus.CLEANED, merged.cleanupStatus)
        assertEquals(ModelCatalog.GEMMA_VISION_READER_NAME, merged.lastCleanedBy)
        assertEquals(Coupon.ExtractionSource.VISION_VERIFIED, merged.extractionSource)
    }

    @Test
    fun `full image vision input cannot become vision verified`() {
        val current = coupon(
            storeName = "Sample Store",
            description = "Save 10%",
            redeemCode = "SAVE10",
            rawOcrText = "Sample Store\nSave 10%\ncode SAVE10",
            codeState = Coupon.CodeState.PRESENT,
            expiryState = Coupon.ExpiryState.UNKNOWN
        )
        val vision = extraction(
            card(
                storeName = "Sample Store",
                description = "Save 10%",
                redeemCode = "SAVE10",
                codeState = Coupon.CodeState.PRESENT,
                expiryState = Coupon.ExpiryState.NOT_VISIBLE,
                evidence = "store: Sample Store\noffer: Save 10%\ncode: SAVE10"
            )
        )

        val merged = worker.mergeVisionFieldLabels(
            current = current,
            vision = vision,
            rawOcr = current.rawOcrText,
            visionInput = fullImageInput()
        )

        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertTrue(merged.needsAttention)
        assertNull(merged.lastCleanedBy)
        assertEquals(Coupon.ExtractionSource.OCR_VERIFIED, merged.extractionSource)
    }

    @Test
    fun `vision json missing key errors are not reported as model setup failures`() {
        val message = worker.userFacingFailure("Vision field response missing cards[]")

        assertEquals(
            "Gemma Vision could not return a usable structured result. The OCR result is still saved for review.",
            message
        )
    }

    private fun cropInput(): VerifyCouponWorker.VisionInput {
        return VerifyCouponWorker.VisionInput(
            bitmap = mockk(relaxed = true),
            usedTargetedCrop = true,
            source = "layout",
            normalizedBoundsJson = null,
            pixelCrop = Rect(0, 0, 20, 20),
            layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
            debugEvidence = null
        )
    }

    private fun fullImageInput(): VerifyCouponWorker.VisionInput {
        return VerifyCouponWorker.VisionInput(
            bitmap = mockk(relaxed = true),
            usedTargetedCrop = false,
            source = "full_image",
            normalizedBoundsJson = null,
            pixelCrop = null,
            layoutState = null,
            debugEvidence = null
        )
    }

    private fun extraction(card: VisionCouponCard): VisionFieldExtraction {
        return VisionFieldExtraction(
            cards = listOf(card),
            activeCard = card,
            confidence = 0.95f,
            rawEvidence = card.evidence.orEmpty()
        )
    }

    private fun card(
        storeName: String?,
        description: String?,
        redeemCode: String? = null,
        codeState: String,
        expiryState: String,
        evidence: String
    ): VisionCouponCard {
        return VisionCouponCard(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            expiryText = null,
            codeState = codeState,
            expiryState = expiryState,
            layoutState = Coupon.LayoutState.MODAL_FOREGROUND,
            confidence = 0.95f,
            evidence = evidence,
            bounds = null,
            active = true
        )
    }

    private fun coupon(
        storeName: String,
        description: String,
        redeemCode: String?,
        rawOcrText: String,
        expiryDate: java.util.Date? = null,
        codeState: String = Coupon.CodeState.UNKNOWN,
        expiryState: String = Coupon.ExpiryState.UNKNOWN
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            imageUri = null,
            rawOcrText = rawOcrText,
            expiryDate = expiryDate,
            codeState = codeState,
            expiryState = expiryState,
            extractionSource = Coupon.ExtractionSource.OCR_VERIFIED
        )
    }
}
