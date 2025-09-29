package com.example.coupontracker.verification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coupontracker.data.local.CouponDatabase
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.LocalLlmOcrService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration test for SystemVerificationHarness
 * This can be run via: ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.coupontracker.verification.SystemVerificationTest
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SystemVerificationTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var database: CouponDatabase

    @Inject
    lateinit var llmRuntimeManager: LlmRuntimeManager

    @Inject
    lateinit var localLlmOcrService: LocalLlmOcrService

    @Inject
    lateinit var telemetryService: ExtractionTelemetryService

    private lateinit var verificationHarness: SystemVerificationHarness

    @Before
    fun setup() {
        hiltRule.inject()
        
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        
        // Print detailed results
        val summary = verificationHarness.getVerificationSummary(result)
        println(summary)
        
        // Log results for CI/CD
        android.util.Log.i("SystemVerification", summary)
        
        // Assert overall success (but allow partial failures in mock environments)
        if (result.nativeState == com.example.coupontracker.util.NativeState.REAL) {
            assert(result.overallPassed) { 
                "System verification failed with real native library: ${result.errors.joinToString(", ")}" 
            }
        } else {
            // In mock/missing native environments, just verify the harness runs
            assert(result.migrationTestPassed) { 
                "Migration test should always pass: ${result.errors.joinToString(", ")}" 
            }
        }
    }
}
