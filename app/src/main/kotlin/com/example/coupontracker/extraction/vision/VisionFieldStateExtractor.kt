package com.example.coupontracker.extraction.vision

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.RawVisionExtractionModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionFieldStateExtractor @Inject constructor(
    private val modelSelector: ModelSelector,
    private val securePreferencesManager: SecurePreferencesManager,
    private val parser: VisionFieldJsonParser
) {
    suspend fun extract(bitmap: Bitmap, ocrText: String?): VisionFieldExtraction? {
        if (!securePreferencesManager.isGemmaVisionVerifierEnabled()) {
            Log.i(TAG, "Skipping VLM field-state extraction; Gemma Vision verifier is disabled")
            return null
        }
        val adapter = runCatching { modelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY) }
            .onFailure { error -> Log.w(TAG, "VLM field-state adapter unavailable: ${error.message}") }
            .getOrNull()
        if (adapter == null) return null
        if (adapter.mode.name !in VLM_MODE_NAMES) {
            Log.i(TAG, "Skipping VLM field-state extraction; retry mode is ${adapter.mode}")
            return null
        }
        val rawAdapter = adapter as? RawVisionExtractionModel
        if (rawAdapter == null) {
            Log.w(TAG, "Skipping VLM field-state extraction; ${adapter.mode} has no raw vision capability")
            return null
        }

        return runCatching {
            val result = rawAdapter.extractRawFromImage(
                image = bitmap,
                ocrText = ocrText?.takeIf { it.isNotBlank() },
                prompt = PROMPT
            )
            parser.parse(result.canonicalJson).also { parsed ->
                Log.i(
                    TAG,
                    "VLM field-state extraction succeeded mode=${adapter.mode} cards=${parsed.cards.size} " +
                        "activeLayout=${parsed.activeCard?.layoutState} activeCode=${parsed.activeCard?.codeState}"
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Vision field-state extraction failed: ${error.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "VisionFieldStateExtractor"
        private val VLM_MODE_NAMES = setOf("VLM_GEMMA", "VLM_QWEN", "VLM_MINICPM")
        private const val PROMPT =
            "Inspect this single foreground coupon card or modal. Return JSON only: " +
                "{\"cards\":[{\"active\":true,\"storeName\":string|null,\"description\":string|null," +
                "\"redeemCode\":string|null,\"expiryText\":string|null," +
                "\"codeState\":\"PRESENT|NO_CODE_NEEDED|NOT_VISIBLE|UNKNOWN\"," +
                "\"expiryState\":\"PRESENT|NOT_VISIBLE|UNKNOWN\"," +
                "\"layoutState\":\"COMPLETE|PARTIAL|MODAL_FOREGROUND|MULTI_CARD|LOW_CONFIDENCE\"," +
                "\"confidence\":0.0,\"evidence\":string|null}]}. " +
                "Use PRESENT for code only when the exact code is visible. Use NO_CODE_NEEDED only when the UI says no code is needed. " +
                "Do not invent coupon codes or expiry dates. Ignore background cards when a modal is foreground."
    }
}
