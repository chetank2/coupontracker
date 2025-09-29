package com.example.coupontracker.verification

import android.content.Context
import android.util.Log
import com.example.coupontracker.data.local.CouponDatabase
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.LocalLlmOcrService
import kotlinx.coroutines.runBlocking

/**
 * Standalone verification runner for development and CI/CD
 * Can be invoked from application startup or debugging
 */
object VerificationRunner {
    
    private const val TAG = "VerificationRunner"
    
    /**
     * Run verification with provided dependencies
     */
    fun runVerification(
        context: Context,
        database: CouponDatabase,
        llmRuntimeManager: LlmRuntimeManager,
        localLlmOcrService: LocalLlmOcrService,
        telemetryService: ExtractionTelemetryService
    ): SystemVerificationHarness.VerificationResult {
        
        val harness = SystemVerificationHarness(
            context = context,
            database = database,
            llmRuntimeManager = llmRuntimeManager,
            localLlmOcrService = localLlmOcrService,
            telemetryService = telemetryService
        )
        
        return runBlocking {
            val result = harness.runVerification()
            
            // Log summary
            val summary = harness.getVerificationSummary(result)
            Log.i(TAG, summary)
            
            // Also print to console for development
            println(summary)
            
            result
        }
    }
    
    /**
     * Quick verification check for app startup
     */
    fun quickHealthCheck(
        context: Context,
        database: CouponDatabase
    ): Boolean {
        return try {
            // Basic database connectivity check
            val dao = database.couponDao()
            dao.searchCoupons("health_check")
            
            Log.d(TAG, "✅ Quick health check passed")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Quick health check failed", e)
            false
        }
    }
}
