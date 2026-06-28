package com.example.coupontracker.domain.usecase

import android.graphics.Bitmap
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.OcrFirstExtractionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CouponUseCaseBoundaryTest {

    @Test
    fun `save coupon delegates normalized description and image identity`() = runTest {
        val repository = mockk<CouponRepository>()
        val useCase = SaveCouponUseCase(repository)
        val coupon = Coupon(
            storeName = "Store",
            description = "  Flat   20% OFF  ",
            redeemCode = "SAVE20",
            imageUri = "content://coupon",
            imagePhash = "phash",
            imageSignature = "sig"
        )
        coEvery {
            repository.saveOrMergeCoupon(
                coupon = coupon,
                normalizedDescription = any(),
                imagePhash = "phash",
                imageSignature = "sig"
            )
        } returns 42L

        val id = useCase(coupon)

        assertEquals(42L, id)
        coVerify {
            repository.saveOrMergeCoupon(
                coupon = coupon,
                normalizedDescription = "flat 20 off",
                imagePhash = "phash",
                imageSignature = "sig"
            )
        }
    }

    @Test
    fun `delete coupon delegates the selected coupon`() = runTest {
        val repository = mockk<CouponRepository>(relaxed = true)
        val useCase = DeleteCouponUseCase(repository)
        val coupon = Coupon(
            storeName = "Store",
            description = "Offer",
            redeemCode = null,
            imageUri = null
        )

        useCase(coupon)

        coVerify { repository.deleteCoupon(coupon) }
    }

    @Test
    fun `share coupon omits missing optional fields`() {
        val useCase = ShareCouponUseCase()
        val coupon = Coupon(
            storeName = "Store",
            description = "Offer",
            redeemCode = null,
            imageUri = null,
            expiryDate = null
        )

        val payload = useCase(coupon)

        assertEquals("Store\nOffer", payload)
        assertFalse(payload.contains("Code:"))
        assertFalse(payload.contains("Expires:"))
    }

    @Test
    fun `share coupon includes code and formatted expiry when present`() {
        val useCase = ShareCouponUseCase()
        val coupon = Coupon(
            storeName = "Store",
            description = "Offer",
            redeemCode = "SAVE20",
            imageUri = null,
            expiryDate = Date(1_735_689_600_000L)
        )

        val payload = useCase(coupon)
        val expectedExpiry = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(coupon.expiryDate!!)

        assertEquals(
            listOf(
                "Store",
                "Offer",
                "Code: SAVE20",
                "Expires: $expectedExpiry"
            ).joinToString("\n"),
            payload
        )
    }

    @Test
    fun `extract coupon forwards bitmap uri and capture timestamp`() = runTest {
        val extractor = mockk<OcrFirstCouponExtractor>()
        val useCase = extractCouponUseCase(extractor)
        val bitmap = mockk<Bitmap>()
        val timestamp = Date(1_735_689_600_000L)
        val expected = OcrFirstExtractionResult(
            coupon = Coupon(
                storeName = "Store",
                description = "Offer",
                redeemCode = null,
                imageUri = null
            ),
            rawOcrText = "Store\nOffer",
            confidence = 0.9f,
            success = true,
            failureReason = null
        )
        coEvery {
            extractor.extract(
                bitmap = bitmap,
                imageUri = "content://coupon",
                captureTimestamp = timestamp
            )
        } returns expected

        val result = useCase(bitmap, "content://coupon", timestamp)

        assertEquals(expected, result)
        coVerify {
            extractor.extract(
                bitmap = bitmap,
                imageUri = "content://coupon",
                captureTimestamp = timestamp
            )
        }
    }

    @Test
    fun `extract coupon scoped ocr request forwards crop scoped text`() = runTest {
        val extractor = mockk<OcrFirstCouponExtractor>()
        val useCase = extractCouponUseCase(extractor)
        val bitmap = mockk<Bitmap>()
        val timestamp = Date(1_735_689_600_000L)
        val expected = OcrFirstExtractionResult(
            coupon = Coupon(
                storeName = "Crop Store",
                description = "Crop Offer",
                redeemCode = null,
                imageUri = "content://crop"
            ),
            rawOcrText = "Crop Store\nCrop Offer",
            confidence = 0.8f,
            success = true,
            failureReason = null
        )
        coEvery {
            extractor.extractFromOcr(
                bitmap = bitmap,
                ocrText = "Crop Store\nCrop Offer",
                ocrHints = mapOf("storeName" to "Crop Store"),
                ocrBlocks = emptyList(),
                imageUri = "content://crop",
                captureTimestamp = timestamp
            )
        } returns expected

        val result = useCase.extract(
            ExtractCouponRequest.ScopedOcrInput(
                bitmap = bitmap,
                ocrText = "Crop Store\nCrop Offer",
                ocrHints = mapOf("storeName" to "Crop Store"),
                imageUri = "content://crop",
                captureTimestamp = timestamp
            )
        )

        assertEquals(expected, result)
        coVerify {
            extractor.extractFromOcr(
                bitmap = bitmap,
                ocrText = "Crop Store\nCrop Offer",
                ocrHints = mapOf("storeName" to "Crop Store"),
                ocrBlocks = emptyList(),
                imageUri = "content://crop",
                captureTimestamp = timestamp
            )
        }
    }

    private fun extractCouponUseCase(
        extractor: OcrFirstCouponExtractor
    ): ExtractCouponUseCase = ExtractCouponUseCase(
        extractor = extractor,
        multiCouponExtractionService = mockk<MultiCouponExtractionService>(),
        routingUseCase = SingleScanRoutingUseCase(),
        fullImageFallbackProbe = mockk<FullImageFallbackProbe>()
    )
}
