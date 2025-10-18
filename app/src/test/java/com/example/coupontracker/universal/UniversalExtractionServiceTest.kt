package com.example.coupontracker.universal

import android.content.Context
import android.graphics.Bitmap
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ProgressiveExtractionResult
import com.example.coupontracker.extraction.ProgressiveExtractionService
import com.example.coupontracker.universal.ExtractionCandidate
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class UniversalExtractionServiceTest {

    private val androidContext: Context = mockk(relaxed = true)
    private val fieldDetector: UniversalFieldDetector = mockk()
    private val patternLearner: PatternLearningEngine = mockk()
    private val confidenceScorer: AdaptiveConfidenceScorer = mockk()
    private val progressiveExtractionService: ProgressiveExtractionService = mockk()

    private lateinit var service: UniversalExtractionService

    private val bitmap: Bitmap = mockk(relaxed = true)

    @Before
    fun setUp() {
        service = UniversalExtractionService(
            androidContext,
            fieldDetector,
            patternLearner,
            confidenceScorer,
            progressiveExtractionService
        )

        coEvery { fieldDetector.detectFields(any(), any(), any()) } returns emptyMap()
        coEvery { patternLearner.getRelevantPatterns(any(), any()) } returns emptyList()
        coEvery { confidenceScorer.scoreCandidate(any(), any()) } answers { firstArg<ExtractionCandidate>().confidence }
        coJustRun { confidenceScorer.updateFromFeedback(any(), any(), any()) }
        coEvery {
            progressiveExtractionService.extractCoupon(any(), any(), any(), any(), any(), any())
        } returns emptyProgressiveResult()
    }

    @Test
    fun `relative expiry phrases yield calendar date`() = runBlocking {
        val ocrText = "FLASH SALE!\nEXPIRES IN 05 DAYS"

        val result = service.extractCoupon(bitmap, ocrText)

        val expiryCandidate = result.extractedFields[FieldType.EXPIRY_DATE]
        assertNotNull(expiryCandidate)
        assertTrue(expiryCandidate!!.text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

        val couponDate = result.coupon.expiryDate
        assertNotNull(couponDate)
        val expectedDate = LocalDate.now().plusDays(5)
        val actualDate = couponDate!!.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        assertEquals(expectedDate, actualDate)
    }

    @Test
    fun `compound rupee amounts are preserved and summed`() = runBlocking {
        val ocrText = "MEGA DEAL\n₹599 + ₹50 cashback on orders above ₹999"

        val result = service.extractCoupon(bitmap, ocrText)

        val amountCandidate = result.extractedFields[FieldType.AMOUNT]
        assertNotNull(amountCandidate)
        assertEquals("₹599 + ₹50 cashback", amountCandidate!!.text)
        assertEquals(649.0, result.coupon.cashbackAmount)
    }

    @Test
    fun `store name inferred from contextual phrase`() = runBlocking {
        val ocrText = "Weekend treat from the folks at Zepto Mart -- grab rewards now!"

        val result = service.extractCoupon(bitmap, ocrText)

        val storeCandidate = result.extractedFields[FieldType.STORE_NAME]
        assertNotNull(storeCandidate)
        assertEquals("Zepto Mart", storeCandidate!!.text)
        assertTrue(result.coupon.description.startsWith("Weekend treat"))
    }

    private fun emptyProgressiveResult(): ProgressiveExtractionResult {
        return ProgressiveExtractionResult(
            coupon = stubCoupon(),
            confidence = 0f,
            extractedFields = emptyMap(),
            success = false,
            extractionAttempts = emptyList(),
            passesUsed = 0,
            error = null
        )
    }

    private fun stubCoupon(): Coupon {
        return Coupon(
            storeName = "Placeholder",
            description = "placeholder",
            cashbackAmount = 0.0,
            redeemCode = null,
            imageUri = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}
