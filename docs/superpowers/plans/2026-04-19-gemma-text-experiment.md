# Gemma Text Experiment (Phase 5 text) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `GemmaTextCouponModel` adapter running in the `TEXT_GEMMA` slot and benchmark it against the Qwen baseline on the golden set from Plan 1. Produce a decision note: keep Gemma as EXPERIMENT, promote to DEFAULT, or drop.

**Architecture:** Gemma 2B-IT (or smaller instruct variant) runs through MediaPipe's LLM Inference API — a first-party Android path that does not require our existing MLC runtime. The adapter wraps `com.google.mediapipe.tasks.genai.llminference.LlmInference` behind the same `CouponExtractionModel` shape. `ModelMode.TEXT_GEMMA` already exists from Plan 1. `ModelModule` gets a new `@Binds @IntoSet` line. Then a parameterised golden-set benchmark runs both adapters and writes a comparison report. No changes to `LocalLlmOcrService` — Plan 2's `ModelSelector` already handles the switch via `ModelStrategyConfig`.

**Tech Stack:** Kotlin 1.9, Hilt 2.48, MediaPipe GenAI (`com.google.mediapipe:tasks-genai:0.10.14+`), JUnit 4, MockK.

---

## Pre-flight

- Plans 1 and 2 landed.
- `ModelMode.TEXT_GEMMA` already exists.
- `CouponExtractionModel`, `ModelSelector`, `ModelStrategyConfig` exist.
- Gemma model weights (`.bin` or `.task` bundle) are NOT in the repo; they must be fetched per-device. `ModelAssetManager` already handles this kind of asset download for the Qwen path — follow its pattern.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/llm/gemma/GemmaRuntime.kt` — thin wrapper around MediaPipe's `LlmInference`, mirroring the shape of `LlmRuntimeManager.runTextInference`. Separate package so the MediaPipe dependency stays contained.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/GemmaTextCouponModel.kt` — adapter using `GemmaRuntime`.
- `app/src/test/java/com/example/coupontracker/extraction/model/GemmaTextCouponModelTest.kt` — unit test with mocked runtime.
- `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetAbTest.kt` — runs both adapters against the same fixtures, captures deltas.
- `benchmark/reports/gemma_vs_qwen_hermetic.md` — committed comparison output.
- `benchmark/reports/gemma_vs_qwen_live.md` — placeholder; a developer fills it in after running on device.

### Files to modify

- `app/build.gradle.kts` — add `implementation("com.google.mediapipe:tasks-genai:0.10.14")` (pick latest stable at write time).
- `app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt` — register `GemmaTextCouponModel` via `@Binds @IntoSet`.
- `app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt` — add a Gemma asset entry alongside the existing Qwen entry. Read the existing entries first; match their shape (URL, expected SHA-256, local path, license gate).

---

## Task 1: Add MediaPipe GenAI dependency

- [ ] **Step 1: Edit `app/build.gradle.kts`**

Find the `dependencies { … }` block. Add one line under the `implementation(...)` group where other Google/ML dependencies live (search for `mlkit`):

```kotlin
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
```

Also check whether `com.google.mediapipe:tasks-genai` requires a specific Maven repository. It is hosted on `google()`, which is already declared in the root `build.gradle.kts`. No repo change is expected.

- [ ] **Step 2: Verify**

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i mediapipe
```
Expected: `com.google.mediapipe:tasks-genai:0.10.14`. Skip if Java unavailable.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(deps): add MediaPipe GenAI for Gemma text inference"
```

---

## Task 2: Implement `GemmaRuntime`

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/llm/gemma/GemmaRuntime.kt`.

- [ ] **Step 1: Create the wrapper**

```kotlin
package com.example.coupontracker.llm.gemma

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around MediaPipe GenAI's LlmInference for the Gemma text
 * path. Structured to mirror LlmRuntimeManager.runTextInference so the
 * adapter-level code is symmetric across backends.
 *
 * Model path defaults to the location ModelAssetManager.GEMMA_TEXT_PATH
 * writes to. If the file is missing, `runTextInference` returns null so
 * the caller (adapter) can surface the missing-asset case via a standard
 * ModelExtractionResult error path.
 */
@Singleton
class GemmaRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()
    @Volatile private var inference: LlmInference? = null

    suspend fun runTextInference(
        ocrText: String,
        prompt: String,
        maxTokensOverride: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val engine = acquire() ?: return@withContext null
        val combined = buildString {
            append(prompt.trim())
            append("\n\n")
            append(ocrText.trim())
        }
        mutex.withLock {
            try {
                engine.generateResponse(combined)
            } catch (e: Exception) {
                Log.w(TAG, "Gemma inference failed", e)
                null
            }
        }
    }

    fun isReady(): Boolean = inference != null

    fun release() {
        synchronized(this) {
            inference?.close()
            inference = null
        }
    }

    private fun acquire(): LlmInference? {
        val cached = inference
        if (cached != null) return cached
        synchronized(this) {
            inference?.let { return it }
            val path = File(context.filesDir, MODEL_RELATIVE_PATH)
            if (!path.exists()) {
                Log.w(TAG, "Gemma model not found at ${path.absolutePath}")
                return null
            }
            val options = LlmInferenceOptions.builder()
                .setModelPath(path.absolutePath)
                .setMaxTokens(DEFAULT_MAX_TOKENS)
                .setTopK(1)
                .setTemperature(0.1f)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            return inference
        }
    }

    companion object {
        private const val TAG = "GemmaRuntime"
        const val MODEL_RELATIVE_PATH = "gemma/gemma-2b-it.task"
        const val DEFAULT_MAX_TOKENS = 512
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/llm/gemma/GemmaRuntime.kt
git commit -m "feat(llm): add Gemma runtime wrapper over MediaPipe GenAI"
```

---

## Task 3: Implement `GemmaTextCouponModel`

**Files:**
- Create `app/src/main/kotlin/com/example/coupontracker/extraction/model/GemmaTextCouponModel.kt`
- Create `app/src/test/java/com/example/coupontracker/extraction/model/GemmaTextCouponModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.llm.gemma.GemmaRuntime
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaTextCouponModelTest {

    private val canonical =
        """{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}"""

    @Test
    fun `happy path returns canonical JSON from runtime`() = runBlocking {
        val runtime = mockk<GemmaRuntime>()
        coEvery { runtime.runTextInference(any(), any(), any()) } returns canonical

        val model = GemmaTextCouponModel(runtime)
        val result = model.extractFromText("ocr text", "prompt", grammar = null)

        assertEquals(ModelMode.TEXT_GEMMA, model.mode)
        assertEquals(canonical, result.canonicalJson)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `runtime returning null throws`() = runBlocking {
        val runtime = mockk<GemmaRuntime>()
        coEvery { runtime.runTextInference(any(), any(), any()) } returns null

        val model = GemmaTextCouponModel(runtime)
        val thrown = runCatching { model.extractFromText("ocr", "prompt", null) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test(expected = NotImplementedError::class)
    fun `extractFromImage not supported`() = runBlocking {
        val model = GemmaTextCouponModel(mockk())
        model.extractFromImage(mockk<Bitmap>(relaxed = true), null, "prompt")
        Unit
    }
}
```

- [ ] **Step 2: Implement the adapter**

```kotlin
package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.gemma.GemmaRuntime
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter over the Gemma text inference path.
 * Composition: GemmaRuntime.runTextInference(...) → CouponJsonContract.enforce(...).
 */
class GemmaTextCouponModel @Inject constructor(
    private val runtime: GemmaRuntime
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.TEXT_GEMMA

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = runtime.runTextInference(
                ocrText = ocrText,
                prompt = prompt,
                maxTokensOverride = null
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("GemmaRuntime returned null (model missing or inference failed)")
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
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
            "GemmaTextCouponModel does not support vision. See Plan 5 (VLM retry)."
        )
    }
}
```

- [ ] **Step 3: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.model.GemmaTextCouponModelTest"
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/GemmaTextCouponModel.kt \
        app/src/test/java/com/example/coupontracker/extraction/model/GemmaTextCouponModelTest.kt
git commit -m "feat(model): add GemmaTextCouponModel adapter"
```

---

## Task 4: Register adapter in Hilt and add asset entry

- [ ] **Step 1: Update `ModelModule.kt`**

Append a second `@Binds @IntoSet` method:

```kotlin
    @Binds
    @IntoSet
    abstract fun bindGemmaText(impl: GemmaTextCouponModel): CouponExtractionModel
```

Also add the import `import com.example.coupontracker.extraction.model.GemmaTextCouponModel`.

- [ ] **Step 2: Add a Gemma asset to `ModelAssetManager`**

Read `app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt`. Find the existing asset registry (likely a `val assets: List<ModelAsset>` or similar). Append a new entry modelled exactly on the existing Qwen entry, pointing at the Gemma model file with its HuggingFace URL and a placeholder SHA-256 (the developer overrides with the real digest once they run the download once and record the value).

If the existing pattern is a sealed class or enum of assets, add a new case with the same shape. If no existing pattern is obvious, stop and escalate DONE_WITH_CONCERNS — asset registration is a prerequisite for the live benchmark and should not be invented from scratch here.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt \
        app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt
git commit -m "feat(model): register Gemma adapter and asset entry"
```

---

## Task 5: A/B benchmark on the golden set

**Files:**
- Create `app/src/test/java/com/example/coupontracker/benchmark/GoldenSetAbTest.kt` — runs BOTH replay fixtures simulating each adapter's output through `MetricsCalculator`.

This test is hermetic; it uses pre-recorded outputs for both adapters so it does not require a device. The live-on-device A/B is documented in Task 6.

- [ ] **Step 1: Add Gemma replay fixtures**

```bash
mkdir -p benchmark/goldenset/replay_gemma
```

For each sample in `benchmark/goldenset/manifest.json`, write a file `benchmark/goldenset/replay_gemma/<id>.json`. Use the same `expected` content as the Qwen fixtures so the initial hermetic run shows zero delta; the developer overwrites these with real Gemma outputs after running the live benchmark.

Concretely, for each of the 5 samples:

```bash
for id in ajio_flat50_clean flipkart_big_saving myntra_no_code zomato_gold_cashback ambiguous_low_signal; do
  cp "benchmark/goldenset/replay/$id.json" "benchmark/goldenset/replay_gemma/$id.json"
done
```

- [ ] **Step 2: Create the A/B test**

```kotlin
package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoldenSetAbTest {

    @Test
    fun `hermetic A B comparison produces a report`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue(samples.isNotEmpty())

        val qwenRecordings = ManifestLoader.replayRecordings(samples)
        val gemmaRecordings = samples.associate { s ->
            val text = javaClass.classLoader!!.getResource("replay_gemma/${s.id}.json")?.readText()
                ?: error("missing replay_gemma/${s.id}.json")
            s.imageSha256 to text
        }

        val qwen = ReplayCouponModel(qwenRecordings) { sentinel.get()!! }
        val gemma = ReplayCouponModel(gemmaRecordings) { sentinel.get()!! }

        val qwenRows = samples.map { s ->
            sentinel.set(s.imageSha256)
            val r = qwen.extractFromImage(fake, null, "")
            MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
        }
        val gemmaRows = samples.map { s ->
            sentinel.set(s.imageSha256)
            val r = gemma.extractFromImage(fake, null, "")
            MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
        }

        val qwenAgg = MetricsCalculator.aggregate(qwenRows)
        val gemmaAgg = MetricsCalculator.aggregate(gemmaRows)

        val out = File("build/reports/goldenset").apply { mkdirs() }
        File(out, "ab_hermetic.md").writeText(render(qwenAgg, gemmaAgg))
    }

    private fun render(q: AggregateMetrics, g: AggregateMetrics): String = buildString {
        append("# Gemma vs Qwen hermetic A/B\n\n")
        append("| metric | Qwen | Gemma | Δ |\n|---|---|---|---|\n")
        row("redeemCode exact", q.redeemCodeAccuracy, g.redeemCodeAccuracy)
        row("storeName normalized", q.storeNameAccuracy, g.storeNameAccuracy)
        row("expiryDate match", q.expiryDateAccuracy, g.expiryDateAccuracy)
        row("JSON validity", q.jsonValidity, g.jsonValidity)
        row("hallucination rate", q.hallucinationRate, g.hallucinationRate)
    }

    private fun StringBuilder.row(name: String, a: Double, b: Double) {
        append("| %s | %.3f | %.3f | %+.3f |\n".format(name, a, b, b - a))
    }

    companion object {
        private val sentinel = ThreadLocal<String>()
        private val fake: android.graphics.Bitmap = mockk(relaxed = true)
    }
}
```

- [ ] **Step 3: Wire `replay_gemma` to the test classpath**

Edit `app/build.gradle.kts`, find the existing `resources.srcDir("${rootDir}/benchmark/goldenset")` line (added in Plan 1), and add a sibling — but NOT a separate srcDir, since the classpath already covers the whole directory. Confirm `replay_gemma/*.json` is accessible via the classloader; a quick sanity test:

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.GoldenSetAbTest"
```
Expected: PASS; `app/build/reports/goldenset/ab_hermetic.md` generated with all deltas = 0 (because seed fixtures are copies).

- [ ] **Step 4: Commit**

```bash
git add benchmark/goldenset/replay_gemma/ \
        app/src/test/java/com/example/coupontracker/benchmark/GoldenSetAbTest.kt
git commit -m "test(benchmark): add Gemma vs Qwen hermetic A/B runner"
```

---

## Task 6: Document live A/B and commit placeholder reports

- [ ] **Step 1: Create `benchmark/reports/gemma_vs_qwen_hermetic.md`**

Run the hermetic A/B once locally (if Java available), copy `app/build/reports/goldenset/ab_hermetic.md` to `benchmark/reports/gemma_vs_qwen_hermetic.md`. If Java is not available, write this placeholder:

```markdown
# Gemma vs Qwen hermetic A/B

Generated by: `./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.benchmark.GoldenSetAbTest"`

## Expected shape with seed fixtures (Gemma fixtures copied from Qwen)

| metric | Qwen | Gemma | Δ |
|---|---|---|---|
| redeemCode exact | 1.000 | 1.000 | +0.000 |
| storeName normalized | 1.000 | 1.000 | +0.000 |
| expiryDate match | 1.000 | 1.000 | +0.000 |
| JSON validity | 1.000 | 1.000 | +0.000 |
| hallucination rate | 0.000 | 0.000 | +0.000 |

Deltas become meaningful once `replay_gemma/*.json` is overwritten from a
live on-device run (see `gemma_vs_qwen_live.md`).
```

- [ ] **Step 2: Create `benchmark/reports/gemma_vs_qwen_live.md`**

```markdown
# Gemma vs Qwen live A/B (on-device)

Not yet generated. Procedure:

1. Ship a debug build with `ModelStrategyConfig.setModeFor(EXPERIMENT, TEXT_GEMMA)`.
2. Run the benchmark with `-PcouponBenchmark=live -PmodelRole=EXPERIMENT`
   to drive Gemma, and separately with `-PmodelRole=DEFAULT` for Qwen.
3. For each sample, copy the canonical JSON from the device into
   `benchmark/goldenset/replay_gemma/<id>.json` (Gemma) and
   `benchmark/goldenset/replay/<id>.json` (Qwen).
4. Re-run `GoldenSetAbTest`.
5. Copy `app/build/reports/goldenset/ab_hermetic.md` over
   `benchmark/reports/gemma_vs_qwen_hermetic.md`.
6. Measure latency and memory on-device separately; append to this file.

Decision criteria:
- If Gemma redeemCode-accuracy ≥ Qwen and latency within 1.3× → promote
  Gemma to DEFAULT.
- If Gemma wins on one metric but loses on another → keep as EXPERIMENT,
  drive via feature flag, collect field data.
- If Gemma trails by ≥ 5 percentage points on any accuracy metric → drop.
```

- [ ] **Step 3: Commit**

```bash
git add benchmark/reports/gemma_vs_qwen_hermetic.md \
        benchmark/reports/gemma_vs_qwen_live.md
git commit -m "docs(benchmark): commit Gemma vs Qwen A/B placeholders"
```

---

## Self-Review

1. **Spec coverage:** Phase 5 text-mode A/B — adapter (Tasks 2–4), hermetic benchmark (Task 5), live procedure (Task 6). Gemma vision mode is out-of-scope; it belongs in Plan 5.
2. **Placeholder scan:** Live benchmark document explicitly labelled "Not yet generated". No TODOs in code.
3. **Type consistency:** `GemmaRuntime`, `GemmaTextCouponModel`, `ModelMode.TEXT_GEMMA`, `ReplayCouponModel`, `MetricsCalculator` consistent.
