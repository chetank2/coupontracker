package com.example.coupontracker.model

import android.util.Log
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import org.json.JSONObject
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
        private const val SELF_TEST_STORE = "FixtureMart"
        private const val SELF_TEST_CODE = "FIXTURE42"
        private const val SELF_TEST_EXPIRY_DAY = "31"
        private const val SELF_TEST_EXPIRY_MONTH = "Jul"
        private const val SELF_TEST_EXPIRY_YEAR = "2026"
        private const val SELF_TEST_OFFER_VALUE = "42"
        private const val SELF_TEST_OCR =
            "$SELF_TEST_STORE coupon. Save $SELF_TEST_OFFER_VALUE percent with code $SELF_TEST_CODE. " +
                "Valid till $SELF_TEST_EXPIRY_DAY $SELF_TEST_EXPIRY_MONTH $SELF_TEST_EXPIRY_YEAR."
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
                Return only JSON for the provided OCR text.
                Required keys: storeName, description, redeemCode, expiryDate.
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                llmRuntimeManager.runTextInference(
                    ocrText = SELF_TEST_OCR,
                    prompt = prompt,
                    keepLoaded = false,
                    maxTokensOverride = 96
                )
            }

            val duration = System.currentTimeMillis() - startTime
            val rawResponse = response.orEmpty().trim()
            val json = runCatching { JSONObject(rawResponse) }.getOrElse {
                val preview = rawResponse.take(160).replace('\n', ' ')
                Log.w(TAG, "Self-test response was not parseable JSON: $preview", it)
                return SelfTestResult.Failed("Reader loaded but did not return valid JSON")
            }

            val contractReport = CouponJsonContract.validate(json)
            if (!contractReport.valid) {
                Log.w(
                    TAG,
                    "Self-test JSON failed contract: missing=${contractReport.missingKeys}, " +
                        "unknown=${contractReport.unknownKeys}, errors=${contractReport.structuralErrors}"
                )
                return SelfTestResult.Failed("Reader loaded but failed the coupon JSON check")
            }

            val store = json.optString("storeName")
            val code = json.optString("redeemCode")
            val expiry = json.optString("expiryDate")
            val description = json.optString("description")
            val matchesFixture = store.equals(SELF_TEST_STORE, ignoreCase = true) &&
                code.equals(SELF_TEST_CODE, ignoreCase = true) &&
                expiry.contains(SELF_TEST_EXPIRY_DAY, ignoreCase = true) &&
                expiry.contains(SELF_TEST_EXPIRY_MONTH, ignoreCase = true) &&
                expiry.contains(SELF_TEST_EXPIRY_YEAR, ignoreCase = true) &&
                description.contains(SELF_TEST_OFFER_VALUE, ignoreCase = true)

            if (!matchesFixture) {
                Log.w(
                    TAG,
                    "Self-test response did not match fixture: store='$store', code='$code', " +
                        "expiry='$expiry', description='$description'"
                )
                return SelfTestResult.Failed("Reader loaded but returned incorrect coupon details")
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
