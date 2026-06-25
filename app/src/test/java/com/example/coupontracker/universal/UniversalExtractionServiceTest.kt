package com.example.coupontracker.universal

import android.content.Context
import android.graphics.Bitmap
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
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

        coEvery {
            progressiveExtractionService.extractCoupon(any(), any(), any(), any(), any(), any())
        } returns progressiveResult(
            mapOf(
                FieldType.EXPIRY_DATE to FieldCandidate(
                    value = LocalDate.now().plusDays(5).toString(),
                    confidence = 0.85f,
                    source = "test_relative_expiry",
                    context = null
                )
            )
        )

        val result = service.extractCoupon(bitmap, ocrText)

        val expiryCandidate = result.extractedFields[FieldType.EXPIRY_DATE]
        assertNotNull(expiryCandidate)
        assertTrue(expiryCandidate!!.text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

        val couponDate = requireNotNull(result.coupon.expiryDate)
        val expectedDate = LocalDate.now().plusDays(5)
        val actualDate = couponDate.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        assertEquals(expectedDate, actualDate)
    }

    @Test
    fun `relative expiry uses capture timestamp instead of current date`() = runBlocking {
        val screenshotDate = Date.from(
            LocalDate.of(2025, 5, 2)
                .atStartOfDay(ZoneId.of("Asia/Kolkata"))
                .toInstant()
        )
        val ocrText = "PORTRONICS\nEXPIRES IN 10 DAYS\nCode OCE10"

        coEvery {
            progressiveExtractionService.extractCoupon(any(), any(), any(), any(), any(), any())
        } returns emptyProgressiveResult()

        val result = service.extractCoupon(
            image = bitmap,
            ocrText = ocrText,
            context = ExtractionContext(captureTimestamp = screenshotDate)
        )

        val couponDate = requireNotNull(result.coupon.expiryDate)
        val actualDate = couponDate.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        assertEquals(LocalDate.of(2025, 5, 12), actualDate)
    }

    @Test
    fun `compound rupee amounts are preserved and summed`() = runBlocking {
        val ocrText = "MEGA DEAL\n₹599 + ₹50 cashback on orders above ₹999"

        coEvery {
            progressiveExtractionService.extractCoupon(any(), any(), any(), any(), any(), any())
        } returns progressiveResult(
            mapOf(
                FieldType.AMOUNT to FieldCandidate(
                    value = "₹599 + ₹50 cashback",
                    confidence = 0.72f,
                    source = "test_compound_amount",
                    context = null
                )
            )
        )

        val result = service.extractCoupon(bitmap, ocrText)

        val amountCandidate = result.extractedFields[FieldType.AMOUNT]
        assertNotNull(amountCandidate)
        assertTrue(result.extractedFields.containsKey(FieldType.AMOUNT))
        assertEquals("₹599 + ₹50 cashback", amountCandidate!!.text)
        assertTrue(result.coupon.description.contains("Cashback:"))
        assertTrue(result.coupon.description.contains("₹599 + ₹50 cashback"))
    }

    @Test
    fun `store name inferred from contextual phrase`() = runBlocking {
        val ocrText = "Get 20% off from the folks at Zepto Mart this weekend!"

        coEvery {
            progressiveExtractionService.extractCoupon(any(), any(), any(), any(), any(), any())
        } returns progressiveResult(
            mapOf(
                FieldType.STORE_NAME to FieldCandidate(
                    value = "Zepto Mart",
                    confidence = 0.78f,
                    source = "test_context_store",
                    context = null
                ),
                FieldType.DESCRIPTION to FieldCandidate(
                    value = "Get 20% off from the folks at Zepto Mart this weekend!",
                    confidence = 0.6f,
                    source = "test_context_description",
                    context = null
                )
            )
        )

        val result = service.extractCoupon(bitmap, ocrText)

        val storeCandidate = result.extractedFields[FieldType.STORE_NAME]
        println("Store candidate=${storeCandidate?.text}, couponStore=${result.coupon.storeName}")
        assertNotNull(storeCandidate)
        assertEquals("Zepto Mart", storeCandidate!!.text)
        assertEquals("Zepto Mart", result.coupon.storeName)
        assertTrue(result.coupon.description.startsWith("Get 20% off"))
    }

    private fun emptyProgressiveResult(): ProgressiveExtractionResult =
        progressiveResult(emptyMap(), success = false, confidence = 0f)

    private fun progressiveResult(
        fields: Map<FieldType, FieldCandidate>,
        success: Boolean = true,
        confidence: Float = 0.75f
    ): ProgressiveExtractionResult =
        ProgressiveExtractionResult(
            coupon = stubCoupon(),
            confidence = confidence,
            extractedFields = fields,
            success = success,
            extractionAttempts = emptyList(),
            passesUsed = if (success) 1 else 0,
            error = if (success) null else "no_candidates"
        )

    private fun stubCoupon(): Coupon {
        return Coupon(
            storeName = "Placeholder",
            description = "placeholder",
            redeemCode = null,
            imageUri = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}
