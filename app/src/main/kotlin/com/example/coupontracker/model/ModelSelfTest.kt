package com.example.coupontracker.model

import android.util.Log
import com.example.coupontracker.llm.LlmRuntimeManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

sealed class SelfTestResult {
    data class Success(val durationMs: Long, val modelName: String, val isRealInference: Boolean = false) : SelfTestResult()
    data class Failed(val reason: String, val error: Throwable? = null) : SelfTestResult()
}

@Singleton
class ModelSelfTest @Inject constructor(
    private val llmRuntimeManager: LlmRuntimeManager
) {
    companion object {
        private const val TAG = "ModelSelfTest"
        private const val TIMEOUT_MS = 60_000L
    }
    
    /**
     * Run the same text-only inference path used by coupon cleanup.
     * Returns Success only if the installed model can load and produce JSON.
     */
    suspend fun runSelfTest(): SelfTestResult {
        Log.d(TAG, "Starting model self-test (${TIMEOUT_MS}ms timeout)...")
        val startTime = System.currentTimeMillis()
        
        return try {
            if (!llmRuntimeManager.isModelAvailable()) {
                return SelfTestResult.Failed("Qwen model is not installed or verified")
            }

            val modelInfo = llmRuntimeManager.getModelInfo()
            val prompt = """
                Return only JSON for this coupon text:
                Store: Domino's
                Offer: Get 20% off
                Code: PIZZA20
                Expiry: 30 Jun 2026

                Required keys: storeName, description, redeemCode, expiryDate.
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                llmRuntimeManager.runTextInference(
                    ocrText = "Domino's Get 20% off code PIZZA20 valid till 30 Jun 2026",
                    prompt = prompt,
                    keepLoaded = false,
                    maxTokensOverride = 96
                )
            }

            val duration = System.currentTimeMillis() - startTime
            val jsonLike = response?.trimStart()?.startsWith("{") == true
            if (!jsonLike) {
                val preview = response?.take(120)?.replace('\n', ' ')
                Log.w(TAG, "Self-test response was not JSON: $preview")
                return SelfTestResult.Failed("Reader loaded but did not return valid JSON")
            }

            Log.d(TAG, "Self-test passed with ${modelInfo.name} in ${duration}ms")
            SelfTestResult.Success(duration, modelInfo.name, isRealInference = true)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "✗ Self-test TIMEOUT after ${TIMEOUT_MS}ms")
            llmRuntimeManager.cancelOngoingInference()
            llmRuntimeManager.resetAfterTimeout()
            SelfTestResult.Failed("Reader check timed out")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Self-test failed with exception", e)
            SelfTestResult.Failed("Reader check failed: ${e.message}", e)
        }
    }
}
