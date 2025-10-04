package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.util.BitmapManager
import com.example.coupontracker.util.ExtractionPerformanceMonitor
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.MultiEngineOCR
import io.mockk.anyConstructed
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.mockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class ScannerViewModelTest {

    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        mockkConstructor(TwoStageDetector::class)
        every { anyConstructed<TwoStageDetector>().getModelInfo() } returns "mock"

        mockkConstructor(MultiEngineOCR::class)
        justRun { anyConstructed<MultiEngineOCR>().setNetworkAvailability(any()) }

        val application: Application = mockk(relaxed = true)
        val context: Context = mockk(relaxed = true)
        val couponRepository: CouponRepository = mockk(relaxed = true)
        val ocrEngine: com.example.coupontracker.ocr.OcrEngine = mockk(relaxed = true)
        val localLlmOcrService: LocalLlmOcrService = mockk(relaxed = true)
        val telemetryService: ExtractionTelemetryService = mockk(relaxed = true)
        val universalExtractionService: UniversalExtractionService = mockk(relaxed = true)
        val performanceMonitor: ExtractionPerformanceMonitor = mockk(relaxed = true)
        val bitmapManager: BitmapManager = mockk(relaxed = true)

        viewModel = ScannerViewModel(
            application,
            context,
            couponRepository,
            ocrEngine,
            localLlmOcrService,
            telemetryService,
            universalExtractionService,
            performanceMonitor,
            bitmapManager
        )
    }

    @After
    fun tearDown() {
        unmockkConstructor(TwoStageDetector::class)
        unmockkConstructor(MultiEngineOCR::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun `fallback merge ignores store names that duplicate detected code`() {
        val extractedInfo = mutableMapOf(
            "code" to "SAVE2025",
            "description" to "Sample description"
        )
        val fallbackFields = mapOf(
            "storeName" to "SAVE2025",
            "code" to "SAVE2025"
        )

        viewModel.mergeValidatedFields(extractedInfo, fallbackFields)

        val persistedStore = extractedInfo["storeName"] ?: extractedInfo["app"] ?: "Unknown Store"
        assertEquals("Unknown Store", persistedStore)
    }
}
