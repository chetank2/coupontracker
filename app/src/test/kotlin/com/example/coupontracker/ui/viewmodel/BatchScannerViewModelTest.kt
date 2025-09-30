package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.BitmapManager
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.ExtractionSignals
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.UriPersistenceManager
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.universal.UniversalExtractionService
import io.mockk.any
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BatchScannerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `processWithOcrFirstPath falls back to LLM when OCR fails`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = mockk<CouponRepository>(relaxed = true)
        val bitmapManager = mockk<BitmapManager>(relaxed = true)
        val llmService = mockk<LocalLlmOcrService>()
        val universalExtractionService = mockk<UniversalExtractionService>(relaxed = true)

        val viewModel = BatchScannerViewModel(
            application,
            application,
            repository,
            bitmapManager,
            llmService,
            universalExtractionService
        )

        val multiEngineMock = mockk<MultiEngineOCR>()
        coEvery { multiEngineMock.processImage(any<Bitmap>()) } returns MultiEngineOCR.OCRResult.Error("ocr boom")

        val uriPersistenceManagerMock = mockk<UriPersistenceManager>()
        val persistedUri = Uri.parse("content://persisted")
        coEvery { uriPersistenceManagerMock.persistUri(any()) } returns persistedUri

        setPrivateField(viewModel, "multiEngineOCR", multiEngineMock)
        setPrivateField(viewModel, "uriPersistenceManager", uriPersistenceManagerMock)

        val couponInfo = CouponInfo(
            storeName = "LLM Store",
            description = "LLM description",
            cashbackAmount = 10.0,
            redeemCode = "LLM123",
            discountType = "PERCENTAGE"
        )
        coEvery { llmService.processCouponImageTyped(any()) } returns ExtractResult.Good(
            info = couponInfo,
            signals = ExtractionSignals(
                qualityScore = 95,
                fieldConfidences = emptyMap(),
                processingTimeMs = 12,
                memoryUsageMB = 1.2f,
                stage = ExtractionStage.LLM
            )
        )

        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val uri = Uri.parse("content://test")

        val result = callPrivateSuspend(viewModel, "processWithOcrFirstPath", uri, bitmap, true)

        assertIs<Coupon>(result)
        assertEquals("LLM Store", result.storeName)
        assertEquals("LLM123", result.redeemCode)
        assertFalse(result.storeName.contains("Example", ignoreCase = true))
        assertEquals(persistedUri.toString(), result.imageUri)

        coVerify { multiEngineMock.processImage(bitmap) }
        coVerify { llmService.processCouponImageTyped(bitmap) }
        coVerify { uriPersistenceManagerMock.persistUri(uri) }

        bitmap.recycle()
    }

    private suspend fun callPrivateSuspend(
        target: Any,
        functionName: String,
        vararg args: Any?
    ): Any? {
        val function = target::class.declaredFunctions.first { it.name == functionName }
        function.isAccessible = true
        return function.callSuspend(target, *args)
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
