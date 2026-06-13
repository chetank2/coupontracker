# VLM Retry Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the already-built `VlmRetryRunner` into `LocalLlmOcrService.processCouponImageTyped` so the production extraction path consults the VLM retry slot when the default text path flags low confidence.

**Architecture:** `VlmRetryRunner` (added in commit `182a60fd`) is a `@Singleton` that takes canonical JSON + bitmap + ocr text + prompt and returns (possibly merged) JSON. The runner is fail-safe: any internal failure (unconfigured retry slot, VLM exception, malformed VLM JSON) returns the input JSON unchanged. Adoption requires (a) a `CouponInfo.toCanonicalJsonString()` helper to round-trip the parsed model back to the JSON shape the runner expects, (b) injection of the runner into `LocalLlmOcrService`, and (c) a single retry call in `processCouponImageTyped` after `parseWithOptionalRetry` returns its `ParsedLlmResult`. The merged JSON is re-parsed via the existing `parseLlmResponseToCouponInfo` so all schema enforcement and validation runs again on the merged payload.

**Tech Stack:** Kotlin 1.9, Hilt 2.50, JUnit 4, MockK 1.13.9, org.json.

---

## Pre-flight

- Branch: `feature/qwen-multi-coupon-extraction`. HEAD: `74bdf006`.
- Pieces already shipped:
  - `VlmRetryRunner` at `app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmRetryRunner.kt`.
  - `VlmRetryEvaluator`, `VlmRetryTrigger`, `VlmMerger`.
  - Adapters: `QwenVlmCouponModel`, `MiniCpmVlmCouponModel`, `GemmaVisionCouponModel` — all DI-registered.
  - `ModelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY)` already resolves per `ModelStrategyConfig`.
  - `DeviceTierPolicy.apply(...)` already wired in `CouponTrackerApplication.onCreate` — sets the retry mode based on capability.
- The runbook at `docs/extraction/vlm_retry.md` contains the integration sketch.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/retry/CouponInfoCanonical.kt` — extension function `CouponInfo.toCanonicalJsonString(): String` that emits the seven canonical v1 keys. Lives next to the runner so the round-trip helper is co-located with its consumer.
- `app/src/test/java/com/example/coupontracker/extraction/retry/CouponInfoCanonicalTest.kt` — covers the date formatting, null-handling, and unknown-defaults rules.

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — add `injectedVlmRetryRunner: VlmRetryRunner? = null` constructor parameter (default null preserves the 2-arg `ImageProcessor.kt` and test constructions). After `parseWithOptionalRetry` in `processCouponImageTyped`, invoke the runner and re-parse if a merge happened. Also touches the constructor companion accessor for tests.
- `app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt` — add the runner to the `provideLocalLlmOcrService` provider parameter list and pass it through.

### Files to leave alone

- `VlmRetryRunner` itself — already complete.
- `processCouponImage` (the legacy entry at line ~1126) — different code path; not in scope.
- Any other VM that constructs `LocalLlmOcrService` — the new param has a default.

---

## Task 1: Add `CouponInfo.toCanonicalJsonString()` extension

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/retry/CouponInfoCanonical.kt`

- [ ] **Step 1: Create the extension file**

```kotlin
package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.util.CouponInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Round-trip helper: emit a CouponInfo as the canonical seven-key v1 JSON
 * payload that `VlmRetryRunner` (and `CouponJsonContract`) expects.
 *
 * Rules — match `enforceCanonicalFields` and parser behaviour:
 * - Empty/null string fields become the literal "unknown".
 * - `expiryDate` (Date?) is formatted as `yyyy-MM-dd`, or "unknown" if null.
 * - `storeNameSource` falls back to "unknown" when null/blank.
 * - `storeNameEvidence` is always emitted as a JSON array (possibly empty).
 * - `needsAttention` defaults to its model field; the runner may flip it.
 *
 * Used by the production extraction path to feed the runner without
 * dragging in the LLM response string (which may have been modified by
 * sanitisation since first parse).
 */
fun CouponInfo.toCanonicalJsonString(): String {
    val obj = JSONObject()
    obj.put(CouponSchemaKeys.STORE_NAME, storeName.ifBlank { "unknown" })
    obj.put(CouponSchemaKeys.DESCRIPTION, description.ifBlank { "unknown" })
    obj.put(CouponSchemaKeys.REDEEM_CODE,
        redeemCode?.takeIf { it.isNotBlank() } ?: "unknown")
    obj.put(CouponSchemaKeys.EXPIRY_DATE,
        expiryDate?.let { ISO_DATE_FORMAT.format(it) } ?: "unknown")
    obj.put(CouponSchemaKeys.STORE_NAME_SOURCE,
        storeNameSource?.takeIf { it.isNotBlank() } ?: "unknown")
    obj.put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray(storeNameEvidence))
    obj.put(CouponSchemaKeys.NEEDS_ATTENTION, needsAttention)
    return obj.toString()
}

private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/retry/CouponInfoCanonical.kt
git commit -m "feat(retry): add CouponInfo.toCanonicalJsonString round-trip helper"
```

---

## Task 2: Test the extension

**Files:**
- Create: `app/src/test/java/com/example/coupontracker/extraction/retry/CouponInfoCanonicalTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.example.coupontracker.extraction.retry

import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.util.CouponInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CouponInfoCanonicalTest {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `populated CouponInfo emits all seven canonical keys`() {
        val date = isoFmt.parse("2026-06-01")!!
        val info = CouponInfo(
            storeName = "AJIO",
            description = "Flat 50% off",
            redeemCode = "SAVE50",
            expiryDate = date,
            storeNameSource = "ocr",
            storeNameEvidence = listOf("AJIO"),
            needsAttention = false
        )

        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("AJIO", obj.getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("Flat 50% off", obj.getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("SAVE50", obj.getString(CouponSchemaKeys.REDEEM_CODE))
        assertEquals("2026-06-01", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("ocr", obj.getString(CouponSchemaKeys.STORE_NAME_SOURCE))
        assertEquals(1, obj.getJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE).length())
        assertFalse(obj.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }

    @Test
    fun `blank fields become literal unknown`() {
        val info = CouponInfo(
            storeName = "",
            description = "",
            redeemCode = "",
            expiryDate = null,
            storeNameSource = null,
            storeNameEvidence = emptyList(),
            needsAttention = true
        )

        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("unknown", obj.getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.DESCRIPTION))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.REDEEM_CODE))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
        assertEquals("unknown", obj.getString(CouponSchemaKeys.STORE_NAME_SOURCE))
        assertEquals(0, obj.getJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE).length())
        assertTrue(obj.getBoolean(CouponSchemaKeys.NEEDS_ATTENTION))
    }

    @Test
    fun `null redeemCode becomes unknown`() {
        val info = CouponInfo(storeName = "AJIO", redeemCode = null)
        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("unknown", obj.getString(CouponSchemaKeys.REDEEM_CODE))
    }

    @Test
    fun `expiryDate Date is formatted yyyy-MM-dd`() {
        val date = isoFmt.parse("2027-01-15")!!
        val info = CouponInfo(storeName = "X", expiryDate = date)
        val obj = JSONObject(info.toCanonicalJsonString())
        assertEquals("2027-01-15", obj.getString(CouponSchemaKeys.EXPIRY_DATE))
    }

    @Test
    fun `output contains exactly the seven canonical keys`() {
        val info = CouponInfo(storeName = "X")
        val obj = JSONObject(info.toCanonicalJsonString())
        val keys = obj.keys().asSequence().toSet()
        assertEquals(CouponSchemaKeys.ALLOWED_SET, keys)
    }
}
```

- [ ] **Step 2: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.retry.CouponInfoCanonicalTest"
```
Expected: PASS (5 tests). If Java is unavailable locally, skip and rely on Task 6's gate.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/extraction/retry/CouponInfoCanonicalTest.kt
git commit -m "test(retry): cover CouponInfo.toCanonicalJsonString contract"
```

---

## Task 3: Inject `VlmRetryRunner` into `LocalLlmOcrService`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt:59-68` (constructor)

- [ ] **Step 1: Add the constructor parameter**

Find the existing constructor (around line 59):

```kotlin
class LocalLlmOcrService(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null,
    private val injectedTelemetryService: LlmTelemetryService? = null,
    private val customOcrTextProvider: (suspend (Bitmap) -> String?)? = null,
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder? = null,
    private val injectedPromptBuilder: PromptBuilder? = null,
    private val injectedTelemetryClient: TelemetryClient? = null,
    private val injectedModelSelector: com.example.coupontracker.extraction.model.ModelSelector? = null
) {
```

Change to:

```kotlin
class LocalLlmOcrService(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null,
    private val injectedTelemetryService: LlmTelemetryService? = null,
    private val customOcrTextProvider: (suspend (Bitmap) -> String?)? = null,
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder? = null,
    private val injectedPromptBuilder: PromptBuilder? = null,
    private val injectedTelemetryClient: TelemetryClient? = null,
    private val injectedModelSelector: com.example.coupontracker.extraction.model.ModelSelector? = null,
    private val injectedVlmRetryRunner: com.example.coupontracker.extraction.retry.VlmRetryRunner? = null
) {
```

The new parameter is **last** so positional 2-arg constructions in `ImageProcessor.kt:40` and `SystemVerificationTest.kt:36` keep compiling.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "refactor(llm): inject optional VlmRetryRunner into LocalLlmOcrService"
```

---

## Task 4: Update `LlmModule` to provide the runner

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt`

- [ ] **Step 1: Update the provider**

Find the existing `provideLocalLlmOcrService` provider (around line 65). It currently looks like:

```kotlin
    @Provides
    @Singleton
    fun provideLocalLlmOcrService(
        @ApplicationContext context: Context,
        ocrEngine: com.example.coupontracker.ocr.OcrEngine,
        llmRuntimeManager: LlmRuntimeManager,
        validatorFeedbackRecorder: ValidatorFeedbackRecorder,
        telemetryClient: TelemetryClient,
        modelSelector: com.example.coupontracker.extraction.model.ModelSelector
    ): LocalLlmOcrService {
        return LocalLlmOcrService(
            context = context,
            ocrEngine = ocrEngine,
            injectedLlmRuntimeManager = llmRuntimeManager,
            validatorFeedbackRecorder = validatorFeedbackRecorder,
            injectedTelemetryClient = telemetryClient,
            injectedModelSelector = modelSelector
        )
    }
```

Change to:

```kotlin
    @Provides
    @Singleton
    fun provideLocalLlmOcrService(
        @ApplicationContext context: Context,
        ocrEngine: com.example.coupontracker.ocr.OcrEngine,
        llmRuntimeManager: LlmRuntimeManager,
        validatorFeedbackRecorder: ValidatorFeedbackRecorder,
        telemetryClient: TelemetryClient,
        modelSelector: com.example.coupontracker.extraction.model.ModelSelector,
        vlmRetryRunner: com.example.coupontracker.extraction.retry.VlmRetryRunner
    ): LocalLlmOcrService {
        return LocalLlmOcrService(
            context = context,
            ocrEngine = ocrEngine,
            injectedLlmRuntimeManager = llmRuntimeManager,
            validatorFeedbackRecorder = validatorFeedbackRecorder,
            injectedTelemetryClient = telemetryClient,
            injectedModelSelector = modelSelector,
            injectedVlmRetryRunner = vlmRetryRunner
        )
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt
git commit -m "refactor(di): provide VlmRetryRunner to LocalLlmOcrService"
```

---

## Task 5: Invoke the runner inside `processCouponImageTyped`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — insert after `parseWithOptionalRetry` returns at line ~1224.

- [ ] **Step 1: Inspect the insertion point**

Open the file and re-read lines 1215–1247 to confirm the call site. The relevant block reads:

```kotlin
            val parsedResult = parseWithOptionalRetry(
                initialOutcome = initialOutcome,
                normalizedOcr = normalizedOcr,
                rawOcrText = rawOcrText,
                prompt = promptResult.prompt,
                captureTimestamp = captureTimestamp,
                structuredCandidates = structuredCandidates,
                allowTokenExpansion = true,
                progressCallback = null
            )

            val inferenceElapsed = System.currentTimeMillis() - inferenceStartTime
            memoryUsage = parsedResult.outcome.memoryUsageMb
            Log.d(TAG, "⏱️  Inference completed in ${inferenceElapsed / 1000}s")

            val couponInfo = parsedResult.couponInfo

            if (MockLlmResponseDetector.isMockResponse(couponInfo)) {
```

We insert retry logic between the `val couponInfo = parsedResult.couponInfo` line and the `MockLlmResponseDetector` check, so the merged result still goes through the existing mock-detection and quality-validation gates.

- [ ] **Step 2: Add the retry helper as a private method**

Insert this private method directly above `parseWithOptionalRetry` (around line 2008, before the existing `parseWithOptionalRetry` definition):

```kotlin
    private suspend fun maybeApplyVlmRetry(
        couponInfo: CouponInfo,
        bitmap: Bitmap,
        rawOcrText: String,
        prompt: String,
        captureTimestamp: Date?,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>
    ): CouponInfo {
        val runner = injectedVlmRetryRunner ?: return couponInfo
        val canonicalJson = couponInfo.toCanonicalJsonString()
        val (mergedJson, triggers) = try {
            runner.maybeRetry(canonicalJson, bitmap, rawOcrText, prompt)
        } catch (e: Exception) {
            Log.w(TAG, "VlmRetryRunner threw; preserving default extraction", e)
            return couponInfo
        }
        if (triggers.isEmpty() || mergedJson == canonicalJson) {
            return couponInfo
        }
        Log.i(TAG, "VLM retry produced merged payload; triggers=$triggers")
        return try {
            parseLlmResponseToCouponInfo(
                mergedJson,
                rawOcrText,
                captureTimestamp,
                structuredCandidates
            )
        } catch (e: Exception) {
            Log.w(TAG, "Re-parse of VLM-merged JSON failed; preserving default extraction", e)
            couponInfo
        }
    }
```

This requires the import `import com.example.coupontracker.extraction.retry.toCanonicalJsonString` at the top of the file (next to existing extraction imports).

- [ ] **Step 3: Replace the `couponInfo` assignment with the retry-aware version**

Find this line at ~1230:

```kotlin
            val couponInfo = parsedResult.couponInfo
```

Change to:

```kotlin
            val couponInfo = maybeApplyVlmRetry(
                couponInfo = parsedResult.couponInfo,
                bitmap = bitmap,
                rawOcrText = rawOcrText,
                prompt = promptResult.prompt,
                captureTimestamp = captureTimestamp,
                structuredCandidates = structuredCandidates
            )
```

The downstream code (`MockLlmResponseDetector.isMockResponse`, `validateExtractionQuality`, telemetry) is unchanged — it operates on the returned `couponInfo`, which is either the original or the merged-and-re-parsed one.

- [ ] **Step 4: Sanity-check**

```bash
grep -n "maybeApplyVlmRetry\|toCanonicalJsonString" app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
```
Expected: 3 hits — the function definition, the import, the call site. Plus one more for the import line.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "feat(llm): adopt VlmRetryRunner in processCouponImageTyped"
```

---

## Task 6: End-to-end verification

This task is verification-only — no code changes.

- [ ] **Step 1: `git diff --check`**

```bash
git diff --check
```
Expected: no output (no whitespace errors).

- [ ] **Step 2: Run all extraction-related tests**

```bash
./gradlew :app:testDebugUnitTest \
    --tests "com.example.coupontracker.extraction.retry.*" \
    --tests "com.example.coupontracker.extraction.model.*" \
    --tests "com.example.coupontracker.contract.*" \
    --tests "com.example.coupontracker.util.LocalLlmJsonRepairTest" \
    --tests "com.example.coupontracker.util.LocalLlmOcrServiceCompanionTest" \
    --tests "com.example.coupontracker.benchmark.*"
```
Expected: all green. The retry tests cover the runner; the canonical-helper tests cover the round-trip; the benchmark tests cover the contract.

If Java is unavailable locally, this gate must run on a JRE-equipped machine before merging the branch.

- [ ] **Step 3: Verify no other VM was broken**

```bash
grep -rn "LocalLlmOcrService(" app/src --include='*.kt' | grep -v 'LocalLlmOcrService\.'
```
Expected: 3 hits — `LlmModule.kt:72`, `ImageProcessor.kt:40`, `SystemVerificationTest.kt:36`. Confirm none of them broke (the new constructor parameter has a default).

- [ ] **Step 4: Confirm runtime config controls retry**

```bash
grep -n "LOW_CONFIDENCE_RETRY" app/src/main/kotlin/com/example/coupontracker/runtime/DeviceTierPolicy.kt
```
Expected: at least one hit. This confirms `DeviceTierPolicy` already wires the retry mode at startup. Together with this plan, the chain is now complete:

> `CouponTrackerApplication.onCreate` → `DeviceTierPolicy.apply` → `ModelStrategyConfig.setModeFor(LOW_CONFIDENCE_RETRY, …)` → `processCouponImageTyped` → `maybeApplyVlmRetry` → `VlmRetryRunner` → `ModelSelector.select(LOW_CONFIDENCE_RETRY)` → adapter

- [ ] **Step 5: No commit needed** — verification only.

---

## Self-Review

1. **Spec coverage:**
   - Round-trip helper → Tasks 1–2.
   - Constructor injection → Task 3.
   - DI provider update → Task 4.
   - Production call-site adoption → Task 5.
   - End-to-end gate → Task 6.

2. **Placeholder scan:** None. Every code block is the literal content for the file.

3. **Type consistency:**
   - `CouponInfo.toCanonicalJsonString()` is defined in Task 1 and called in Task 5.
   - `injectedVlmRetryRunner` parameter name is the same in Tasks 3 and 4.
   - `maybeApplyVlmRetry` signature in Task 5 Step 2 matches the call in Task 5 Step 3.
   - `VlmRetryRunner.maybeRetry(canonicalJson, bitmap, ocrText, prompt)` matches the existing runner signature at `app/src/main/kotlin/com/example/coupontracker/extraction/retry/VlmRetryRunner.kt:37`.

4. **Fail-safe verification:**
   - When `injectedVlmRetryRunner == null`: production behaviour unchanged (early return in `maybeApplyVlmRetry`).
   - When the runner throws: caught and original `couponInfo` returned.
   - When the merged JSON re-parse throws: caught and original `couponInfo` returned.
   - When triggers fire but JSON unchanged: original `couponInfo` returned (no useless re-parse).
   - At any of these failure points, downstream `MockLlmResponseDetector`, `validateExtractionQuality`, and telemetry see exactly what they would have seen pre-adoption.
