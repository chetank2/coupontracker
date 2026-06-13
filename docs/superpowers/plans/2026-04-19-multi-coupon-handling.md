# Multi-Coupon Handling (Phase 7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect multiple coupon regions on one screenshot, extract each separately, deduplicate, cap the result set, and surface a review screen. Upgrade existing multi-coupon scaffolding (`HybridCouponDetector`, `TwoStageDetector`, `BatchScannerViewModel`) so it produces canonical JSON per coupon via the `CouponExtractionModel` interface.

**Architecture:** The existing `HybridCouponDetector` + `TwoStageDetector` produce bounding-box candidates. A new `CouponRegionPipeline` wraps the detector, crops each region to its own bitmap, runs OCR + `ModelSelector.select(DEFAULT).extractFromText(...)` per region, applies `CouponDeduplicator`, caps at `MAX_COUPONS_PER_SCREENSHOT`, and returns `List<CouponInfo>`. If the VLM retry path from Plan 5 is available, each region's low-confidence retry can optionally consult the VLM for segmentation questions ("which card does this code belong to?"), but the region-level extraction after segmentation still runs OCR + text LLM per region — VLM is never primary at the field level.

**Tech Stack:** Kotlin 1.9, Hilt 2.48, MLKit Object Detection (already in deps), existing region detectors, JUnit 4, MockK.

---

## Pre-flight

- Plans 1–5 landed.
- `HybridCouponDetector`, `TwoStageDetector` exist in `app/src/main/kotlin/com/example/coupontracker/ml/`.
- `BatchScannerViewModel` already handles a batch flow and calls `MultiCouponDetectorDisabledException` when the feature is off.
- `CouponExtractionModel` and `ModelSelector` exist.
- `multi_coupon_fixture.png` lives at `app/src/androidTest/assets/`.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponRegion.kt` — data class for a detected region (bounds + cropped bitmap handle).
- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponRegionPipeline.kt` — orchestrator.
- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponDeduplicator.kt` — dedup by (storeName, redeemCode, expiryDate).
- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/MultiCouponLimits.kt` — `MAX_COUPONS_PER_SCREENSHOT = 10`.
- `app/src/test/java/com/example/coupontracker/extraction/multi/CouponDeduplicatorTest.kt`
- `app/src/test/java/com/example/coupontracker/extraction/multi/CouponRegionPipelineTest.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt` — Compose screen showing per-region extraction with user-editable fields.
- `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/MultiCouponReviewViewModel.kt` — state holder.
- `benchmark/goldenset/multi/manifest.json` — multi-coupon subset manifest with expected arrays of 2+ coupons per image.
- `app/src/test/java/com/example/coupontracker/benchmark/MultiCouponGoldenSetTest.kt` — runs the region pipeline against multi-coupon fixtures.

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt` — route through the new pipeline, replace any direct detector calls.

---

## Task 1: `CouponRegion` + `MultiCouponLimits`

**Files:** Two small value objects.

- [ ] **Step 1: Create `MultiCouponLimits.kt`**

```kotlin
package com.example.coupontracker.extraction.multi

object MultiCouponLimits {
    const val MAX_COUPONS_PER_SCREENSHOT = 10
    const val MIN_REGION_AREA_PX = 50_000 // skip tiny detections
}
```

- [ ] **Step 2: Create `CouponRegion.kt`**

```kotlin
package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import android.graphics.Rect

data class CouponRegion(
    val bounds: Rect,
    val crop: Bitmap,
    val detectionConfidence: Float
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/multi/
git commit -m "feat(multi): add CouponRegion and MultiCouponLimits"
```

---

## Task 2: `CouponDeduplicator`

**Files:** Create deduplicator + test.

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.extraction.multi

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CouponDeduplicatorTest {

    private fun coupon(store: String, code: String, expiry: String): JSONObject = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, "x")
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, expiry)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray())
        put(CouponSchemaKeys.NEEDS_ATTENTION, false)
    }

    @Test
    fun `identical triple collapses`() {
        val list = listOf(
            coupon("AJIO", "SAVE50", "2026-06-01"),
            coupon("AJIO", "SAVE50", "2026-06-01")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `different codes preserved`() {
        val list = listOf(
            coupon("AJIO", "SAVE50", "2026-06-01"),
            coupon("AJIO", "SAVE60", "2026-06-01")
        )
        assertEquals(2, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `case-insensitive and whitespace-insensitive store match`() {
        val list = listOf(
            coupon("Ajio", "SAVE50", "2026-06-01"),
            coupon(" AJIO ", "SAVE50", "2026-06-01")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }

    @Test
    fun `unknown fields treated as their own group`() {
        val list = listOf(
            coupon("unknown", "unknown", "unknown"),
            coupon("unknown", "unknown", "unknown")
        )
        assertEquals(1, CouponDeduplicator.dedupe(list).size)
    }
}
```

- [ ] **Step 2: Implement `CouponDeduplicator`**

```kotlin
package com.example.coupontracker.extraction.multi

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.util.Locale

object CouponDeduplicator {

    fun dedupe(coupons: List<JSONObject>): List<JSONObject> {
        val seen = linkedMapOf<Triple<String, String, String>, JSONObject>()
        coupons.forEach { c ->
            val key = Triple(
                norm(c.optString(CouponSchemaKeys.STORE_NAME)),
                norm(c.optString(CouponSchemaKeys.REDEEM_CODE)),
                norm(c.optString(CouponSchemaKeys.EXPIRY_DATE))
            )
            seen.putIfAbsent(key, c)
        }
        return seen.values.toList()
    }

    private fun norm(v: String?): String =
        v.orEmpty().trim().lowercase(Locale.US).replace("\\s+".toRegex(), " ")
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.multi.CouponDeduplicatorTest"
```
Expected: PASS (4 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponDeduplicator.kt \
        app/src/test/java/com/example/coupontracker/extraction/multi/CouponDeduplicatorTest.kt
git commit -m "feat(multi): add CouponDeduplicator by store+code+expiry"
```

---

## Task 3: `CouponRegionPipeline`

**Files:** Create orchestrator + test.

- [ ] **Step 1: Inspect existing detector contracts**

Read `app/src/main/kotlin/com/example/coupontracker/ml/HybridCouponDetector.kt` and `TwoStageDetector.kt` to understand what shape they return (`List<Rect>`? `List<DetectionResult>`?). The pipeline depends on whichever API the class exposes — adapt the code below to match. This is a dependency worth confirming before writing the pipeline; if the detectors return richer objects than `Rect`, adjust `CouponRegion` accordingly.

- [ ] **Step 2: Implement `CouponRegionPipeline`**

```kotlin
package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ocr.OcrEngine
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CouponRegionPipeline @Inject constructor(
    private val detector: HybridCouponDetector,
    private val ocrEngine: OcrEngine,
    private val modelSelector: ModelSelector
) {

    suspend fun extract(bitmap: Bitmap): List<JSONObject> {
        val regions = detector.detect(bitmap)
            .filter { it.bounds.width() * it.bounds.height() >= MultiCouponLimits.MIN_REGION_AREA_PX }
            .take(MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT)

        if (regions.isEmpty()) {
            return listOf(extractSingle(bitmap))
        }

        val coupons = regions.map { region ->
            val crop = Bitmap.createBitmap(
                bitmap, region.bounds.left, region.bounds.top,
                region.bounds.width(), region.bounds.height()
            )
            extractSingle(crop)
        }
        return CouponDeduplicator.dedupe(coupons)
    }

    private suspend fun extractSingle(crop: Bitmap): JSONObject {
        val ocrText = ocrEngine.recognize(crop)
        val adapter = modelSelector.select(ModelRole.DEFAULT)
        val result = adapter.extractFromText(
            ocrText = ocrText,
            prompt = DEFAULT_PROMPT,
            grammar = null
        )
        return JSONObject(result.canonicalJson)
    }

    companion object {
        // Use the same prompt the single-image path uses. In practice this
        // should be pulled from PromptBuilder once it is reachable here —
        // leaving as a one-line default keeps the pipeline testable without
        // dragging the full prompt-builder graph into a unit test.
        private const val DEFAULT_PROMPT =
            "Extract the coupon fields as canonical JSON using the provided OCR text."
    }
}
```

Replace `HybridCouponDetector.detect(bitmap)` with whatever Step 1 revealed as the real API. If the detector returns `Rect`s rather than detection objects, map each to a `CouponRegion(bounds=rect, crop=..., detectionConfidence=0f)`.

- [ ] **Step 3: Write a unit test with mocked detector + OCR + selector**

```kotlin
package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelExtractionResult
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ocr.OcrEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CouponRegionPipelineTest {

    @Test
    fun `zero regions falls back to whole-image extraction`() = runBlocking {
        val detector = mockk<HybridCouponDetector>()
        every { detector.detect(any()) } returns emptyList()
        val ocr = mockk<OcrEngine>()
        coEvery { ocr.recognize(any()) } returns "AJIO SAVE50 01 Jun 2026"
        val adapter = mockk<CouponExtractionModel>()
        coEvery { adapter.extractFromText(any(), any(), any()) } returns
            ModelExtractionResult("""{"storeName":"AJIO","description":"","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}""", 10L, false)
        val selector = mockk<ModelSelector>()
        every { selector.select(ModelRole.DEFAULT) } returns adapter

        val pipeline = CouponRegionPipeline(detector, ocr, selector)
        val result = pipeline.extract(mockk<Bitmap>(relaxed = true))
        assertEquals(1, result.size)
        assertEquals("AJIO", result[0].getString("storeName"))
    }
}
```

(Add a second test for the multi-region path once the detector shape is confirmed.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponRegionPipeline.kt \
        app/src/test/java/com/example/coupontracker/extraction/multi/CouponRegionPipelineTest.kt
git commit -m "feat(multi): add CouponRegionPipeline orchestrating detector + OCR + model"
```

---

## Task 4: Multi-coupon review ViewModel + screen

**Files:**
- Create `MultiCouponReviewViewModel.kt`
- Create `MultiCouponReviewScreen.kt`

- [ ] **Step 1: Create the ViewModel**

```kotlin
package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import javax.inject.Inject

data class ReviewableCoupon(
    val id: String,
    val canonical: JSONObject,
    val accepted: Boolean = true
)

data class MultiCouponReviewUiState(
    val coupons: List<ReviewableCoupon> = emptyList(),
    val loading: Boolean = false
)

@HiltViewModel
class MultiCouponReviewViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MultiCouponReviewUiState())
    val uiState: StateFlow<MultiCouponReviewUiState> = _uiState

    fun show(coupons: List<JSONObject>) {
        val indexed = coupons.mapIndexed { i, c -> ReviewableCoupon("c$i", c) }
        _uiState.value = MultiCouponReviewUiState(coupons = indexed)
    }

    fun toggle(id: String) {
        _uiState.value = _uiState.value.copy(
            coupons = _uiState.value.coupons.map {
                if (it.id == id) it.copy(accepted = !it.accepted) else it
            }
        )
    }

    fun edit(id: String, field: String, value: String) {
        _uiState.value = _uiState.value.copy(
            coupons = _uiState.value.coupons.map {
                if (it.id == id) {
                    val json = JSONObject(it.canonical.toString()).put(field, value)
                    it.copy(canonical = json)
                } else it
            }
        )
    }

    fun accepted(): List<JSONObject> =
        _uiState.value.coupons.filter { it.accepted }.map { it.canonical }
}
```

- [ ] **Step 2: Create the Compose screen**

```kotlin
package com.example.coupontracker.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.ui.viewmodel.MultiCouponReviewViewModel

@Composable
fun MultiCouponReviewScreen(
    onConfirm: (List<org.json.JSONObject>) -> Unit,
    vm: MultiCouponReviewViewModel = hiltViewModel()
) {
    val state = vm.uiState.collectAsState().value
    Column(Modifier.fillMaxWidth()) {
        LazyColumn {
            items(state.coupons, key = { it.id }) { item ->
                Card(Modifier.padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Checkbox(checked = item.accepted, onCheckedChange = { vm.toggle(item.id) })
                        OutlinedTextField(
                            value = item.canonical.optString("storeName"),
                            onValueChange = { vm.edit(item.id, "storeName", it) },
                            label = { Text("Store") }
                        )
                        OutlinedTextField(
                            value = item.canonical.optString("redeemCode"),
                            onValueChange = { vm.edit(item.id, "redeemCode", it) },
                            label = { Text("Code") }
                        )
                        OutlinedTextField(
                            value = item.canonical.optString("expiryDate"),
                            onValueChange = { vm.edit(item.id, "expiryDate", it) },
                            label = { Text("Expiry") }
                        )
                    }
                }
            }
        }
        Button(onClick = { onConfirm(vm.accepted()) }) { Text("Save selected") }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/MultiCouponReviewViewModel.kt \
        app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt
git commit -m "feat(ui): add multi-coupon review screen"
```

---

## Task 5: Wire the pipeline into `BatchScannerViewModel`

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt`.

- [ ] **Step 1: Inspect the existing flow**

Read the file. Find where it calls `HybridCouponDetector` or `MultiCouponDetectorState.ENABLED` to begin a batch extraction. That is the insertion point.

- [ ] **Step 2: Inject `CouponRegionPipeline`**

Add `private val regionPipeline: CouponRegionPipeline` to the constructor; route the existing detection call through `regionPipeline.extract(bitmap)`. Drop any per-coupon extraction logic that duplicates what the pipeline now handles.

- [ ] **Step 3: Pass results to the review screen**

Where the ViewModel currently pushes its extracted batch to the database or a success state, first send the raw `List<JSONObject>` to `MultiCouponReviewViewModel.show(...)` and navigate to the review screen. Persist only after the user confirms.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
git commit -m "refactor(ui): route batch scanner through CouponRegionPipeline"
```

---

## Task 6: Multi-coupon golden-set fixtures + test

**Files:** extend the golden set with a multi-coupon subset and add a benchmark.

- [ ] **Step 1: Extend the generator**

Add a second generator function in `scripts/generate_golden_set.py` (or a new `scripts/generate_multi_coupon_goldenset.py`) that renders pages containing 2–4 coupons stacked vertically, with each region labeled. Output:
- `benchmark/goldenset/multi/images/<id>.png`
- `benchmark/goldenset/multi/replay/<id>.json` — the replay fixture for this case is an ARRAY of canonical JSON objects, one per expected coupon.
- `benchmark/goldenset/multi/manifest.json` — one entry per multi-coupon image with `expected: [ {canonical}, {canonical}, … ]`.

Seed with three samples: a 2-coupon page, a 3-coupon page, and a 4-coupon page mixing known and unknown brands.

- [ ] **Step 2: Create `MultiCouponGoldenSetTest.kt`**

```kotlin
package com.example.coupontracker.benchmark

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCouponGoldenSetTest {

    @Test
    fun `multi coupon manifest loads and fixtures match expected count`() = runBlocking {
        val loader = javaClass.classLoader!!
        val manifestText = loader.getResource("multi/manifest.json")?.readText()
            ?: error("multi manifest missing from classpath")
        val arr = org.json.JSONArray(manifestText)
        assertTrue("multi manifest must not be empty", arr.length() > 0)
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val id = entry.getString("id")
            val expected = entry.getJSONArray("expected")
            val replayText = loader.getResource("multi/replay/$id.json")?.readText()
                ?: error("missing multi/replay/$id.json")
            val replay = org.json.JSONArray(replayText)
            assertTrue("replay fixture must match expected count for $id",
                replay.length() == expected.length())
        }
    }
}
```

(A full end-to-end multi-coupon pipeline test requires real Bitmap crops; that lives in `androidTest`. This unit test is a structural sanity check only.)

- [ ] **Step 3: Commit**

```bash
git add scripts/generate_golden_set.py \
        benchmark/goldenset/multi/ \
        app/src/test/java/com/example/coupontracker/benchmark/MultiCouponGoldenSetTest.kt
git commit -m "feat(benchmark): seed multi-coupon golden set and structural test"
```

---

## Task 7: Integration smoke test on-device

**Files:** Create `app/src/androidTest/java/com/example/coupontracker/extraction/multi/CouponRegionPipelineInstrumentedTest.kt`.

- [ ] **Step 1: Create the smoke test**

```kotlin
package com.example.coupontracker.extraction.multi

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CouponRegionPipelineInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var pipeline: CouponRegionPipeline

    @Test
    fun extractsAtLeastOneCouponFromFixture() = runBlocking {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = BitmapFactory.decodeStream(ctx.assets.open("multi_coupon_fixture.png"))
        val coupons = pipeline.extract(bitmap)
        assertTrue("pipeline must produce at least one coupon", coupons.isNotEmpty())
        assertTrue("result must be capped", coupons.size <= MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/androidTest/java/com/example/coupontracker/extraction/multi/CouponRegionPipelineInstrumentedTest.kt
git commit -m "test(multi): on-device smoke for CouponRegionPipeline"
```

---

## Self-Review

1. **Spec coverage:** Region detection + per-region OCR + per-region extract → Task 3. Dedup → Task 2. Cap → Task 1 + used in Task 3. Review screen → Task 4. VM integration → Task 5. Golden-set support → Task 6. On-device smoke → Task 7.
2. **Placeholder scan:** `DEFAULT_PROMPT` in Task 3 is explicitly noted as a one-line stand-in and references the proper fix (`PromptBuilder`); this is a concrete deferred dependency, not a TODO, but executing developer should swap in the real prompt builder before shipping.
3. **Type consistency:** `CouponRegion`, `CouponDeduplicator`, `CouponRegionPipeline`, `MultiCouponLimits`, `MultiCouponReviewViewModel` consistent. Uses `ModelSelector.select(DEFAULT)` which Plan 2 defined.
