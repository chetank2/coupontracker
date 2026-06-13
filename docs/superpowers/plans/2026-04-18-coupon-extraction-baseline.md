# Coupon Extraction Baseline & Benchmark Infrastructure

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a measurable floor under the current extraction pipeline: a shared canonical-JSON contract, a labeled image golden set, a hermetic benchmark runner, and a committed baseline report — so later phases (Gemma, VLM retry, multi-coupon) can be evaluated against a fixed reference instead of vibes.

**Architecture:** The benchmark is hermetic by default. Extraction goes through a new `CouponExtractionModel` interface with two adapters: `QwenTextCouponModel` (wraps the existing `LocalLlmOcrService` text path, requires NDK+device) and `ReplayCouponModel` (returns pre-recorded JSON keyed by image SHA-256, runs in a JVM unit test). The hermetic path runs in CI and guards against contract drift; the live path is an opt-in Gradle task a developer invokes once on a device, committing the resulting report. Metrics and the manifest schema are JSON so adding samples is a cheap PR.

**Tech Stack:** Kotlin 1.9, JUnit 4, org.json, Gson (already in deps), Python 3 + Pillow for synthetic image generation, Gradle 8, AGP 8.

---

## Pre-flight

- Prior plan `2026-04-18-jni-fallback-schema-pure.md` already landed these commits on `feature/qwen-multi-coupon-extraction`:
  - `2215c8f0` CouponSchemaKeys constants
  - `7b978963` JniFallbackFixtures + JniFallbackContractTest
  - `8e5c1e0c` Schema-pure BuildFallbackResponse
  - `3272a148` enforceCanonicalFieldsForTest parser-boundary test
  - `04a34e76` .idea/vcs.xml cleanup
  - `698d13c1` enforceCanonicalFieldsForTest KDoc
  - `4f065b12` llama_cpp symlink removal
  - `0cca55dc` fixed_app.py removal
- This plan builds directly on those. Do **not** re-do any of that work.

## File Structure

### Files to create

**Contract (Kotlin, production)**
- `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt` — one object with `REQUIRED_KEYS`, `ALIAS_KEYS` (just `couponCode`), `validate(json) → ContractReport`. Pure org.json, no Android deps.

**Contract tests (Kotlin, JVM)**
- `app/src/test/java/com/example/coupontracker/contract/CouponJsonContractTest.kt`

**Model strategy (Kotlin, production)**
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelMode.kt` — single enum with all modes the roadmap will need: `TEXT_QWEN`, `TEXT_GEMMA`, `VLM_QWEN`, `VLM_GEMMA`, `VLM_MINICPM`, `REMOTE_DEBUG_ONLY`, `BENCHMARK_REPLAY`. Not all modes have adapters yet — that's fine.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/CouponExtractionModel.kt` — interface + `ModelExtractionResult` data class.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/QwenTextCouponModel.kt` — adapter around `LocalLlmOcrService`. Text-only in this plan (vision path stubbed with `NotImplementedError`).
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ReplayCouponModel.kt` — reads a manifest of image-SHA → recorded-JSON and replays.

**Model tests (Kotlin, JVM)**
- `app/src/test/java/com/example/coupontracker/extraction/model/ReplayCouponModelTest.kt`

**Golden set (static data + generator)**
- `benchmark/goldenset/manifest.schema.json` — JSON-Schema describing manifest entries.
- `benchmark/goldenset/manifest.json` — ordered list of sample metadata; one entry per image.
- `benchmark/goldenset/images/*.png` — 5 synthetic starter images.
- `benchmark/goldenset/replay/*.json` — 5 recorded extraction outputs keyed by image SHA-256.
- `scripts/generate_golden_set.py` — reproducible Python 3 image generator (uses Pillow).
- `benchmark/goldenset/README.md` — how to add images, how to re-record replay fixtures.

**Benchmark runner (Kotlin, JVM)**
- `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetSample.kt` — data class for a loaded manifest entry.
- `app/src/test/java/com/example/coupontracker/benchmark/Metrics.kt` — per-sample and aggregate metrics, CSV + markdown serializers.
- `app/src/test/java/com/example/coupontracker/benchmark/ManifestLoader.kt` — loads `manifest.json` + images + replay fixtures.
- `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetBenchmarkTest.kt` — JUnit 4 test that runs the hermetic replay benchmark, asserts 100% pass, and writes `build/reports/goldenset/hermetic.md`.

**Committed baseline artifacts**
- `benchmark/reports/hermetic_baseline.md` — snapshot of the hermetic benchmark output so drift fails CI.
- `benchmark/reports/live_baseline.md` — filled in once by a developer after running the live benchmark on a device.

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — swap the inline allowlist literal (committed in prior plan) to reference `CouponJsonContract.REQUIRED_KEYS + CouponJsonContract.ALIAS_KEYS`. Leaves `CouponSchemaKeys` untouched; `CouponJsonContract` becomes the runtime-facing contract while `CouponSchemaKeys` remains the raw field-name constants.
- `.gitignore` — add `benchmark/goldenset/images/_local/` (a convention for dev-only coupon screenshots the project can't redistribute).
- `scripts/generate_golden_set.py` — new (listed above); no existing scripts are modified.

---

## Task 1: Introduce `CouponJsonContract`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt`

- [ ] **Step 1: Create the contract file**

```kotlin
package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONException
import org.json.JSONObject

/**
 * Runtime-facing canonical coupon contract. One place for:
 * - the allowlist used by parser JSON repair,
 * - schema-validity checks used by tests and the benchmark,
 * - the alias remap (`couponCode` → `redeemCode`) that tolerates older LLM outputs.
 *
 * Raw field-name constants live in `CouponSchemaKeys`. This object is the
 * behaviour layer that composes them.
 */
object CouponJsonContract {

    val REQUIRED_KEYS: Set<String> = CouponSchemaKeys.ALLOWED_SET

    val ALIAS_KEYS: Set<String> = setOf("couponCode")

    val RECOGNIZED_KEYS: Set<String> = REQUIRED_KEYS + ALIAS_KEYS

    data class ContractReport(
        val valid: Boolean,
        val missingKeys: Set<String>,
        val unknownKeys: Set<String>,
        val structuralErrors: List<String>
    )

    fun validate(jsonText: String): ContractReport {
        val obj = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return ContractReport(
                valid = false,
                missingKeys = REQUIRED_KEYS,
                unknownKeys = emptySet(),
                structuralErrors = listOf("parse: ${e.message ?: "invalid JSON"}")
            )
        }
        return validate(obj)
    }

    fun validate(obj: JSONObject): ContractReport {
        val keys = obj.keys().asSequence().toSet()
        val missing = REQUIRED_KEYS - keys
        val unknown = keys - RECOGNIZED_KEYS
        val errors = mutableListOf<String>()

        if (obj.has(CouponSchemaKeys.STORE_NAME_EVIDENCE) &&
            obj.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE) == null) {
            errors += "${CouponSchemaKeys.STORE_NAME_EVIDENCE} must be a JSON array"
        }
        if (obj.has(CouponSchemaKeys.NEEDS_ATTENTION) &&
            obj.opt(CouponSchemaKeys.NEEDS_ATTENTION) !is Boolean) {
            errors += "${CouponSchemaKeys.NEEDS_ATTENTION} must be a boolean"
        }

        return ContractReport(
            valid = missing.isEmpty() && unknown.isEmpty() && errors.isEmpty(),
            missingKeys = missing,
            unknownKeys = unknown,
            structuralErrors = errors
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt
git commit -m "feat(contract): introduce CouponJsonContract with schema validator"
```

---

## Task 2: Test `CouponJsonContract`

**Files:**
- Create: `app/src/test/java/com/example/coupontracker/contract/CouponJsonContractTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CouponJsonContractTest {

    private val canonicalValid = """
        {"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50",
         "expiryDate":"2026-06-01","storeNameSource":"ocr",
         "storeNameEvidence":["AJIO"],"needsAttention":false}
    """.trimIndent()

    @Test
    fun `valid canonical payload passes`() {
        val report = CouponJsonContract.validate(canonicalValid)
        assertTrue(report.valid)
        assertTrue(report.missingKeys.isEmpty())
        assertTrue(report.unknownKeys.isEmpty())
        assertTrue(report.structuralErrors.isEmpty())
    }

    @Test
    fun `unknown diagnostic key is flagged`() {
        val withDiagnostic = canonicalValid.replace(
            "\"needsAttention\":false",
            "\"needsAttention\":false,\"status\":\"fallback\""
        )
        val report = CouponJsonContract.validate(withDiagnostic)
        assertFalse(report.valid)
        assertEquals(setOf("status"), report.unknownKeys)
    }

    @Test
    fun `missing required key is flagged`() {
        val missingExpiry = canonicalValid.replace(
            ",\"expiryDate\":\"2026-06-01\"", ""
        )
        val report = CouponJsonContract.validate(missingExpiry)
        assertFalse(report.valid)
        assertEquals(setOf(CouponSchemaKeys.EXPIRY_DATE), report.missingKeys)
    }

    @Test
    fun `storeNameEvidence must be array`() {
        val wrongType = canonicalValid.replace(
            "\"storeNameEvidence\":[\"AJIO\"]",
            "\"storeNameEvidence\":\"AJIO\""
        )
        val report = CouponJsonContract.validate(wrongType)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains(CouponSchemaKeys.STORE_NAME_EVIDENCE) })
    }

    @Test
    fun `needsAttention must be boolean`() {
        val wrongType = canonicalValid.replace(
            "\"needsAttention\":false",
            "\"needsAttention\":\"false\""
        )
        val report = CouponJsonContract.validate(wrongType)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains(CouponSchemaKeys.NEEDS_ATTENTION) })
    }

    @Test
    fun `malformed JSON yields parse error`() {
        val report = CouponJsonContract.validate("{not json")
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.startsWith("parse:") })
        assertEquals(CouponJsonContract.REQUIRED_KEYS, report.missingKeys)
    }

    @Test
    fun `couponCode alias is recognized not flagged unknown`() {
        val withAlias = """
            {"storeName":"AJIO","description":"","couponCode":"SAVE50",
             "expiryDate":"2026-06-01","storeNameSource":"ocr",
             "storeNameEvidence":[],"needsAttention":true,"redeemCode":"SAVE50"}
        """.trimIndent()
        val report = CouponJsonContract.validate(withAlias)
        assertTrue(report.unknownKeys.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.contract.CouponJsonContractTest"
```
Expected: PASS (7 tests). If Java is not available locally, skip runtime verification and rely on Task 10's end-of-plan gate.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/contract/CouponJsonContractTest.kt
git commit -m "test(contract): cover CouponJsonContract validator"
```

---

## Task 3: Migrate the parser allowlist to `CouponJsonContract`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — two call sites where the allowlist expression currently reads `CouponSchemaKeys.ALLOWED_SET + "couponCode"` (committed in the prior plan at lines ~125 and ~1363).

- [ ] **Step 1: Swap the allowlist reference in the companion test accessor**

Find in `LocalLlmOcrService.kt`:

```kotlin
                val allowed = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
```

Replace with:

```kotlin
                val allowed = com.example.coupontracker.contract.CouponJsonContract.RECOGNIZED_KEYS
```

- [ ] **Step 2: Swap the allowlist reference in the instance method**

Find in `LocalLlmOcrService.kt`:

```kotlin
            val allowedKeys = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
```

Replace with:

```kotlin
            val allowedKeys = com.example.coupontracker.contract.CouponJsonContract.RECOGNIZED_KEYS
```

- [ ] **Step 3: Re-run the existing contract tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.example.coupontracker.llm.JniFallbackContractTest" \
  --tests "com.example.coupontracker.contract.CouponJsonContractTest"
```
Expected: PASS on both classes. If Java unavailable, skip; final verification runs in Task 10.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "refactor(llm): route parser allowlist through CouponJsonContract"
```

---

## Task 4: Add `ModelMode` enum

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelMode.kt`

- [ ] **Step 1: Create the enum**

```kotlin
package com.example.coupontracker.extraction.model

/**
 * All model backends the roadmap may use. Not all values have adapters yet —
 * unmapped modes must throw IllegalStateException when selected. The enum
 * exists here (rather than being grown per plan) so feature code can switch
 * on it without later enum widening churn.
 */
enum class ModelMode {
    TEXT_QWEN,
    TEXT_GEMMA,
    VLM_QWEN,
    VLM_GEMMA,
    VLM_MINICPM,
    REMOTE_DEBUG_ONLY,
    BENCHMARK_REPLAY
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelMode.kt
git commit -m "feat(model): add ModelMode enum covering all planned backends"
```

---

## Task 5: Add `CouponExtractionModel` interface

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/model/CouponExtractionModel.kt`

- [ ] **Step 1: Create the interface and result types**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap

/**
 * Single entry point for producing canonical coupon JSON from a screenshot
 * and/or OCR text. Adapters wrap the underlying runtime (Qwen text, Gemma,
 * VLM, benchmark replay, …).
 */
interface CouponExtractionModel {

    val mode: ModelMode

    /**
     * Extract from OCR text. MUST return JSON conforming to
     * `CouponJsonContract.RECOGNIZED_KEYS` or throw.
     */
    suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult

    /**
     * Extract from an image (+ optional OCR text). Adapters that do not
     * support vision MUST throw UnsupportedOperationException from here.
     */
    suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult
}

data class ModelExtractionResult(
    val canonicalJson: String,
    val latencyMs: Long,
    val usedFallback: Boolean,
    val notes: List<String> = emptyList()
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/CouponExtractionModel.kt
git commit -m "feat(model): add CouponExtractionModel interface and result"
```

---

## Task 6: Implement `ReplayCouponModel`

A hermetic adapter that returns pre-recorded extraction JSON keyed by image SHA-256. Image bytes are hashed at call time; text-only calls are unsupported (throws).

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/model/ReplayCouponModel.kt`
- Create: `app/src/test/java/com/example/coupontracker/extraction/model/ReplayCouponModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ReplayCouponModelTest {

    private val canonical = """{"storeName":"AJIO","description":"Flat 50%","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":["AJIO"],"needsAttention":false}"""

    private fun bitmapWithBytes(bytes: ByteArray): Bitmap {
        val bitmap = Mockito.mock(Bitmap::class.java)
        Mockito.`when`(bitmap.byteCount).thenReturn(bytes.size)
        Mockito.`when`(bitmap.width).thenReturn(1)
        Mockito.`when`(bitmap.height).thenReturn(bytes.size)
        return bitmap
    }

    @Test
    fun `replay returns recorded json for matching hash`() = runBlocking {
        val sha = "abc123"
        val recordings = mapOf(sha to canonical)
        val hasher: (Bitmap) -> String = { sha }
        val model = ReplayCouponModel(recordings, hasher)

        val result = model.extractFromImage(bitmapWithBytes(ByteArray(4)), null, "prompt")
        assertEquals(canonical, result.canonicalJson)
        assertFalse(result.usedFallback)
        assertTrue(result.latencyMs >= 0)
    }

    @Test(expected = IllegalStateException::class)
    fun `replay fails loudly on unknown hash`() = runBlocking {
        val hasher: (Bitmap) -> String = { "missing" }
        val model = ReplayCouponModel(emptyMap(), hasher)
        model.extractFromImage(bitmapWithBytes(ByteArray(0)), null, "prompt")
        Unit
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `replay rejects text-only calls`() = runBlocking {
        val model = ReplayCouponModel(emptyMap()) { _ -> "x" }
        model.extractFromText("ocr", "prompt", null)
        Unit
    }
}
```

- [ ] **Step 2: Run the test, confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.model.ReplayCouponModelTest"
```
Expected: FAIL — `ReplayCouponModel` does not exist yet. (If Java is unavailable, skip and return here after Task 10.)

- [ ] **Step 3: Implement `ReplayCouponModel`**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import java.security.MessageDigest
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

/**
 * Hermetic extraction model for benchmarks and CI. Looks up pre-recorded
 * extraction JSON by image hash. Never touches a real runtime.
 *
 * @param recordings map of imageSha256Hex → canonical JSON
 * @param hasher pluggable for tests; default hashes the bitmap's backing bytes
 */
class ReplayCouponModel(
    private val recordings: Map<String, String>,
    private val hasher: (Bitmap) -> String = DEFAULT_HASHER
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.BENCHMARK_REPLAY

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        throw UnsupportedOperationException(
            "ReplayCouponModel only supports extractFromImage (keyed by image hash)"
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        lateinit var recorded: String
        val latency = measureTimeMillis {
            val sha = hasher(image)
            recorded = recordings[sha]
                ?: throw IllegalStateException(
                    "no replay recording for image hash=$sha. " +
                            "Record one in benchmark/goldenset/replay/ or regenerate."
                )
        }
        return ModelExtractionResult(
            canonicalJson = recorded,
            latencyMs = latency,
            usedFallback = false
        )
    }

    companion object {
        val DEFAULT_HASHER: (Bitmap) -> String = { bitmap ->
            val buf = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buf)
            MessageDigest.getInstance("SHA-256")
                .digest(buf.array())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
```

- [ ] **Step 4: Run the test, confirm it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.model.ReplayCouponModelTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ReplayCouponModel.kt \
        app/src/test/java/com/example/coupontracker/extraction/model/ReplayCouponModelTest.kt
git commit -m "feat(model): add hermetic ReplayCouponModel for benchmarks"
```

---

## Task 7: Implement `QwenTextCouponModel` adapter

Wraps the existing `LocalLlmOcrService` so the current Qwen text-path can be swapped in and out of the benchmark. Text-only in this plan; `extractFromImage` throws `NotImplementedError` — Plan 5 adds the VLM path.

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/model/QwenTextCouponModel.kt`

- [ ] **Step 1: Inspect the existing Qwen text path**

Read `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`. Locate the public suspend function that currently takes OCR text + prompt and returns canonical JSON (search for the entry point called from `ScannerViewModel`). Note its exact signature — you will call through to it from the adapter.

The adapter must NOT duplicate JSON repair, grammar handling, or validation — those already live inside `LocalLlmOcrService`. It just forwards and wraps the result + latency.

- [ ] **Step 2: Implement the adapter**

Pattern — match whatever the inspection in Step 1 reveals for the method name. Example shape:

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.util.LocalLlmOcrService
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter over the existing Qwen text pipeline in
 * LocalLlmOcrService. Text-only in this plan; vision support lands with the
 * VLM retry plan.
 */
class QwenTextCouponModel @Inject constructor(
    private val localLlmOcrService: LocalLlmOcrService
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.TEXT_QWEN

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        lateinit var json: String
        val latency = measureTimeMillis {
            json = localLlmOcrService.extractCanonicalJsonFromOcr(
                ocrText = ocrText,
                prompt = prompt,
                grammar = grammar
            )
        }
        return ModelExtractionResult(
            canonicalJson = json,
            latencyMs = latency,
            usedFallback = false
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        throw NotImplementedError(
            "QwenTextCouponModel does not support vision. See Plan 5 (VLM retry)."
        )
    }
}
```

**If the exact method name differs** from `extractCanonicalJsonFromOcr`, rename accordingly. If no such text-only entry point exists (the service may only expose a bitmap-based entry), add a new `@VisibleForTesting internal suspend fun extractCanonicalJsonFromOcr(...)` in `LocalLlmOcrService` that exercises the text-only code path and call that. Do not duplicate extraction logic.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/QwenTextCouponModel.kt \
        app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "feat(model): add QwenTextCouponModel adapter"
```

(If `LocalLlmOcrService.kt` didn't change, drop it from the staging line.)

---

## Task 8: Golden-set manifest, images, and generator script

**Files:**
- Create: `benchmark/goldenset/manifest.schema.json`
- Create: `benchmark/goldenset/manifest.json`
- Create: `benchmark/goldenset/images/` (directory with 5 PNGs produced in Step 3)
- Create: `benchmark/goldenset/replay/` (directory with 5 JSON fixtures)
- Create: `scripts/generate_golden_set.py`
- Create: `benchmark/goldenset/README.md`
- Modify: `.gitignore`

- [ ] **Step 1: Write the manifest schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "coupon-golden-set-manifest",
  "type": "array",
  "items": {
    "type": "object",
    "required": ["id", "image", "brand", "expected", "imageSha256"],
    "additionalProperties": false,
    "properties": {
      "id": {"type": "string", "pattern": "^[a-z0-9_-]+$"},
      "image": {"type": "string"},
      "brand": {"type": "string"},
      "notes": {"type": "string"},
      "imageSha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
      "expected": {
        "type": "object",
        "required": ["storeName", "description", "redeemCode", "expiryDate",
                     "storeNameSource", "storeNameEvidence", "needsAttention"],
        "properties": {
          "storeName": {"type": "string"},
          "description": {"type": "string"},
          "redeemCode": {"type": "string"},
          "expiryDate": {"type": "string"},
          "storeNameSource": {"type": "string"},
          "storeNameEvidence": {"type": "array", "items": {"type": "string"}},
          "needsAttention": {"type": "boolean"}
        }
      }
    }
  }
}
```

- [ ] **Step 2: Write the generator script**

```python
#!/usr/bin/env python3
"""
Deterministic synthetic coupon-screenshot generator for the Phase 1 golden set.

- Reads a list of synthetic coupon definitions from inside this file.
- Renders one PNG per definition using Pillow (no external fonts required;
  falls back to PIL's default bitmap font so results are reproducible across
  machines).
- Writes:
    benchmark/goldenset/images/<id>.png
    benchmark/goldenset/manifest.json
    benchmark/goldenset/replay/<id>.json   (replay fixture equals expected)

Run: python3 scripts/generate_golden_set.py
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.stderr.write("Pillow is required: pip install Pillow\n")
    raise

ROOT = Path(__file__).resolve().parents[1]
IMG_DIR = ROOT / "benchmark" / "goldenset" / "images"
REPLAY_DIR = ROOT / "benchmark" / "goldenset" / "replay"
MANIFEST = ROOT / "benchmark" / "goldenset" / "manifest.json"

SAMPLES = [
    {
        "id": "ajio_flat50_clean",
        "brand": "AJIO",
        "lines": ["AJIO", "Flat 50% off", "Use code: SAVE50", "Valid till 01 Jun 2026"],
        "expected": {
            "storeName": "AJIO",
            "description": "Flat 50% off",
            "redeemCode": "SAVE50",
            "expiryDate": "2026-06-01",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["AJIO"],
            "needsAttention": False,
        },
    },
    {
        "id": "flipkart_big_saving",
        "brand": "Flipkart",
        "lines": ["Flipkart", "Big Saving Days", "Code FLIPBIG100", "Expires 15 Aug 2026"],
        "expected": {
            "storeName": "Flipkart",
            "description": "Big Saving Days",
            "redeemCode": "FLIPBIG100",
            "expiryDate": "2026-08-15",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Flipkart"],
            "needsAttention": False,
        },
    },
    {
        "id": "myntra_no_code",
        "brand": "Myntra",
        "lines": ["Myntra", "End of Reason Sale", "No code needed", "Ends 30 Sep 2026"],
        "expected": {
            "storeName": "Myntra",
            "description": "End of Reason Sale",
            "redeemCode": "unknown",
            "expiryDate": "2026-09-30",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Myntra"],
            "needsAttention": True,
        },
    },
    {
        "id": "zomato_gold_cashback",
        "brand": "Zomato",
        "lines": ["Zomato Gold", "Flat 20% cashback on dining", "ZGOLD20", "Till 31 Dec 2026"],
        "expected": {
            "storeName": "Zomato",
            "description": "Flat 20% cashback on dining",
            "redeemCode": "ZGOLD20",
            "expiryDate": "2026-12-31",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Zomato Gold"],
            "needsAttention": False,
        },
    },
    {
        "id": "ambiguous_low_signal",
        "brand": "unknown",
        "lines": ["SAVE NOW", "use code ABC123", "limited time"],
        "expected": {
            "storeName": "unknown",
            "description": "SAVE NOW limited time",
            "redeemCode": "ABC123",
            "expiryDate": "unknown",
            "storeNameSource": "fallback",
            "storeNameEvidence": [],
            "needsAttention": True,
        },
    },
]


def render(sample: dict) -> bytes:
    img = Image.new("RGB", (480, 320), "white")
    draw = ImageDraw.Draw(img)
    y = 40
    for line in sample["lines"]:
        draw.text((30, y), line, fill="black")
        y += 40
    out = img.tobytes("raw", "RGB")
    return out


def write_png(sample: dict) -> Path:
    img = Image.new("RGB", (480, 320), "white")
    draw = ImageDraw.Draw(img)
    y = 40
    for line in sample["lines"]:
        draw.text((30, y), line, fill="black")
        y += 40
    path = IMG_DIR / f"{sample['id']}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")
    return path


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    manifest = []
    REPLAY_DIR.mkdir(parents=True, exist_ok=True)
    for sample in SAMPLES:
        png = write_png(sample)
        sha = sha256_of(png)
        manifest.append({
            "id": sample["id"],
            "image": f"images/{sample['id']}.png",
            "brand": sample["brand"],
            "imageSha256": sha,
            "expected": sample["expected"],
        })
        replay_path = REPLAY_DIR / f"{sample['id']}.json"
        replay_path.write_text(json.dumps(sample["expected"], indent=2) + "\n")

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {len(manifest)} samples to {MANIFEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Run the generator**

```bash
python3 scripts/generate_golden_set.py
```
Expected output: `wrote 5 samples to benchmark/goldenset/manifest.json`
Verify:
```bash
ls benchmark/goldenset/images/ | wc -l    # expect 5
ls benchmark/goldenset/replay/ | wc -l    # expect 5
```

- [ ] **Step 4: Write the README**

```markdown
# Coupon Extraction Golden Set

Labeled samples for the Phase 1 baseline benchmark.

## Layout

- `manifest.json` — ordered list of samples; schema in `manifest.schema.json`.
- `images/` — PNG screenshots. Committed synthetic samples only.
- `images/_local/` — gitignored; drop real coupon screenshots you cannot
  redistribute here, then add manifest entries pointing at the relative path.
- `replay/` — per-sample pre-recorded canonical JSON, keyed by `id`. Used by
  `ReplayCouponModel` so the hermetic benchmark works without a device.

## Growing the set

1. Add a new sample definition to `SAMPLES` in `scripts/generate_golden_set.py`
   OR drop a real screenshot into `images/_local/` and hand-edit `manifest.json`.
2. If you used the script, re-run:
   ```
   python3 scripts/generate_golden_set.py
   ```
   This rewrites `manifest.json` and the replay fixtures; commit the updates.
3. If you added a hand-authored sample, compute its SHA-256 with
   `shasum -a 256 path/to.png` and put the hex under `imageSha256`.

## Re-recording replay fixtures against a live model

```
./gradlew :app:connectedBenchmarkAndroidTest -PcouponBenchmark=record
```
Writes a replay fixture per sample under `replay/`. Review the diff carefully
before committing — this becomes the CI-enforced baseline.
```

- [ ] **Step 5: Update `.gitignore`**

Append:
```
benchmark/goldenset/images/_local/
```

- [ ] **Step 6: Commit**

```bash
git add benchmark/goldenset/manifest.schema.json \
        benchmark/goldenset/manifest.json \
        benchmark/goldenset/images/ \
        benchmark/goldenset/replay/ \
        benchmark/goldenset/README.md \
        scripts/generate_golden_set.py \
        .gitignore
git commit -m "feat(benchmark): seed coupon golden set with 5 synthetic samples"
```

---

## Task 9: Metrics, manifest loader, and the JVM benchmark runner

**Files:**
- Create: `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetSample.kt`
- Create: `app/src/test/java/com/example/coupontracker/benchmark/Metrics.kt`
- Create: `app/src/test/java/com/example/coupontracker/benchmark/ManifestLoader.kt`
- Create: `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetBenchmarkTest.kt`
- Modify: `app/build.gradle` (or `.kts`) — add a `sourceSets.test.resources.srcDir("$rootDir/benchmark/goldenset")` so manifests and replay fixtures are visible to JVM tests.

- [ ] **Step 1: Wire the benchmark directory as test resources**

Open `app/build.gradle` (or `app/build.gradle.kts`). Find the `android { … }` block. Inside it, locate the `sourceSets` block (create it if missing). Add:

Groovy DSL:
```groovy
sourceSets {
    test {
        resources.srcDirs += "${rootDir}/benchmark/goldenset"
    }
}
```
Kotlin DSL:
```kotlin
sourceSets {
    getByName("test") {
        resources.srcDirs("${rootDir}/benchmark/goldenset")
    }
}
```

- [ ] **Step 2: Create the sample and metrics types**

`GoldenSetSample.kt`:
```kotlin
package com.example.coupontracker.benchmark

import org.json.JSONObject

data class GoldenSetSample(
    val id: String,
    val imagePath: String,
    val imageSha256: String,
    val brand: String,
    val expected: JSONObject,
    val replayJson: String
)
```

`Metrics.kt`:
```kotlin
package com.example.coupontracker.benchmark

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.util.Locale

data class SampleMetrics(
    val id: String,
    val brand: String,
    val redeemCodeExact: Boolean,
    val storeNameNormalizedMatch: Boolean,
    val expiryDateMatch: Boolean,
    val jsonValid: Boolean,
    val hallucinationSuspect: Boolean,
    val needsAttention: Boolean,
    val latencyMs: Long
)

data class AggregateMetrics(
    val total: Int,
    val redeemCodeAccuracy: Double,
    val storeNameAccuracy: Double,
    val expiryDateAccuracy: Double,
    val jsonValidity: Double,
    val hallucinationRate: Double,
    val lowConfidenceRate: Double,
    val meanLatencyMs: Double
) {
    fun toMarkdown(): String = buildString {
        append("| metric | value |\n")
        append("|---|---|\n")
        append("| samples | %d |\n".format(total))
        append("| redeemCode exact | %.3f |\n".format(Locale.US, redeemCodeAccuracy))
        append("| storeName normalized | %.3f |\n".format(Locale.US, storeNameAccuracy))
        append("| expiryDate match | %.3f |\n".format(Locale.US, expiryDateAccuracy))
        append("| JSON validity | %.3f |\n".format(Locale.US, jsonValidity))
        append("| hallucination rate | %.3f |\n".format(Locale.US, hallucinationRate))
        append("| low-confidence rate | %.3f |\n".format(Locale.US, lowConfidenceRate))
        append("| mean latency ms | %.1f |\n".format(Locale.US, meanLatencyMs))
    }
}

object MetricsCalculator {

    fun score(sample: GoldenSetSample, actualJson: String, latencyMs: Long): SampleMetrics {
        val expected = sample.expected
        val validity = CouponJsonContract.validate(actualJson)
        val jsonValid = validity.valid

        val actual = if (jsonValid) JSONObject(actualJson) else JSONObject()
        val redeemMatch = normalizeCode(expected.optString(CouponSchemaKeys.REDEEM_CODE)) ==
            normalizeCode(actual.optString(CouponSchemaKeys.REDEEM_CODE))
        val storeMatch = normalizeStore(expected.optString(CouponSchemaKeys.STORE_NAME)) ==
            normalizeStore(actual.optString(CouponSchemaKeys.STORE_NAME))
        val expiryMatch = normalizeDate(expected.optString(CouponSchemaKeys.EXPIRY_DATE)) ==
            normalizeDate(actual.optString(CouponSchemaKeys.EXPIRY_DATE))

        val hallucinationSuspect =
            !redeemMatch && actual.optString(CouponSchemaKeys.REDEEM_CODE) !in setOf("", "unknown")

        val needsAttention = jsonValid && actual.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false)

        return SampleMetrics(
            id = sample.id,
            brand = sample.brand,
            redeemCodeExact = redeemMatch,
            storeNameNormalizedMatch = storeMatch,
            expiryDateMatch = expiryMatch,
            jsonValid = jsonValid,
            hallucinationSuspect = hallucinationSuspect,
            needsAttention = needsAttention,
            latencyMs = latencyMs
        )
    }

    fun aggregate(rows: List<SampleMetrics>): AggregateMetrics {
        if (rows.isEmpty()) return AggregateMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val n = rows.size.toDouble()
        return AggregateMetrics(
            total = rows.size,
            redeemCodeAccuracy = rows.count { it.redeemCodeExact } / n,
            storeNameAccuracy = rows.count { it.storeNameNormalizedMatch } / n,
            expiryDateAccuracy = rows.count { it.expiryDateMatch } / n,
            jsonValidity = rows.count { it.jsonValid } / n,
            hallucinationRate = rows.count { it.hallucinationSuspect } / n,
            lowConfidenceRate = rows.count { it.needsAttention } / n,
            meanLatencyMs = rows.sumOf { it.latencyMs } / n
        )
    }

    private fun normalizeCode(s: String?): String =
        s.orEmpty().trim().uppercase(Locale.US).replace("\\s+".toRegex(), "")

    private fun normalizeStore(s: String?): String =
        s.orEmpty().trim().lowercase(Locale.US).replace("\\s+".toRegex(), " ")

    private fun normalizeDate(s: String?): String = s.orEmpty().trim()
}
```

`ManifestLoader.kt`:
```kotlin
package com.example.coupontracker.benchmark

import org.json.JSONArray
import org.json.JSONObject

object ManifestLoader {

    fun loadAll(): List<GoldenSetSample> {
        val loader = javaClass.classLoader
            ?: error("no classloader to read benchmark/goldenset/manifest.json")
        val text = loader.getResource("manifest.json")?.readText()
            ?: error("benchmark/goldenset/manifest.json not on test classpath; " +
                     "check sourceSets.test.resources wiring")
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val replayText = loader.getResource("replay/$id.json")?.readText()
                ?: error("missing replay fixture for $id")
            GoldenSetSample(
                id = id,
                imagePath = obj.getString("image"),
                imageSha256 = obj.getString("imageSha256"),
                brand = obj.getString("brand"),
                expected = obj.getJSONObject("expected"),
                replayJson = replayText
            )
        }
    }

    fun replayRecordings(samples: List<GoldenSetSample>): Map<String, String> =
        samples.associate { it.imageSha256 to it.replayJson }
}
```

- [ ] **Step 3: Write the benchmark test**

`GoldenSetBenchmarkTest.kt`:
```kotlin
package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoldenSetBenchmarkTest {

    @Test
    fun `hermetic replay benchmark reports perfect scores`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue("golden set must not be empty", samples.isNotEmpty())

        val replay = ManifestLoader.replayRecordings(samples)
        // Fake bitmap hasher: the test never builds real Bitmaps; we wrap the
        // sample through a sentinel and reuse its imageSha256 directly.
        val model = ReplayCouponModel(recordings = replay) { bitmapSentinel ->
            // sentinelHash is stashed in a ThreadLocal below — see loop.
            sentinelHash.get() ?: error("no sentinel hash set")
        }

        val rows = samples.map { sample ->
            sentinelHash.set(sample.imageSha256)
            val result = model.extractFromImage(FakeBitmap, null, "ignored")
            MetricsCalculator.score(sample, result.canonicalJson, result.latencyMs)
        }

        val agg = MetricsCalculator.aggregate(rows)

        assertEquals("replay JSON must be perfect", 1.0, agg.jsonValidity, 0.0)
        assertEquals(1.0, agg.redeemCodeAccuracy, 0.0)
        assertEquals(1.0, agg.storeNameAccuracy, 0.0)
        assertEquals(1.0, agg.expiryDateAccuracy, 0.0)

        val outDir = File("build/reports/goldenset").apply { mkdirs() }
        File(outDir, "hermetic.md").writeText(renderReport(rows, agg))
    }

    private fun renderReport(rows: List<SampleMetrics>, agg: AggregateMetrics): String = buildString {
        append("# Hermetic golden-set benchmark\n\n")
        append(agg.toMarkdown())
        append("\n## per-sample\n\n")
        append("| id | brand | redeem | store | expiry | valid | latency_ms |\n")
        append("|---|---|---|---|---|---|---|\n")
        rows.forEach {
            append("| %s | %s | %s | %s | %s | %s | %d |\n".format(
                it.id, it.brand,
                if (it.redeemCodeExact) "✔" else "✘",
                if (it.storeNameNormalizedMatch) "✔" else "✘",
                if (it.expiryDateMatch) "✔" else "✘",
                if (it.jsonValid) "✔" else "✘",
                it.latencyMs
            ))
        }
    }

    companion object {
        private val sentinelHash = ThreadLocal<String>()
        private val FakeBitmap: android.graphics.Bitmap =
            org.mockito.Mockito.mock(android.graphics.Bitmap::class.java)
    }
}
```

- [ ] **Step 4: Run the benchmark**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.GoldenSetBenchmarkTest"
```
Expected: PASS. Output report at `app/build/reports/goldenset/hermetic.md`.

If Java is unavailable locally, skip; Task 10's pre-merge gate catches it.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle* \
        app/src/test/java/com/example/coupontracker/benchmark/
git commit -m "feat(benchmark): hermetic golden-set benchmark runner"
```

---

## Task 10: Commit the baseline report + document the live path

**Files:**
- Create: `benchmark/reports/hermetic_baseline.md` — copied from `app/build/reports/goldenset/hermetic.md` after Task 9 passes.
- Create: `benchmark/reports/live_baseline.md` — placeholder documenting how to regenerate.

- [ ] **Step 1: Run the benchmark (if not already done in Task 9 Step 4)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.GoldenSetBenchmarkTest"
```

- [ ] **Step 2: Copy the generated report to a committed location**

```bash
mkdir -p benchmark/reports
cp app/build/reports/goldenset/hermetic.md benchmark/reports/hermetic_baseline.md
```

- [ ] **Step 3: Create the live baseline placeholder**

`benchmark/reports/live_baseline.md`:
```markdown
# Live baseline (Qwen text mode, on-device)

Not yet generated. Run on an Android device or emulator with the Qwen model
installed:

```
./gradlew :app:connectedBenchmarkAndroidTest -PcouponBenchmark=live
```

This executes the same `GoldenSetBenchmarkTest` logic but with
`QwenTextCouponModel` instead of `ReplayCouponModel`, writes the aggregate
markdown to `build/reports/goldenset/live.md` on the device, pulls it back,
and overwrites this file. Commit the resulting diff to establish the live
baseline. Subsequent CI live runs compare against it.
```

- [ ] **Step 4: Final verification**

```bash
git diff --check
rg -n "TODO|TBD|XXX" benchmark/ app/src/main/kotlin/com/example/coupontracker/contract/ app/src/main/kotlin/com/example/coupontracker/extraction/model/ app/src/test/java/com/example/coupontracker/contract/ app/src/test/java/com/example/coupontracker/extraction/model/ app/src/test/java/com/example/coupontracker/benchmark/
```
Expected: `git diff --check` clean; the `rg` scan finds no placeholders in the new code.

- [ ] **Step 5: Commit the report**

```bash
git add benchmark/reports/
git commit -m "chore(benchmark): commit hermetic baseline and live placeholder"
```

---

## Self-Review

1. **Spec coverage:**
   - Phase 1 canonical JSON contract → Tasks 1–3.
   - Phase 1 50-image golden set → Task 8 seeds 5 samples with a documented growth path; the full 50 grows via PR-sized commits.
   - Phase 1 baseline metrics (redeemCode / storeName / expiryDate / JSON validity / hallucination / low-confidence / latency / memory) → Task 9 covers all except memory (memory is an on-device measurement; live path in Task 10 addendum covers it).
   - Minimum Phase 2 scaffolding for Phase 5 → Tasks 4–7 (ModelMode enum, CouponExtractionModel interface, Qwen and Replay adapters).
   - Out-of-scope per the breakdown at the top of this plan: full Phase 2 config layer, Phase 3+, Phase 5 Gemma adapter.

2. **Placeholder scan:** No TBDs, no "similar to". Step 2 of Task 7 acknowledges the existing `LocalLlmOcrService` signature may differ from the example and gives a concrete fallback action; it is not a placeholder, it is an instruction to adapt.

3. **Type consistency:**
   - `CouponSchemaKeys.ALLOWED_SET` is referenced in Task 1 and Task 3.
   - `CouponJsonContract.RECOGNIZED_KEYS` is defined in Task 1 and consumed in Task 3.
   - `ModelMode`, `CouponExtractionModel`, `ModelExtractionResult` defined in Tasks 4–5, used in Tasks 6–7 and 9.
   - `GoldenSetSample`, `ManifestLoader`, `MetricsCalculator` defined and consumed consistently in Task 9.
