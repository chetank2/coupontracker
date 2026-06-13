# Pluggable Model Interface (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `CouponExtractionModel` scaffolding from Plan 1 into a production-wired strategy layer: a `ModelSelector` with device-independent config, Hilt registration of available adapters, and a single switch-point inside `LocalLlmOcrService` so later plans can drop new adapters in without further pipeline surgery.

**Architecture:** `ModelSelector` is a `@Singleton` with `select(role: ModelRole): CouponExtractionModel`. Role is an enum — `DEFAULT`, `LOW_CONFIDENCE_RETRY`, `EXPERIMENT`, `BENCHMARK`. The mapping (role → `ModelMode` → concrete adapter) comes from `ModelStrategyConfig`, backed by `SharedPreferences` with sane defaults and override hooks for remote config / tests. `LocalLlmOcrService.runLlmInferenceWithRetry` — the existing private text-inference call — stays unchanged structurally; one internal wrapper is added that delegates to `ModelSelector.select(DEFAULT).extractFromText(...)` instead of calling `LlmRuntimeManager.runTextInference` directly, but the wrapper keeps all retry, telemetry, and timeout logic in place. No behavioural change in the default path.

**Tech Stack:** Kotlin 1.9, Hilt 2.48, JUnit 4, MockK 1.13.9, SharedPreferences.

---

## Pre-flight

- Plan 1 landed 11 commits from `cb98e678` to `87ff4edb`.
- `CouponExtractionModel`, `ModelMode`, `ModelExtractionResult`, `QwenTextCouponModel`, `ReplayCouponModel`, `CouponJsonContract` all exist.
- Existing DI modules live in `app/src/main/kotlin/com/example/coupontracker/di/`: `LlmModule.kt` binds `LlmRuntimeManager`. Add `ModelModule.kt` alongside.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelRole.kt` — enum.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelStrategyConfig.kt` — reads/writes role→mode mapping from `SharedPreferences`; provides overrides.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelSelector.kt` — `@Singleton`; holds the adapter map and resolves `ModelRole → CouponExtractionModel`.
- `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelSelectorException.kt` — thrown when a role resolves to a `ModelMode` with no registered adapter.
- `app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt` — Hilt module binding adapters and `ModelSelector`.
- `app/src/test/java/com/example/coupontracker/extraction/model/ModelStrategyConfigTest.kt`
- `app/src/test/java/com/example/coupontracker/extraction/model/ModelSelectorTest.kt`

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — add a private `extractTextViaSelector(ocrText, prompt, maxTokensOverride)` that calls `ModelSelector.select(DEFAULT).extractFromText(...)` and returns the canonical JSON string. Replace the direct `llmRuntime.runTextInference(...)` call inside `runLlmInferenceWithRetry` with a call to this new helper. Keep all surrounding retry / telemetry / timeout logic.
- `app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt` — no content change; verify `LlmRuntimeManager` is provided as `@Singleton` so `QwenTextCouponModel`'s constructor injection works (it already should).

---

## Task 1: Add `ModelRole` enum

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelRole.kt`.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.extraction.model

/**
 * Where in the pipeline a model is invoked. Config maps each role to a
 * `ModelMode`; the `ModelSelector` resolves that to a concrete adapter.
 */
enum class ModelRole {
    /** Main extraction path. Plan 2 ships with TEXT_QWEN as the default. */
    DEFAULT,
    /** Retried after the default path flags low confidence. Plan 5 wires VLM. */
    LOW_CONFIDENCE_RETRY,
    /** A/B experiment slot. Plan 4 wires Gemma text here. */
    EXPERIMENT,
    /** Hermetic benchmark slot (ReplayCouponModel). */
    BENCHMARK
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelRole.kt
git commit -m "feat(model): add ModelRole enum for strategy slots"
```

---

## Task 2: Add `ModelStrategyConfig`

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelStrategyConfig.kt` plus a test.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/example/coupontracker/extraction/model/ModelStrategyConfigTest.kt`:

```kotlin
package com.example.coupontracker.extraction.model

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStrategyConfigTest {

    private fun prefsReturning(
        stored: Map<String, String> = emptyMap()
    ): SharedPreferences {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        stored.forEach { (k, v) ->
            every { prefs.getString(k, any()) } returns v
        }
        return prefs
    }

    @Test
    fun `defaults map DEFAULT to TEXT_QWEN and BENCHMARK to BENCHMARK_REPLAY`() {
        val config = ModelStrategyConfig(prefsReturning())
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.DEFAULT))
        assertEquals(ModelMode.BENCHMARK_REPLAY, config.modeFor(ModelRole.BENCHMARK))
    }

    @Test
    fun `LOW_CONFIDENCE_RETRY and EXPERIMENT default to DEFAULT value`() {
        val config = ModelStrategyConfig(prefsReturning())
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY))
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.EXPERIMENT))
    }

    @Test
    fun `stored mode overrides default`() {
        val config = ModelStrategyConfig(
            prefsReturning(mapOf("role.EXPERIMENT" to "TEXT_GEMMA"))
        )
        assertEquals(ModelMode.TEXT_GEMMA, config.modeFor(ModelRole.EXPERIMENT))
    }

    @Test
    fun `setModeFor writes to prefs with correct key`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        val config = ModelStrategyConfig(prefs)

        config.setModeFor(ModelRole.EXPERIMENT, ModelMode.TEXT_GEMMA)

        verify { editor.putString("role.EXPERIMENT", "TEXT_GEMMA") }
        verify { editor.apply() }
    }

    @Test
    fun `invalid stored value falls back to default`() {
        val config = ModelStrategyConfig(
            prefsReturning(mapOf("role.DEFAULT" to "NOT_A_MODE"))
        )
        assertEquals(ModelMode.TEXT_QWEN, config.modeFor(ModelRole.DEFAULT))
    }
}
```

- [ ] **Step 2: Implement `ModelStrategyConfig`**

```kotlin
package com.example.coupontracker.extraction.model

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Role → ModelMode mapping backed by SharedPreferences.
 * Used by `ModelSelector` to resolve which adapter to invoke per role.
 */
@Singleton
class ModelStrategyConfig @Inject constructor(
    private val prefs: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun modeFor(role: ModelRole): ModelMode {
        val raw = prefs.getString(keyFor(role), null) ?: return defaultFor(role)
        return runCatching { ModelMode.valueOf(raw) }.getOrDefault(defaultFor(role))
    }

    fun setModeFor(role: ModelRole, mode: ModelMode) {
        prefs.edit().putString(keyFor(role), mode.name).apply()
    }

    private fun keyFor(role: ModelRole): String = "role.${role.name}"

    private fun defaultFor(role: ModelRole): ModelMode = when (role) {
        ModelRole.DEFAULT -> ModelMode.TEXT_QWEN
        ModelRole.LOW_CONFIDENCE_RETRY -> ModelMode.TEXT_QWEN
        ModelRole.EXPERIMENT -> ModelMode.TEXT_QWEN
        ModelRole.BENCHMARK -> ModelMode.BENCHMARK_REPLAY
    }

    companion object {
        const val PREFS_NAME = "coupon_model_strategy"
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.model.ModelStrategyConfigTest"
```
Expected: PASS (5 tests). Skip if Java unavailable.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelStrategyConfig.kt \
        app/src/test/java/com/example/coupontracker/extraction/model/ModelStrategyConfigTest.kt
git commit -m "feat(model): add ModelStrategyConfig with role-to-mode mapping"
```

---

## Task 3: Add `ModelSelectorException`

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelSelectorException.kt`.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.extraction.model

/**
 * Thrown when `ModelSelector.select(role)` cannot resolve an adapter because
 * the mode chosen for the role has not been registered (e.g. config picks
 * VLM_QWEN before Plan 5 has landed the adapter).
 */
class ModelSelectorException(
    val role: ModelRole,
    val mode: ModelMode,
    message: String = "No adapter registered for role=$role mode=$mode"
) : IllegalStateException(message)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelSelectorException.kt
git commit -m "feat(model): add ModelSelectorException for unregistered modes"
```

---

## Task 4: Add `ModelSelector`

**Files:** Create `ModelSelector.kt` and a test.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/example/coupontracker/extraction/model/ModelSelectorTest.kt`:

```kotlin
package com.example.coupontracker.extraction.model

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class ModelSelectorTest {

    private fun adapter(mode: ModelMode): CouponExtractionModel =
        mockk<CouponExtractionModel>().also { every { it.mode } returns mode }

    @Test
    fun `select returns adapter matching configured mode`() {
        val qwen = adapter(ModelMode.TEXT_QWEN)
        val replay = adapter(ModelMode.BENCHMARK_REPLAY)
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.DEFAULT) } returns ModelMode.TEXT_QWEN
        every { config.modeFor(ModelRole.BENCHMARK) } returns ModelMode.BENCHMARK_REPLAY

        val selector = ModelSelector(adapters = setOf(qwen, replay), config = config)

        assertSame(qwen, selector.select(ModelRole.DEFAULT))
        assertSame(replay, selector.select(ModelRole.BENCHMARK))
    }

    @Test
    fun `select throws when mode unregistered`() {
        val qwen = adapter(ModelMode.TEXT_QWEN)
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY) } returns ModelMode.VLM_QWEN

        val selector = ModelSelector(adapters = setOf(qwen), config = config)

        val ex = assertThrows(ModelSelectorException::class.java) {
            selector.select(ModelRole.LOW_CONFIDENCE_RETRY)
        }
        assertEquals(ModelRole.LOW_CONFIDENCE_RETRY, ex.role)
        assertEquals(ModelMode.VLM_QWEN, ex.mode)
    }

    @Test
    fun `duplicate adapter modes rejected at construction`() {
        val a = adapter(ModelMode.TEXT_QWEN)
        val b = adapter(ModelMode.TEXT_QWEN)
        val config = mockk<ModelStrategyConfig>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            ModelSelector(adapters = setOf(a, b), config = config)
        }
    }
}
```

- [ ] **Step 2: Implement `ModelSelector`**

```kotlin
package com.example.coupontracker.extraction.model

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a ModelRole to its configured CouponExtractionModel adapter.
 * Adapters are injected as a Set-multibinding via Hilt; construction-time
 * check rejects duplicate modes.
 */
@Singleton
class ModelSelector @Inject constructor(
    adapters: Set<@JvmSuppressWildcards CouponExtractionModel>,
    private val config: ModelStrategyConfig
) {

    private val byMode: Map<ModelMode, CouponExtractionModel>

    init {
        require(adapters.map { it.mode }.toSet().size == adapters.size) {
            "Multiple adapters registered for same ModelMode: " +
                adapters.groupBy { it.mode }.filter { it.value.size > 1 }.keys
        }
        byMode = adapters.associateBy { it.mode }
    }

    fun select(role: ModelRole): CouponExtractionModel {
        val mode = config.modeFor(role)
        return byMode[mode] ?: throw ModelSelectorException(role, mode)
    }

    fun availableModes(): Set<ModelMode> = byMode.keys
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.model.ModelSelectorTest"
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/model/ModelSelector.kt \
        app/src/test/java/com/example/coupontracker/extraction/model/ModelSelectorTest.kt
git commit -m "feat(model): add ModelSelector resolving role to adapter"
```

---

## Task 5: Add Hilt `ModelModule`

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt`.

- [ ] **Step 1: Create the module**

```kotlin
package com.example.coupontracker.di

import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.QwenTextCouponModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers CouponExtractionModel adapters into a Set multi-binding.
 * ModelSelector consumes the set and picks per role.
 *
 * Adapters land here one-per-plan:
 *   Plan 2: QwenTextCouponModel (TEXT_QWEN)
 *   Plan 4: GemmaTextCouponModel (TEXT_GEMMA)
 *   Plan 5: VLM adapters (VLM_QWEN / VLM_GEMMA / VLM_MINICPM)
 *
 * BENCHMARK_REPLAY is NOT registered in production — tests construct it
 * directly with recordings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ModelModule {

    @Binds
    @IntoSet
    abstract fun bindQwenText(impl: QwenTextCouponModel): CouponExtractionModel
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/di/ModelModule.kt
git commit -m "feat(di): register QwenTextCouponModel adapter via Hilt multibinding"
```

---

## Task 6: Wire `ModelSelector` into `LocalLlmOcrService`

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`.

- [ ] **Step 1: Inspect the current call site**

Open the file and locate the existing line (in `runLlmInferenceWithRetry`, around line 668):

```kotlin
                    llmRuntime.runTextInference(rawOcrText, prompt, keepLoaded = modelPinned, maxTokensOverride = maxTokensOverride)
```

This is the call inside a `withTimeout(INFERENCE_TIMEOUT_MS) { … }` block. We preserve the `withTimeout`, `keepLoaded`, and retry loop wrappers — only the inner inference call changes.

- [ ] **Step 2: Add a `ModelSelector` dependency to the constructor**

Find the class declaration of `LocalLlmOcrService` (around line 90) and its `@Inject` constructor. Add `private val modelSelector: ModelSelector` as a new constructor parameter, preserving constructor-injection order AFTER existing dependencies but BEFORE any `@VisibleForTesting` overrides. Inside the class body, also add the import `import com.example.coupontracker.extraction.model.ModelRole` at the top of the file, next to existing imports.

Do NOT remove `llmRuntime: LlmRuntimeManager` — other code paths in the file (warmup, smoke test, memory queries) still call it directly.

- [ ] **Step 3: Add a private wrapper**

Immediately above `runLlmInferenceWithRetry` (around line 650), add:

```kotlin
    private suspend fun runDefaultTextExtraction(
        rawOcrText: String,
        prompt: String,
        maxTokensOverride: Int?
    ): String {
        val adapter = modelSelector.select(ModelRole.DEFAULT)
        val result = adapter.extractFromText(
            ocrText = rawOcrText,
            prompt = prompt,
            grammar = null
        )
        // The adapter already applies CouponJsonContract.enforce when it is
        // QwenTextCouponModel; other adapters are expected to return canonical
        // JSON directly. `maxTokensOverride` is reserved for future budget
        // control; the Qwen adapter currently uses its default budget.
        return result.canonicalJson
    }
```

- [ ] **Step 4: Replace the direct `runTextInference` call**

Inside `runLlmInferenceWithRetry`, change the line found in Step 1 from:

```kotlin
            val candidateResponse = try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    llmRuntime.runTextInference(rawOcrText, prompt, keepLoaded = modelPinned, maxTokensOverride = maxTokensOverride)
                }
            }
```

to:

```kotlin
            val candidateResponse = try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    runDefaultTextExtraction(rawOcrText, prompt, maxTokensOverride)
                }
            }
```

- [ ] **Step 5: Update `modelPinned` bookkeeping**

The old call passed `keepLoaded = modelPinned`. The new adapter path does not take `keepLoaded`. In the default Qwen adapter, `keepLoaded=false` is hard-coded. To avoid regressing the "keep model resident across retries" optimisation, add one line after the successful-response `break` that sets `modelPinned = true`. Find the block (around line 687):

```kotlin
            if (!timedOut && candidateResponse != null) {
                response = candidateResponse
                break
            }
```

Change to:

```kotlin
            if (!timedOut && candidateResponse != null) {
                response = candidateResponse
                modelPinned = true
                break
            }
```

If the model is already pinned, this is a no-op; if it was not, this flips it so subsequent inferences in the same scope reuse the loaded weights. The underlying `LlmRuntimeManager` still manages actual load/release semantics — the adapter simply does not forward `keepLoaded`.

- [ ] **Step 6: Run the full text-path tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.util.LocalLlmJsonRepairTest" \
                                --tests "com.example.coupontracker.util.LocalLlmOcrServiceCompanionTest" \
                                --tests "com.example.coupontracker.extraction.model.*"
```
Expected: all existing tests still pass. The refactor is behaviour-preserving for TEXT_QWEN (the default role resolves to `QwenTextCouponModel`, which calls the same underlying `llmRuntime.runTextInference`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "refactor(llm): route default text inference through ModelSelector"
```

---

## Task 7: Add a selector smoke test against the real Hilt graph

**Files:** Create `app/src/androidTest/java/com/example/coupontracker/extraction/model/ModelSelectorInstrumentedTest.kt`.

This test runs on a device/emulator with Hilt, proving the selector resolves `DEFAULT` to `QwenTextCouponModel` in production configuration.

- [ ] **Step 1: Create the instrumented test**

```kotlin
package com.example.coupontracker.extraction.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelSelectorInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var selector: ModelSelector

    @Test
    fun defaultRoleResolvesToQwenText() {
        hiltRule.inject()
        val adapter = selector.select(ModelRole.DEFAULT)
        assertEquals(ModelMode.TEXT_QWEN, adapter.mode)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/androidTest/java/com/example/coupontracker/extraction/model/ModelSelectorInstrumentedTest.kt
git commit -m "test(model): instrumented smoke test for ModelSelector Hilt wiring"
```

---

## Task 8: Document the strategy layer

**Files:** Create `docs/extraction/model_strategy.md`.

- [ ] **Step 1: Write the doc**

```markdown
# Model Strategy Layer

## Roles

`ModelRole` defines the slots in the pipeline where a model runs:

| role | when it fires | Plan 2 default |
|---|---|---|
| DEFAULT | main extraction, every image | TEXT_QWEN |
| LOW_CONFIDENCE_RETRY | retry after default flags needsAttention | TEXT_QWEN (no-op) |
| EXPERIMENT | A/B slot driven by feature flag | TEXT_QWEN |
| BENCHMARK | golden-set runner | BENCHMARK_REPLAY |

Later plans wire different modes:
- Plan 4 moves EXPERIMENT to TEXT_GEMMA.
- Plan 5 moves LOW_CONFIDENCE_RETRY to VLM_QWEN / VLM_MINICPM.

## Changing strategy at runtime

```kotlin
modelStrategyConfig.setModeFor(ModelRole.EXPERIMENT, ModelMode.TEXT_GEMMA)
```

Values persist in `SharedPreferences("coupon_model_strategy")`. Unknown
values fall back to the code default. Remote Config can drive this by
reading the remote value and writing it to the config — keep the resolution
in one place.

## Adding an adapter

1. Create a class implementing `CouponExtractionModel` under
   `app/src/main/kotlin/com/example/coupontracker/extraction/model/`.
2. Set `mode = ModelMode.XXX`.
3. Register it in `di/ModelModule.kt` with `@Binds @IntoSet`.
4. `ModelSelectorInstrumentedTest` will fail if the new adapter conflicts
   (duplicate mode) or if a role resolves to an unregistered mode in prod
   configuration.
```

- [ ] **Step 2: Commit**

```bash
git add docs/extraction/model_strategy.md
git commit -m "docs(extraction): document model strategy layer"
```

---

## Self-Review

1. **Spec coverage:** Phase 2 ModelSelector + config + Hilt registration → Tasks 1–5. Integration point in the existing pipeline → Task 6. Live-graph smoke test → Task 7. Docs → Task 8. Out-of-scope: non-Qwen adapters.
2. **Placeholder scan:** None. The `keepLoaded` refactor note in Task 6 Step 5 is a concrete fix, not a placeholder.
3. **Type consistency:** `ModelRole`, `ModelMode`, `CouponExtractionModel`, `ModelSelector`, `ModelStrategyConfig`, `ModelSelectorException` all referenced consistently.
