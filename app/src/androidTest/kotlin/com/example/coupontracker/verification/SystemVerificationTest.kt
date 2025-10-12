package com.example.coupontracker.verification

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coupontracker.data.local.CouponDatabase
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.ocr.MlKitOcrEngine
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.LocalLlmOcrService
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Integration test for SystemVerificationHarness
 * This can be run via: ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.coupontracker.verification.SystemVerificationTest
 */
@RunWith(AndroidJUnit4::class)
class SystemVerificationTest {
    private lateinit var database: CouponDatabase
    private lateinit var llmRuntimeManager: LlmRuntimeManager
    private lateinit var localLlmOcrService: LocalLlmOcrService
    private lateinit var telemetryService: ExtractionTelemetryService
    private lateinit var verificationHarness: SystemVerificationHarness

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, CouponDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        llmRuntimeManager = LlmRuntimeManager.getInstance(context)
        val ocrEngine = MlKitOcrEngine(context)
        localLlmOcrService = LocalLlmOcrService(
            context = context,
            ocrEngine = ocrEngine,
            injectedLlmRuntimeManager = llmRuntimeManager
        )
        telemetryService = ExtractionTelemetryService(context)

        verificationHarness = SystemVerificationHarness(
            context = context,
            database = database,
            llmRuntimeManager = llmRuntimeManager,
            localLlmOcrService = localLlmOcrService,
            telemetryService = telemetryService
        )
    }

    @Test
    fun runSystemVerification() = runBlocking {
        val result = verificationHarness.runVerification()

        val summary = verificationHarness.getVerificationSummary(result)
        println(summary)
        android.util.Log.i("SystemVerification", summary)

        // Skip assertion when native model unavailable (emulator)
        if (!result.overallPassed && result.errors.any { it.contains("model not available", true) }) {
            android.util.Log.w("SystemVerification", "Skipping assertion: ${result.errors}")
            return@runBlocking
        }

        assert(result.overallPassed) {
            "System verification failed: ${result.errors.joinToString(", ")}"
        }
    }
}
