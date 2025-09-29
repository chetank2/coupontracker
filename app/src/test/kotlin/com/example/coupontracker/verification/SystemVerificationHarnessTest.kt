package com.example.coupontracker.verification

import android.content.Context
import com.example.coupontracker.data.local.CouponDatabase
import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.util.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Unit tests for SystemVerificationHarness
 */
@RunWith(RobolectricTestRunner::class)
class SystemVerificationHarnessTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockDatabase: CouponDatabase

    @Mock
    private lateinit var mockDao: CouponDao

    @Mock
    private lateinit var mockLlmRuntimeManager: LlmRuntimeManager

    @Mock
    private lateinit var mockLocalLlmOcrService: LocalLlmOcrService

    @Mock
    private lateinit var mockTelemetryService: ExtractionTelemetryService

    private lateinit var verificationHarness: SystemVerificationHarness

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        // Setup mock database
        whenever(mockDatabase.couponDao()).thenReturn(mockDao)
        whenever(mockDao.searchCoupons("test query")).thenReturn(flowOf(emptyList()))
        
        verificationHarness = SystemVerificationHarness(
            context = mockContext,
            database = mockDatabase,
            llmRuntimeManager = mockLlmRuntimeManager,
            localLlmOcrService = mockLocalLlmOcrService,
            telemetryService = mockTelemetryService
        )
    }

    @Test
    fun `verification should handle missing native library gracefully`() = runBlocking {
        // Mock native library not available
        // (MlcLlmNative.loadLibrary() will return false in test environment)
        
        val result = verificationHarness.runVerification()
        
        // Should pass even without native library
        assertTrue("Migration test should pass", result.migrationTestPassed)
        assertEquals("Native state should be MISSING", NativeState.MISSING, result.nativeState)
        
        // Self-test and path assertion should be skipped
        assertTrue("Self-test should be skipped/passed", result.selfTestPassed)
        assertTrue("Path assertion should be skipped/passed", result.pathAssertionPassed)
        
        // Overall should pass in mock environment
        assertTrue("Overall verification should pass in mock environment", result.overallPassed)
    }

    @Test
    fun `verification should handle LLM extraction failure`() = runBlocking {
        // Mock LLM service to return failure
        whenever(mockLocalLlmOcrService.processCouponImageTyped(org.mockito.kotlin.any()))
            .thenReturn(ExtractResult.Failed(
                stage = ExtractionStage.LLM,
                error = Exception("Mock LLM failure")
            ))
        
        val result = verificationHarness.runVerification()
        
        // Migration should still pass
        assertTrue("Migration test should pass", result.migrationTestPassed)
        
        // Errors should be captured
        assertTrue("Should have errors", result.errors.isNotEmpty())
        assertTrue("Should contain LLM failure error", 
                  result.errors.any { it.contains("Mock LLM failure") })
    }

    @Test
    fun `verification should handle database errors`() = runBlocking {
        // Mock database to throw exception
        whenever(mockDao.searchCoupons(org.mockito.kotlin.any()))
            .thenThrow(RuntimeException("Mock database error"))
        
        val result = verificationHarness.runVerification()
        
        // Migration test should fail
        assertFalse("Migration test should fail", result.migrationTestPassed)
        
        // Should capture database error
        assertTrue("Should have database error", 
                  result.errors.any { it.contains("Mock database error") })
        
        // Overall should fail
        assertFalse("Overall verification should fail", result.overallPassed)
    }

    @Test
    fun `getVerificationSummary should format results correctly`() = runBlocking {
        val mockResult = SystemVerificationHarness.VerificationResult(
            selfTestPassed = true,
            migrationTestPassed = true,
            pathAssertionPassed = false,
            nativeState = NativeState.MOCK,
            overallPassed = false,
            details = mapOf(
                "native_state" to "MOCK",
                "self_test" to "PASSED",
                "migration_test" to "PASSED",
                "path_assertion" to "FAILED"
            ),
            errors = listOf("Path assertion failed")
        )
        
        val summary = verificationHarness.getVerificationSummary(mockResult)
        
        assertTrue("Should contain overall status", summary.contains("Overall Status: ❌ FAILED"))
        assertTrue("Should contain native state", summary.contains("Native State: MOCK"))
        assertTrue("Should contain self-test result", summary.contains("Self-test: ✅ PASSED"))
        assertTrue("Should contain migration result", summary.contains("Migration: ✅ PASSED"))
        assertTrue("Should contain path assertion result", summary.contains("Path Assertion: ❌ FAILED"))
        assertTrue("Should contain errors section", summary.contains("Errors:"))
        assertTrue("Should contain specific error", summary.contains("Path assertion failed"))
        assertTrue("Should contain details section", summary.contains("Details:"))
    }

    @Test
    fun `verification runner quick health check should work`() {
        val result = VerificationRunner.quickHealthCheck(mockContext, mockDatabase)
        
        // Should pass with mocked database
        assertTrue("Quick health check should pass", result)
    }
}
