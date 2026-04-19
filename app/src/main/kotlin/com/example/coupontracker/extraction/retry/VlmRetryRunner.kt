package com.example.coupontracker.extraction.retry

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.ModelSelectorException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the conservative VLM retry loop on a canonical extraction JSON:
 *
 * 1. Asks `VlmRetryEvaluator` whether any field looks weak.
 * 2. If yes, selects the configured `LOW_CONFIDENCE_RETRY` adapter and
 *    invokes its `extractFromImage(bitmap, ocrText, prompt)` path.
 * 3. Merges the VLM's canonical JSON into the original via `VlmMerger`.
 * 4. Returns the (possibly merged) JSON.
 *
 * Adopted by `LocalLlmOcrService.processCouponImage` with a single call
 * after the default text path produces canonical JSON. Splitting the runner
 * out keeps the integration point one line and keeps the retry logic
 * unit-testable without standing up the full LocalLlmOcrService graph.
 */
@Singleton
class VlmRetryRunner @Inject constructor(
    private val evaluator: VlmRetryEvaluator,
    private val modelSelector: ModelSelector
) {

    /**
     * @return Pair of (final canonical JSON string, list of trigger codes
     *         the evaluator fired — empty when retry was skipped). Always
     *         returns valid JSON; a retry failure preserves the input.
     */
    suspend fun maybeRetry(
        canonicalJson: String,
        bitmap: Bitmap,
        ocrText: String?,
        prompt: String
    ): Pair<String, List<String>> {
        val canonicalObj = try {
            JSONObject(canonicalJson)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse canonical JSON for retry; skipping", e)
            return canonicalJson to emptyList()
        }
        val triggers = evaluator.evaluate(canonicalObj, ocrText.orEmpty())
        if (triggers.isEmpty()) {
            return canonicalJson to emptyList()
        }
        val triggerCodes = triggers.map { it.code }

        val adapter = try {
            modelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY)
        } catch (e: ModelSelectorException) {
            Log.i(TAG, "VLM retry slot unconfigured (mode=${e.mode}); skipping")
            return canonicalJson to triggerCodes
        }

        val vlmJson = try {
            adapter.extractFromImage(bitmap, ocrText, prompt).canonicalJson
        } catch (e: Exception) {
            Log.w(TAG, "VLM retry threw; preserving default JSON", e)
            return canonicalJson to triggerCodes
        }

        val vlmObj = try {
            JSONObject(vlmJson)
        } catch (e: Exception) {
            Log.w(TAG, "VLM produced invalid JSON; preserving default", e)
            return canonicalJson to triggerCodes
        }

        val merged = VlmMerger.merge(canonicalObj, vlmObj, ocrText.orEmpty())
        return merged.toString() to triggerCodes
    }

    companion object {
        private const val TAG = "VlmRetryRunner"
    }
}
