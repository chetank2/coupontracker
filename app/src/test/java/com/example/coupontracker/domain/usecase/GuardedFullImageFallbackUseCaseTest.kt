package com.example.coupontracker.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.capture.FullImageFallbackReviewCouponFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GuardedFullImageFallbackUseCaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saveScannedCouponUseCase = mockk<SaveScannedCouponUseCase>()
    private val useCase = GuardedFullImageFallbackUseCase(
        context = context,
        reviewCouponFactory = FullImageFallbackReviewCouponFactory(),
        saveScannedCouponUseCase = saveScannedCouponUseCase
    )

    @Test
    fun `preview creates guarded review coupon without persisting`() = runTest {
        val imageUri = appStorageUri()

        val result = useCase(
            imageUri = imageUri,
            rawOcrText = "Store\nOffer",
            reason = "classified_multi_coupon",
            persistImmediately = false
        )

        assertTrue(result is GuardedFullImageFallbackResult.Preview)
        assertEquals(imageUri.toString(), result.coupon.imageUri)
        assertEquals("needs review multiple coupons could not be isolated", result.normalizedDescription)
        assertEquals("Store\nOffer", result.coupon.rawOcrText)
        assertEquals(
            "full_image_fallback_guard; reason=classified_multi_coupon",
            result.coupon.debugVisionEvidence
        )
    }

    @Test
    fun `immediate mode persists guarded review coupon with needs review status`() = runTest {
        val imageUri = appStorageUri()
        val savedCoupon = Coupon(
            id = 7L,
            storeName = Coupon.Defaults.UNKNOWN_STORE,
            description = "Needs review: multiple coupons could not be isolated",
            redeemCode = null,
            imageUri = imageUri.toString()
        )
        coEvery {
            saveScannedCouponUseCase(
                coupon = any(),
                normalizedDescription = any(),
                llmStatusName = GuardedFullImageFallbackUseCase.REVIEW_STATUS_NAME,
                debugSnapshot = null
            )
        } returns SaveScannedCouponResult(
            savedCouponId = 7L,
            couponForUi = savedCoupon,
            kind = SaveScannedCouponResult.Kind.SAVED,
            analyticsResult = "created",
            persisted = true
        )

        val result = useCase(
            imageUri = imageUri,
            rawOcrText = "",
            reason = "multiple_regions_detected",
            persistImmediately = true
        )

        assertTrue(result is GuardedFullImageFallbackResult.Persisted)
        assertEquals(7L, (result as GuardedFullImageFallbackResult.Persisted).saveResult.savedCouponId)
        coVerify {
            saveScannedCouponUseCase(
                coupon = match { coupon ->
                    coupon.imageUri == imageUri.toString() &&
                        coupon.cleanupError?.contains("reason=multiple_regions_detected") == true
                },
                normalizedDescription = "needs review multiple coupons could not be isolated",
                llmStatusName = GuardedFullImageFallbackUseCase.REVIEW_STATUS_NAME,
                debugSnapshot = null
            )
        }
    }

    private fun appStorageUri(): Uri {
        val image = File(context.filesDir, "fallback-test.jpg").apply {
            writeText("not-an-image")
        }
        return Uri.fromFile(image)
    }
}
