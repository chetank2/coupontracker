package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class VerifyCouponUseCaseTest {

    private val fixedDate = Date(123456789L)
    private val useCase = VerifyCouponUseCase(now = { fixedDate })

    @Test
    fun `markRunning starts cleanup without changing extracted fields`() {
        val coupon = coupon(
            cleanupStatus = Coupon.CleanupStatus.FAILED,
            cleanupError = "old error",
            storeName = "Store",
            description = "Save 10%"
        )

        val running = useCase.markRunning(coupon)

        assertEquals(Coupon.CleanupStatus.RUNNING, running.cleanupStatus)
        assertEquals(fixedDate, running.cleanupStartedAt)
        assertEquals(null, running.cleanupFinishedAt)
        assertEquals(null, running.cleanupError)
        assertEquals("Store", running.storeName)
        assertEquals("Save 10%", running.description)
    }

    @Test
    fun `automatic verification requires installed enabled Gemma and a review need`() {
        val deterministic = coupon(
            redeemCode = null,
            expiryDate = null,
            codeState = Coupon.CodeState.UNKNOWN,
            expiryState = Coupon.ExpiryState.UNKNOWN
        )

        assertTrue(
            useCase.shouldRunVisionVerification(
                userRequested = false,
                automaticVerification = true,
                deterministicCleaned = deterministic,
                rawOcr = "Store\nSave 10%",
                gemmaEnabled = true,
                gemmaInstalled = true
            )
        )
        assertFalse(
            useCase.shouldRunVisionVerification(
                userRequested = false,
                automaticVerification = true,
                deterministicCleaned = deterministic,
                rawOcr = "Store\nSave 10%",
                gemmaEnabled = false,
                gemmaInstalled = true
            )
        )
    }

    @Test
    fun `user edits win when merging latest coupon state`() {
        val baseline = coupon(
            storeName = "Baseline Store",
            cleanupStatus = Coupon.CleanupStatus.RUNNING,
            extractionSource = Coupon.ExtractionSource.OCR_VERIFIED
        )
        val latest = coupon(
            storeName = "Edited Store",
            cleanupStatus = Coupon.CleanupStatus.NONE,
            extractionSource = Coupon.ExtractionSource.USER_EDITED
        )

        val merged = useCase.mergeLatestCouponState(baseline, latest)

        assertEquals("Edited Store", merged.storeName)
        assertEquals(Coupon.ExtractionSource.USER_EDITED, merged.extractionSource)
    }

    @Test
    fun `non-user latest state only updates cleanup fields`() {
        val baseline = coupon(
            storeName = "Baseline Store",
            cleanupStatus = Coupon.CleanupStatus.RUNNING,
            extractionSource = Coupon.ExtractionSource.OCR_VERIFIED
        )
        val latest = coupon(
            storeName = "Latest Store",
            cleanupStatus = Coupon.CleanupStatus.FAILED,
            cleanupError = "latest failure",
            extractionSource = Coupon.ExtractionSource.OCR_VERIFIED
        )

        val merged = useCase.mergeLatestCouponState(baseline, latest)

        assertEquals("Baseline Store", merged.storeName)
        assertEquals(Coupon.CleanupStatus.FAILED, merged.cleanupStatus)
        assertEquals("latest failure", merged.cleanupError)
    }

    private fun coupon(
        storeName: String = "Store",
        description: String = "Save 10%",
        redeemCode: String? = "SAVE10",
        expiryDate: Date? = Date(999999999L),
        codeState: String = Coupon.CodeState.PRESENT,
        expiryState: String = Coupon.ExpiryState.PRESENT,
        cleanupStatus: String = Coupon.CleanupStatus.NONE,
        cleanupError: String? = null,
        extractionSource: String? = Coupon.ExtractionSource.OCR_VERIFIED
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            imageUri = null,
            expiryDate = expiryDate,
            codeState = codeState,
            expiryState = expiryState,
            cleanupStatus = cleanupStatus,
            cleanupError = cleanupError,
            extractionSource = extractionSource
        )
    }
}
