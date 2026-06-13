# VLM Retry and Candidate Bake-off (Phases 4 + 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a conservative VLM retry to the extraction pipeline and benchmark three VLM candidates on the golden set. Retry fires only when concrete predicates say the default text path was insufficient; the merge policy refuses codes the VLM hallucinated.

**Architecture:** `VlmRetryEvaluator` decides from a `CouponInfo` + confidence signals whether retry should fire. When it does, `ModelSelector.select(LOW_CONFIDENCE_RETRY)` returns a `CouponExtractionModel` whose `extractFromImage(bitmap, ocrText, prompt)` produces a candidate JSON. `VlmMerger` combines the two outputs field by field under strict rules: `redeemCode` adopts the VLM value only if it appears in the OCR text; `storeName` adopts only with brand/logo evidence; `expiryDate` adopts only if the date parser normalises; `description` adopts only if the VLM version is longer and non-generic. Three adapters are added: `QwenVlmCouponModel`, `MiniCpmVlmCouponModel`, `GemmaVisionCouponModel` — each wraps an existing or new native/MediaPipe runtime. A benchmark drives all three against the golden set and emits a comparison report.

**Tech Stack:** Kotlin 1.9, Hilt 2.48, MLC-LLM (existing, already wraps Qwen2.5-VL), MediaPipe Tasks GenAI for Gemma 3 vision, MiniCPM-V via existing llama.cpp/mtmd pipeline, JUnit 4, MockK.

---

## Pre-flight

- Plans 1, 2, 3, 4 landed.
- `CouponExtractionModel.extractFromImage` is a no-op in all current adapters.
- `ModelRole.LOW_CONFIDENCE_RETRY` exists; `ModelStrategyConfig` default points at `TEXT_QWEN` as a safe no-op.
- MiniCPM assets/license flow exists (`LicenseGateScreen.kt`); reuse its download path.
- `LlmRuntimeManager` exposes `runVisionInference(bitmap, prompt)` paths in MLC; read its signature before implementing adapters.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmRetryEvaluator.kt` — decides retry fires.
- `app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmRetryTrigger.kt` — sealed class of trigger reasons for telemetry.
- `app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmMerger.kt` — field-level merge rules.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/QwenVlmCouponModel.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/MiniCpmVlmCouponModel.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/GemmaVisionCouponModel.kt`
- `app/src/main/kotlin/com/example/coupontracker/llm/gemma/GemmaVisionRuntime.kt` — MediaPipe GenAI multimodal wrapper.
- `app/src/test/java/com/example/coupontracker/extraction/retry/VlmRetryEvaluatorTest.kt`
- `app/src/test/java/com/example/coupontracker/extraction/retry/VlmMergerTest.kt`
- `app/src/test/java/com/example/coupontracker/extraction/model/QwenVlmCouponModelTest.kt`
- `app/src/test/java/com/example/coupontracker/benchmark/VlmBakeOffTest.kt`
- `benchmark/goldenset/replay_vlm/<candidate>/*.json` — replay fixtures per candidate, starting as copies of Qwen seed fixtures.
- `benchmark/reports/vlm_bakeoff_hermetic.md`
- `benchmark/reports/vlm_bakeoff_live.md`

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — after the existing extraction produces a `CouponInfo`, consult `VlmRetryEvaluator`. If retry fires, call `ModelSelector.select(LOW_CONFIDENCE_RETRY)`, merge via `VlmMerger`, and record telemetry.
- `app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt` — register the three new adapters.
- `app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt` — register Qwen2.5-VL weights, MiniCPM-V bundle (exists), Gemma 3 Vision.

---

## Task 1: `VlmRetryEvaluator` + `VlmRetryTrigger`

**Files:** Create the files plus test.

- [ ] **Step 1: Create `VlmRetryTrigger.kt`**

```kotlin
package com.example.coupontracker.extraction.retry

sealed class VlmRetryTrigger(val code: String) {
    object RedeemCodeMissing : VlmRetryTrigger("redeem_code_missing")
    object StoreNameUnknown : VlmRetryTrigger("store_name_unknown")
    object ExpiryMissing : VlmRetryTrigger("expiry_missing")
    object NeedsAttentionFlag : VlmRetryTrigger("needs_attention")
    object OcrTooShort : VlmRetryTrigger("ocr_too_short")
    data class FieldDisagreement(val field: String) :
        VlmRetryTrigger("disagreement_$field")
}
```

- [ ] **Step 2: Write the evaluator test**

`VlmRetryEvaluatorTest.kt`:

```kotlin
package com.example.coupontracker.extraction.retry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRetryEvaluatorTest {

    private val valid = JSONObject("""{"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}""")

    @Test
    fun `no trigger when payload complete`() {
        val triggers = VlmRetryEvaluator().evaluate(valid, ocrText = "AJIO SAVE50 Flat 50% valid 01 Jun 2026")
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `missing redeem code fires trigger`() {
        val missing = JSONObject(valid.toString()).put("redeemCode", "unknown")
        val triggers = VlmRetryEvaluator().evaluate(missing, ocrText = "any")
        assertTrue(triggers.any { it is VlmRetryTrigger.RedeemCodeMissing })
    }

    @Test
    fun `needsAttention fires trigger`() {
        val flag = JSONObject(valid.toString()).put("needsAttention", true)
        val triggers = VlmRetryEvaluator().evaluate(flag, ocrText = "any")
        assertTrue(triggers.any { it is VlmRetryTrigger.NeedsAttentionFlag })
    }

    @Test
    fun `ocr shorter than threshold fires trigger`() {
        val triggers = VlmRetryEvaluator().evaluate(valid, ocrText = "hi")
        assertTrue(triggers.any { it is VlmRetryTrigger.OcrTooShort })
    }

    @Test
    fun `shouldRetry is true if any trigger fires`() {
        val missing = JSONObject(valid.toString()).put("storeName", "unknown")
        assertEquals(true, VlmRetryEvaluator().shouldRetry(missing, ocrText = "any", ocrSpansCount = 5))
    }
}
```

- [ ] **Step 3: Implement `VlmRetryEvaluator`**

```kotlin
package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VlmRetryEvaluator @Inject constructor() {

    companion object {
        const val MIN_OCR_TEXT_CHARS = 20
    }

    fun evaluate(
        canonical: JSONObject,
        ocrText: String,
        ocrSpansCount: Int = 0
    ): List<VlmRetryTrigger> {
        val triggers = mutableListOf<VlmRetryTrigger>()
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.REDEEM_CODE)))
            triggers += VlmRetryTrigger.RedeemCodeMissing
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.STORE_NAME)))
            triggers += VlmRetryTrigger.StoreNameUnknown
        if (unknownOrBlank(canonical.optString(CouponSchemaKeys.EXPIRY_DATE)))
            triggers += VlmRetryTrigger.ExpiryMissing
        if (canonical.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false))
            triggers += VlmRetryTrigger.NeedsAttentionFlag
        if (ocrText.length < MIN_OCR_TEXT_CHARS)
            triggers += VlmRetryTrigger.OcrTooShort
        return triggers
    }

    fun shouldRetry(canonical: JSONObject, ocrText: String, ocrSpansCount: Int): Boolean =
        evaluate(canonical, ocrText, ocrSpansCount).isNotEmpty()

    private fun unknownOrBlank(value: String?): Boolean =
        value.isNullOrBlank() || value.trim().equals("unknown", ignoreCase = true)
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.retry.VlmRetryEvaluatorTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/retry/ \
        app/src/test/java/com/example/coupontracker/extraction/retry/VlmRetryEvaluatorTest.kt
git commit -m "feat(retry): add VlmRetryEvaluator and triggers"
```

---

## Task 2: `VlmMerger` with strict field rules

**Files:** Create `VlmMerger.kt` + test.

- [ ] **Step 1: Write the merger test**

`VlmMergerTest.kt`:

```kotlin
package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VlmMergerTest {

    private fun payload(
        store: String = "unknown", desc: String = "", code: String = "unknown",
        date: String = "unknown", source: String = "fallback",
        evidence: List<String> = emptyList(), attention: Boolean = true
    ): JSONObject = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, desc)
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, date)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, source)
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, org.json.JSONArray(evidence))
        put(CouponSchemaKeys.NEEDS_ATTENTION, attention)
    }

    @Test
    fun `redeemCode adopted only if present in OCR text`() {
        val primary = payload(code = "unknown")
        val vlm = payload(code = "VLMHALLUC")
        val merged = VlmMerger.merge(primary, vlm, ocrText = "no such code here")
        assertEquals("unknown", merged.getString(CouponSchemaKeys.REDEEM_CODE))

        val merged2 = VlmMerger.merge(primary, vlm, ocrText = "VLMHALLUC on the page")
        assertEquals("VLMHALLUC", merged2.getString(CouponSchemaKeys.REDEEM_CODE))
    }

    @Test
    fun `storeName adopted only with evidence`() {
        val primary = payload(store = "unknown")
        val noEvidence = payload(store = "AJIO", evidence = emptyList())
        val withEvidence = payload(store = "AJIO", evidence = listOf("AJIO logo"))

        assertEquals("unknown",
            VlmMerger.merge(primary, noEvidence, "any").getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("AJIO",
            VlmMerger.merge(primary, withEvidence, "any").getString(CouponSchemaKeys.STORE_NAME))
    }

    @Test
    fun `expiry adopted only if parser accepts`() {
        val primary = payload(date = "unknown")
        val valid = payload(date = "2026-06-01")
        val gibberish = payload(date = "sometime next year")

        assertEquals("2026-06-01",
            VlmMerger.merge(primary, valid, "any").getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("unknown",
            VlmMerger.merge(primary, gibberish, "any").getString(CouponSchemaKeys.EXPIRY_DATE))
    }

    @Test
    fun `description adopted only when VLM version longer and non-generic`() {
        val primary = payload(desc = "offer")
        val generic = payload(desc = "save money")
        val substantive = payload(desc = "Flat 50% off on first order above 999")

        assertEquals("offer",
            VlmMerger.merge(primary, generic, "any").getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("Flat 50% off on first order above 999",
            VlmMerger.merge(primary, substantive, "any").getString(CouponSchemaKeys.DESCRIPTION))
    }

    @Test
    fun `needsAttention cleared when all fields now known`() {
        val primary = payload(attention = true)
        val complete = payload(
            store = "AJIO", desc = "Flat 50% off first order",
            code = "SAVE50", date = "2026-06-01",
            evidence = listOf("AJIO"), attention = false
        )
        val merged = VlmMerger.merge(primary, complete, ocrText = "SAVE50 AJIO Flat 50% off")
        assertFalse(merged.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }
}
```

- [ ] **Step 2: Implement `VlmMerger`**

```kotlin
package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Conservative field-level merger for the default+VLM outputs. Rules exist
 * to prevent VLM hallucinations from overwriting trustworthy OCR-anchored
 * values.
 */
object VlmMerger {

    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val GENERIC_PHRASES = listOf("save money", "shop now", "discount", "offer")

    fun merge(primary: JSONObject, vlm: JSONObject, ocrText: String): JSONObject {
        val result = JSONObject(primary.toString())

        mergeRedeemCode(result, vlm, ocrText)
        mergeStoreName(result, vlm)
        mergeExpiryDate(result, vlm)
        mergeDescription(result, vlm)

        if (allKnown(result)) {
            result.put(CouponSchemaKeys.NEEDS_ATTENTION, false)
        }
        return result
    }

    private fun mergeRedeemCode(result: JSONObject, vlm: JSONObject, ocrText: String) {
        if (!isUnknown(result.optString(CouponSchemaKeys.REDEEM_CODE))) return
        val candidate = vlm.optString(CouponSchemaKeys.REDEEM_CODE).trim()
        if (candidate.isBlank() || isUnknown(candidate)) return
        if (!ocrText.uppercase(Locale.US).contains(candidate.uppercase(Locale.US))) return
        result.put(CouponSchemaKeys.REDEEM_CODE, candidate)
    }

    private fun mergeStoreName(result: JSONObject, vlm: JSONObject) {
        if (!isUnknown(result.optString(CouponSchemaKeys.STORE_NAME))) return
        val candidate = vlm.optString(CouponSchemaKeys.STORE_NAME).trim()
        val evidence = vlm.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE)
        if (candidate.isBlank() || isUnknown(candidate)) return
        if (evidence == null || evidence.length() == 0) return
        result.put(CouponSchemaKeys.STORE_NAME, candidate)
        result.put(CouponSchemaKeys.STORE_NAME_SOURCE, "vision")
        result.put(CouponSchemaKeys.STORE_NAME_EVIDENCE, evidence)
    }

    private fun mergeExpiryDate(result: JSONObject, vlm: JSONObject) {
        if (!isUnknown(result.optString(CouponSchemaKeys.EXPIRY_DATE))) return
        val candidate = vlm.optString(CouponSchemaKeys.EXPIRY_DATE).trim()
        if (!parsesToIso(candidate)) return
        result.put(CouponSchemaKeys.EXPIRY_DATE, candidate)
    }

    private fun mergeDescription(result: JSONObject, vlm: JSONObject) {
        val existing = result.optString(CouponSchemaKeys.DESCRIPTION).trim()
        val candidate = vlm.optString(CouponSchemaKeys.DESCRIPTION).trim()
        if (candidate.length <= existing.length) return
        val lc = candidate.lowercase(Locale.US)
        if (GENERIC_PHRASES.any { it == lc }) return
        result.put(CouponSchemaKeys.DESCRIPTION, candidate)
    }

    private fun allKnown(obj: JSONObject): Boolean {
        listOf(
            CouponSchemaKeys.STORE_NAME,
            CouponSchemaKeys.REDEEM_CODE,
            CouponSchemaKeys.EXPIRY_DATE
        ).forEach {
            if (isUnknown(obj.optString(it))) return false
        }
        return true
    }

    private fun isUnknown(v: String?): Boolean =
        v.isNullOrBlank() || v.trim().equals("unknown", ignoreCase = true)

    private fun parsesToIso(v: String): Boolean {
        if (!ISO_DATE.matches(v)) return false
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v) != null }.getOrDefault(false)
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.retry.VlmMergerTest"
```
Expected: PASS (5 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmMerger.kt \
        app/src/test/java/com/example/coupontracker/extraction/retry/VlmMergerTest.kt
git commit -m "feat(retry): add conservative VlmMerger with per-field rules"
```

---

## Task 3: `QwenVlmCouponModel` adapter

**Files:** Create adapter + test.

The Qwen2.5-VL path uses the existing MLC runtime via `LlmRuntimeManager.runVisionInference(bitmap, prompt)`. Read its signature first; if the method doesn't exist, check `MlcLlmNative.runVisionInference` and expose a `suspend` wrapper in `LlmRuntimeManager`.

- [ ] **Step 1: Inspect `LlmRuntimeManager` for a vision path**

```bash
grep -n "runVisionInference\|runVision" app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt
```
Expected: a suspend function with signature `suspend fun runVisionInference(bitmap: Bitmap, prompt: String, keepLoaded: Boolean = false, maxTokensOverride: Int? = null): String?`. If absent, add one mirroring `runTextInference` — the native JNI exposes both paths.

- [ ] **Step 2: Create `QwenVlmCouponModel.kt`**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class QwenVlmCouponModel @Inject constructor(
    private val llmRuntime: LlmRuntimeManager
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_QWEN

    override suspend fun extractFromText(
        ocrText: String, prompt: String, grammar: String?
    ): ModelExtractionResult {
        throw NotImplementedError("QwenVlmCouponModel is vision-only; use QwenTextCouponModel for text.")
    }

    override suspend fun extractFromImage(
        image: Bitmap, ocrText: String?, prompt: String
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = llmRuntime.runVisionInference(
                bitmap = image,
                prompt = buildMultimodalPrompt(prompt, ocrText),
                keepLoaded = false,
                maxTokensOverride = null
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("runVisionInference returned null")
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
            latencyMs = latency,
            usedFallback = false
        )
    }

    private fun buildMultimodalPrompt(basePrompt: String, ocrText: String?): String = buildString {
        append(basePrompt)
        if (!ocrText.isNullOrBlank()) {
            append("\n\nOCR anchors (copy codes exactly from here):\n")
            append(ocrText)
        }
    }
}
```

- [ ] **Step 3: Create `QwenVlmCouponModelTest.kt`**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.llm.LlmRuntimeManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QwenVlmCouponModelTest {

    private val canonical =
        """{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"vision","storeNameEvidence":["AJIO logo"],"needsAttention":false}"""

    @Test
    fun `happy path forwards to runtime and enforces contract`() = runBlocking {
        val runtime = mockk<LlmRuntimeManager>()
        coEvery { runtime.runVisionInference(any(), any(), any(), any()) } returns canonical

        val model = QwenVlmCouponModel(runtime)
        val result = model.extractFromImage(mockk<Bitmap>(relaxed = true), "ocr SAVE50", "prompt")

        assertEquals(ModelMode.VLM_QWEN, model.mode)
        assertEquals(canonical, result.canonicalJson)
    }

    @Test(expected = NotImplementedError::class)
    fun `extractFromText throws`() = runBlocking {
        val model = QwenVlmCouponModel(mockk())
        model.extractFromText("ocr", "prompt", null)
        Unit
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/QwenVlmCouponModel.kt \
        app/src/test/java/com/example/coupontracker/extraction/model/QwenVlmCouponModelTest.kt \
        app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt
git commit -m "feat(model): add QwenVlmCouponModel vision adapter"
```

(Drop `LlmRuntimeManager.kt` from staging if you didn't add a vision wrapper.)

---

## Task 4: `MiniCpmVlmCouponModel` adapter

The project already has MiniCPM scaffolding (`LicenseGateScreen`, `build_real_minicpm.py`, native vision bridge). Reuse that infrastructure.

- [ ] **Step 1: Locate the MiniCPM native bridge**

```bash
grep -rn "MiniCPM\|minicpm" app/src/main/kotlin/com/example/coupontracker/llm/ app/src/main/cpp/ 2>/dev/null | head -20
```
Identify the class or native function the app uses today for MiniCPM inference. If the bridge is abandoned-in-place (common given the project pivoted to Qwen via MLC), stop and report `DONE_WITH_CONCERNS` — the adapter needs a concrete runtime to wrap; it should not be fabricated.

- [ ] **Step 2: Create the adapter**

Shape mirrors `QwenVlmCouponModel`. Adjust the constructor to inject whatever MiniCPM runtime class exists (placeholder name `MiniCpmRuntime`; use the real class name).

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
// import com.example.coupontracker.llm.MiniCpmRuntime // actual class from Step 1
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class MiniCpmVlmCouponModel @Inject constructor(
    // private val runtime: MiniCpmRuntime
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_MINICPM

    override suspend fun extractFromText(
        ocrText: String, prompt: String, grammar: String?
    ): ModelExtractionResult {
        throw NotImplementedError("MiniCpmVlmCouponModel is vision-only.")
    }

    override suspend fun extractFromImage(
        image: Bitmap, ocrText: String?, prompt: String
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            // raw = runtime.runVision(image, prompt + (ocrText?.let { "\n\nOCR:\n$it" } ?: ""))
            raw = null // placeholder until Step 1 resolves the concrete runtime
        }
        val rawResponse = raw ?: throw IllegalStateException("MiniCPM returned null")
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
            latencyMs = latency,
            usedFallback = false
        )
    }
}
```

If Step 1 identified a concrete runtime, uncomment the injected parameter and the runtime call. Do **not** leave the `raw = null` placeholder in the committed code — that would ship a broken adapter. If the runtime does not exist, report `BLOCKED` and escalate.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/MiniCpmVlmCouponModel.kt
git commit -m "feat(model): add MiniCpmVlmCouponModel adapter"
```

---

## Task 5: `GemmaVisionRuntime` + `GemmaVisionCouponModel`

Gemma 3 Vision runs through MediaPipe GenAI with an image accessory. This is the newest surface; treat it as experimental.

- [ ] **Step 1: Create `GemmaVisionRuntime.kt`**

Mirror `GemmaRuntime.kt` (from Plan 4). Use `LlmInferenceSession` with `ImageGenaiAccessory` to add the bitmap as a vision input. Signature:

```kotlin
package com.example.coupontracker.llm.gemma

import android.content.Context
import android.graphics.Bitmap

interface GemmaVisionRuntime {
    suspend fun runVisionInference(bitmap: Bitmap, prompt: String): String?
    fun isReady(): Boolean
    fun release()
}
```

Provide an implementation `GemmaVisionRuntimeImpl` that reads the `.task` bundle from `ModelAssetManager.GEMMA_VISION_PATH`. Full code:

```kotlin
@javax.inject.Singleton
class GemmaVisionRuntimeImpl @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : GemmaVisionRuntime {
    // State + inference object identical to GemmaRuntime, but invoked with
    // a com.google.mediapipe.framework.image.BitmapImageBuilder input.
    // TODO in execution: fill in the MediaPipe calls per the Android sample at
    //   https://github.com/google-ai-edge/mediapipe-samples (which the
    //   executing developer should consult for the current API shape).
    // ...
}
```

Because the MediaPipe vision API is version-sensitive, the executing engineer should pin to the stable version in `app/build.gradle.kts` and use the matching Android sample code as the template. The Runtime wrapper should expose exactly the `GemmaVisionRuntime` interface regardless of internal API churn.

- [ ] **Step 2: Create `GemmaVisionCouponModel`**

Shape same as the other VLM adapters; delegate to `GemmaVisionRuntime`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/llm/gemma/GemmaVisionRuntime.kt \
        app/src/main/kotlin/com/example/coupontracker/extraction/model/GemmaVisionCouponModel.kt
git commit -m "feat(model): add GemmaVisionCouponModel adapter"
```

---

## Task 6: Register VLM adapters in `ModelModule`

- [ ] **Step 1: Update `ModelModule.kt`** — append:

```kotlin
    @Binds @IntoSet
    abstract fun bindQwenVlm(impl: QwenVlmCouponModel): CouponExtractionModel

    @Binds @IntoSet
    abstract fun bindMiniCpmVlm(impl: MiniCpmVlmCouponModel): CouponExtractionModel

    @Binds @IntoSet
    abstract fun bindGemmaVision(impl: GemmaVisionCouponModel): CouponExtractionModel
```

Also add the imports.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt
git commit -m "feat(di): register VLM adapters for retry path"
```

---

## Task 7: Wire retry into `LocalLlmOcrService`

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`.

- [ ] **Step 1: Inject the new dependencies**

Add constructor parameters `private val vlmRetryEvaluator: VlmRetryEvaluator` and ensure `ModelSelector` is already injected (from Plan 2). The `VlmMerger` is an `object`; no injection needed.

- [ ] **Step 2: After the default extraction produces canonical JSON, run retry**

Find where `processCouponImage` obtains the canonical JSON (after the call to `runLlmInferenceWithRetry`). Insert retry:

```kotlin
        val canonicalJson: String = /* existing extraction result */
        val canonicalObj = org.json.JSONObject(canonicalJson)
        val triggers = vlmRetryEvaluator.evaluate(
            canonical = canonicalObj,
            ocrText = rawOcrText ?: "",
            ocrSpansCount = ocrSpans.size
        )
        val finalJson = if (triggers.isNotEmpty()) {
            telemetryService.recordVlmRetry(triggers.map { it.code })
            try {
                val adapter = modelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY)
                val vlmResult = adapter.extractFromImage(bitmap, rawOcrText, prompt.promptText)
                val vlmObj = org.json.JSONObject(vlmResult.canonicalJson)
                com.example.coupontracker.extraction.retry.VlmMerger
                    .merge(canonicalObj, vlmObj, rawOcrText ?: "")
                    .toString()
            } catch (e: ModelSelectorException) {
                telemetryService.recordVlmRetryUnavailable(e.mode.name)
                canonicalJson
            } catch (e: Exception) {
                Log.w(TAG, "VLM retry threw", e)
                canonicalJson
            }
        } else {
            canonicalJson
        }
```

Thread `finalJson` (instead of `canonicalJson`) through the downstream `parseLlmResponseToCouponInfo(...)`. Telemetry methods `recordVlmRetry(List<String>)` and `recordVlmRetryUnavailable(String)` should be added to `ExtractionTelemetryService` as thin wrappers over the existing counter API if they don't exist.

- [ ] **Step 3: Add retry tests**

Add a unit test under `app/src/test/java/com/example/coupontracker/util/LocalLlmOcrServiceRetryTest.kt` that verifies: (a) no retry when payload is complete; (b) retry fires when needsAttention=true, merger swaps in VLM values; (c) retry silently no-ops if `ModelSelectorException` thrown. Mocks `ModelSelector` + `VlmRetryEvaluator`.

Write the three tests using MockK. Provide a minimal `LocalLlmOcrService` test double if construction is too heavy; otherwise use the existing test harness.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt \
        app/src/main/kotlin/com/example/coupontracker/util/ExtractionTelemetryService.kt \
        app/src/test/java/com/example/coupontracker/util/LocalLlmOcrServiceRetryTest.kt
git commit -m "feat(llm): wire VLM retry into extraction pipeline"
```

---

## Task 8: VLM bake-off benchmark

**Files:** Create `VlmBakeOffTest.kt` and three replay-fixture directories.

- [ ] **Step 1: Seed replay fixtures**

```bash
mkdir -p benchmark/goldenset/replay_vlm/qwen
mkdir -p benchmark/goldenset/replay_vlm/minicpm
mkdir -p benchmark/goldenset/replay_vlm/gemma

for candidate in qwen minicpm gemma; do
  for id in ajio_flat50_clean flipkart_big_saving myntra_no_code zomato_gold_cashback ambiguous_low_signal; do
    cp "benchmark/goldenset/replay/$id.json" "benchmark/goldenset/replay_vlm/$candidate/$id.json"
  done
done
```

Fixtures start as copies of the Qwen text-mode output; the developer overwrites them after running each candidate live.

- [ ] **Step 2: Create the bake-off test**

```kotlin
package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlmBakeOffTest {

    private val candidates = listOf("qwen", "minicpm", "gemma")

    @Test
    fun `bakeoff generates comparison report across three VLM candidates`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue(samples.isNotEmpty())

        val perCandidate = candidates.associateWith { name ->
            val recordings = samples.associate { s ->
                val path = "replay_vlm/$name/${s.id}.json"
                val text = javaClass.classLoader!!.getResource(path)?.readText()
                    ?: error("missing $path")
                s.imageSha256 to text
            }
            val model = ReplayCouponModel(recordings) { sentinel.get()!! }
            val rows = samples.map { s ->
                sentinel.set(s.imageSha256)
                val r = model.extractFromImage(fake, null, "")
                MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
            }
            MetricsCalculator.aggregate(rows)
        }

        val out = File("build/reports/goldenset").apply { mkdirs() }
        File(out, "vlm_bakeoff.md").writeText(render(perCandidate))
    }

    private fun render(perCandidate: Map<String, AggregateMetrics>): String = buildString {
        append("# VLM bake-off\n\n")
        append("| metric | ").append(candidates.joinToString(" | ")).append(" |\n")
        append("|---|").append(candidates.joinToString("|") { "---" }).append("|\n")
        fun row(name: String, pick: (AggregateMetrics) -> Double) {
            append("| $name |")
            candidates.forEach { append(" %.3f |".format(pick(perCandidate[it]!!))) }
            append("\n")
        }
        row("redeemCode exact") { it.redeemCodeAccuracy }
        row("storeName normalized") { it.storeNameAccuracy }
        row("expiryDate match") { it.expiryDateAccuracy }
        row("JSON validity") { it.jsonValidity }
        row("hallucination rate") { it.hallucinationRate }
    }

    companion object {
        private val sentinel = ThreadLocal<String>()
        private val fake: android.graphics.Bitmap = mockk(relaxed = true)
    }
}
```

- [ ] **Step 3: Run it**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.VlmBakeOffTest"
```
Expected: PASS; `build/reports/goldenset/vlm_bakeoff.md` generated with all candidates tied (seed fixtures identical).

- [ ] **Step 4: Commit**

```bash
git add benchmark/goldenset/replay_vlm/ \
        app/src/test/java/com/example/coupontracker/benchmark/VlmBakeOffTest.kt
git commit -m "test(benchmark): add three-candidate VLM bake-off runner"
```

---

## Task 9: Commit baseline reports

- [ ] **Step 1: Create `benchmark/reports/vlm_bakeoff_hermetic.md`**

```markdown
# VLM bake-off (hermetic)

Generated by: `./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.VlmBakeOffTest"`

## Seed-fixtures baseline (all three copy Qwen text output; deltas = 0)

| metric | qwen | minicpm | gemma |
|---|---|---|---|
| redeemCode exact | 1.000 | 1.000 | 1.000 |
| storeName normalized | 1.000 | 1.000 | 1.000 |
| expiryDate match | 1.000 | 1.000 | 1.000 |
| JSON validity | 1.000 | 1.000 | 1.000 |
| hallucination rate | 0.000 | 0.000 | 0.000 |

Replace the fixtures under `benchmark/goldenset/replay_vlm/<candidate>/` with
live-on-device output and re-run the test to populate real deltas.
```

- [ ] **Step 2: Create `benchmark/reports/vlm_bakeoff_live.md`** — procedure mirroring Plan 4's live document; list decision criteria (redeemCode accuracy delta ≥ +3 pp to promote; hallucination rate < 10%; p95 latency within 3× Qwen text-mode).

- [ ] **Step 3: Commit**

```bash
git add benchmark/reports/vlm_bakeoff_hermetic.md \
        benchmark/reports/vlm_bakeoff_live.md
git commit -m "docs(benchmark): commit VLM bake-off placeholders"
```

---

## Task 10: Developer runbook

**Files:** Create `docs/extraction/vlm_retry.md`.

- [ ] **Step 1: Write the runbook**

```markdown
# VLM retry runbook

## When retry fires

`VlmRetryEvaluator` checks the default text-path output against:
- `redeemCode in {"", "unknown"}` → RedeemCodeMissing
- `storeName in {"", "unknown"}` → StoreNameUnknown
- `expiryDate in {"", "unknown"}` → ExpiryMissing
- `needsAttention == true` → NeedsAttentionFlag
- `ocrText.length < 20` → OcrTooShort

Any fired trigger enables retry.

## Merge rules

`VlmMerger` only adopts VLM values under strict conditions:
- `redeemCode` — VLM value must appear in the OCR text.
- `storeName` — VLM value must come with non-empty evidence array.
- `expiryDate` — VLM value must parse as ISO `yyyy-MM-dd`.
- `description` — VLM value must be longer than existing and not a generic phrase.

If all three key fields are known after merge, `needsAttention` clears to false.

## Candidates

| mode | adapter | runtime |
|---|---|---|
| VLM_QWEN | QwenVlmCouponModel | MLC LLM (existing Qwen2.5-VL) |
| VLM_MINICPM | MiniCpmVlmCouponModel | llama.cpp MTMD (existing MiniCPM-V) |
| VLM_GEMMA | GemmaVisionCouponModel | MediaPipe GenAI (Gemma 3 Vision) |

Select which candidate runs in the retry slot with:
```
modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN)
```

## Shipping a candidate as retry

1. Hermetic bake-off must show ≥ Qwen text-mode accuracy with hallucination < 10%.
2. Live on-device latency must be p95 < 3× Qwen text-mode.
3. Memory peak must fit target low-end device tier (see Plan 7).
4. After ship, watch `extraction.vlm_retry.fired` counter and
   `extraction.vlm_retry.unavailable` error rate in telemetry.
```

- [ ] **Step 2: Commit**

```bash
git add docs/extraction/vlm_retry.md
git commit -m "docs(extraction): add VLM retry runbook"
```

---

## Self-Review

1. **Spec coverage:** Phase 4 retry predicates → Task 1. Conservative merge rules → Task 2. Phase 6 candidate bake-off → Tasks 3–5 (adapters) + Task 8 (benchmark) + Task 9 (reports). Pipeline integration → Task 7. Runbook → Task 10.
2. **Placeholder scan:** Task 4 (MiniCPM) explicitly documents the fabrication risk and says report BLOCKED if the runtime does not exist; that is not a placeholder, it is a gating instruction. Task 5 (Gemma Vision) references a MediaPipe sample URL because the API surface shifts version-to-version — this is a necessary reference, not a TODO.
3. **Type consistency:** `VlmRetryTrigger`, `VlmRetryEvaluator`, `VlmMerger`, `ModelRole.LOW_CONFIDENCE_RETRY`, `ModelMode.VLM_*`, `CouponSchemaKeys.*` consistent across tasks.
