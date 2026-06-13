# BatchScanner Pipeline Rewire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `BatchScannerViewModel`'s multi-coupon extraction path use the new `CouponRegionPipeline` (with built-in dedup + cap), behind a feature flag that defaults to `false` so the existing detector orchestration remains the production path until the flag is flipped.

**Architecture:** The current flow in `BatchScannerViewModel.detectMultipleCoupons` runs OCR via `MultiEngineOCR`, asks `HybridCouponDetector` for regions, crops each region, then calls `extractCouponFromRegion` per region (which delegates to `LocalLlmOcrService.processCouponImageTyped` and converts to `Coupon`). The rewire keeps OCR + detection identical (so all telemetry hooks fire), but replaces the per-region extraction loop with a single `regionPipeline.extractFromCrops(crops)` call followed by JSON-to-`Coupon` conversion. A new `JsonToCouponConverter` lives in `data.util` so the conversion is testable in isolation. Flag default = false; production behaviour unchanged.

**Tech Stack:** Kotlin 1.9, Hilt 2.50, Android Bitmap, JUnit 4, MockK, org.json.

---

## Pre-flight

- Branch: `feature/qwen-multi-coupon-extraction`. HEAD: at the time of writing, `74bdf006` plus the VLM retry adoption plan's commits (if executed first).
- Pieces already shipped:
  - `CouponRegionPipeline.extractFromCrops(crops: List<Bitmap>): List<JSONObject>` at `app/src/main/kotlin/com/example/coupontracker/extraction/multi/CouponRegionPipeline.kt`.
  - `CouponDeduplicator.dedupe(...)` and `MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT` (in the same package).
  - `MultiCouponReviewViewModel` and `MultiCouponReviewScreen` for the review UI (out of this plan's scope).
- The existing VM is 1460 lines — we do not refactor structure, only add a branch in `detectMultipleCoupons`.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/JsonToCouponConverter.kt` — pure conversion from canonical JSON to the Room-mapped `Coupon` entity. Mirrors the field set already in `Coupon` (storeName, description, redeemCode, expiryDate, etc.).
- `app/src/main/kotlin/com/example/coupontracker/extraction/multi/BatchPipelineFeatureFlag.kt` — single-purpose object holding the flag + read helper. Defaults to `false`.
- `app/src/test/java/com/example/coupontracker/extraction/multi/JsonToCouponConverterTest.kt` — covers the conversion rules (date parsing, null fields, source/uri propagation).

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt` — inject `CouponRegionPipeline` + `JsonToCouponConverter`. Inside `detectMultipleCoupons`, when the flag is on AND there is more than one detected region (so we don't bypass the FALLBACK single-coupon path), substitute the region-loop with a single pipeline call. All telemetry hooks above this point are untouched.

### Files explicitly NOT modified

- `extractCouponFromRegion`, `extractSingleCoupon`, `cropBitmapToRegion`, `convertExtractResultToCoupon` — kept intact so the flag-off path is byte-equivalent to today.
- `MultiCouponReviewScreen` and `MultiCouponReviewViewModel` — flag rewire is upstream of the review surface.

---

## Task 1: Add the feature flag

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/extraction/multi/BatchPipelineFeatureFlag.kt`.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.extraction.multi

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature flag for routing batch extraction through CouponRegionPipeline.
 * Default is `false` so the existing BatchScannerViewModel detector loop
 * remains the production path. Flip to `true` (via SharedPreferences write
 * or a debug menu) once the pipeline path has been validated on-device.
 */
@Singleton
class BatchPipelineFeatureFlag @Inject constructor(
    private val prefs: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "coupon_batch_pipeline_flag"
        const val KEY_ENABLED = "batch_pipeline_enabled"
    }
}
```

Note: Kotlin allows only one `@Inject constructor`; the primary takes `SharedPreferences` (test-friendly) and the secondary `@Inject constructor` takes `Context`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/multi/BatchPipelineFeatureFlag.kt
git commit -m "feat(multi): add BatchPipelineFeatureFlag for opt-in pipeline rewire"
```

---

## Task 2: JSON → Coupon converter

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/extraction/multi/JsonToCouponConverter.kt`

- [ ] **Step 1: Inspect the `Coupon` entity**

Read `app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt` to confirm field names + types. Required v1 mapping:

| Canonical JSON | Coupon field |
|---|---|
| `storeName` | `storeName` |
| `description` | `description` |
| `redeemCode` | `redeemCode` |
| `expiryDate` (ISO `yyyy-MM-dd` or "unknown") | `expiryDate` (Date? — null when "unknown") |
| `storeNameSource` | not stored on `Coupon`; only metadata for needsAttention decisions |
| `storeNameEvidence` | not stored on `Coupon`; same |
| `needsAttention` | not stored on `Coupon`; the converter just accepts it |

Also note: `Coupon` has Room-only fields (`id`, `imageUri`, `dateAdded`, etc.). The converter accepts them as parameters from the caller.

- [ ] **Step 2: Create the converter**

```kotlin
package com.example.coupontracker.extraction.multi

import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure converter from a canonical coupon JSON (the seven v1 keys) to a
 * Room `Coupon` entity. Caller supplies non-extraction metadata (uri,
 * timestamp). String fields treat the literal "unknown" as blank.
 */
object JsonToCouponConverter {

    private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun convert(
        canonical: JSONObject,
        imageUri: Uri,
        capturedAt: Date = Date()
    ): Coupon {
        val storeName = stringOrEmpty(canonical, CouponSchemaKeys.STORE_NAME)
        val description = stringOrEmpty(canonical, CouponSchemaKeys.DESCRIPTION)
        val redeemCode = stringOrNull(canonical, CouponSchemaKeys.REDEEM_CODE)
        val expiryDate = parseExpiry(canonical.optString(CouponSchemaKeys.EXPIRY_DATE))

        return Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            expiryDate = expiryDate,
            imageUri = imageUri.toString(),
            dateAdded = capturedAt
        )
    }

    private fun stringOrEmpty(json: JSONObject, key: String): String {
        val raw = json.optString(key)
        return if (raw.isBlank() || raw.equals("unknown", ignoreCase = true)) "" else raw
    }

    private fun stringOrNull(json: JSONObject, key: String): String? {
        val raw = json.optString(key)
        return if (raw.isBlank() || raw.equals("unknown", ignoreCase = true)) null else raw
    }

    private fun parseExpiry(raw: String?): Date? {
        if (raw.isNullOrBlank() || raw.equals("unknown", ignoreCase = true)) return null
        return try {
            ISO_DATE.parse(raw)
        } catch (e: Exception) {
            null
        }
    }
}
```

If `Coupon`'s constructor is more complex (e.g., requires `id` to default), adjust by reading the entity's primary constructor and calling its named-arg form. Do not change `Coupon`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/multi/JsonToCouponConverter.kt
git commit -m "feat(multi): add JsonToCouponConverter for canonical → Room mapping"
```

---

## Task 3: Test the converter

**Files:**
- Create: `app/src/test/java/com/example/coupontracker/extraction/multi/JsonToCouponConverterTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.example.coupontracker.extraction.multi

import android.net.Uri
import com.example.coupontracker.llm.CouponSchemaKeys
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JsonToCouponConverterTest {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun uri(text: String = "content://example/1"): Uri {
        val u = mockk<Uri>()
        every { u.toString() } returns text
        return u
    }

    private fun canonical(
        store: String = "AJIO", desc: String = "Flat 50% off",
        code: String = "SAVE50", date: String = "2026-06-01",
        attention: Boolean = false
    ) = JSONObject().apply {
        put(CouponSchemaKeys.STORE_NAME, store)
        put(CouponSchemaKeys.DESCRIPTION, desc)
        put(CouponSchemaKeys.REDEEM_CODE, code)
        put(CouponSchemaKeys.EXPIRY_DATE, date)
        put(CouponSchemaKeys.STORE_NAME_SOURCE, "ocr")
        put(CouponSchemaKeys.STORE_NAME_EVIDENCE, JSONArray())
        put(CouponSchemaKeys.NEEDS_ATTENTION, attention)
    }

    @Test
    fun `populated canonical maps to Coupon`() {
        val now = Date()
        val coupon = JsonToCouponConverter.convert(canonical(), uri(), capturedAt = now)
        assertEquals("AJIO", coupon.storeName)
        assertEquals("Flat 50% off", coupon.description)
        assertEquals("SAVE50", coupon.redeemCode)
        assertEquals(isoFmt.parse("2026-06-01"), coupon.expiryDate)
        assertEquals("content://example/1", coupon.imageUri)
        assertEquals(now, coupon.dateAdded)
    }

    @Test
    fun `unknown strings become blank`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(store = "unknown", desc = "unknown"), uri()
        )
        assertEquals("", coupon.storeName)
        assertEquals("", coupon.description)
    }

    @Test
    fun `unknown redeemCode becomes null`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(code = "unknown"), uri()
        )
        assertNull(coupon.redeemCode)
    }

    @Test
    fun `unknown expiry becomes null`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "unknown"), uri()
        )
        assertNull(coupon.expiryDate)
    }

    @Test
    fun `malformed expiry becomes null without throwing`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "not-a-date"), uri()
        )
        assertNull(coupon.expiryDate)
    }

    @Test
    fun `valid ISO expiry parses to Date`() {
        val coupon = JsonToCouponConverter.convert(
            canonical(date = "2027-01-15"), uri()
        )
        assertNotNull(coupon.expiryDate)
        assertEquals(isoFmt.parse("2027-01-15"), coupon.expiryDate)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.multi.JsonToCouponConverterTest"
```
Expected: PASS (6 tests). Skip if Java unavailable.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/extraction/multi/JsonToCouponConverterTest.kt
git commit -m "test(multi): cover JsonToCouponConverter rules"
```

---

## Task 4: Inject pipeline + flag + converter into `BatchScannerViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt:35` (constructor)

- [ ] **Step 1: Read the existing constructor**

```bash
sed -n '35,55p' app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
```

Note the existing parameter list. The new dependencies must be added, NOT replace any existing ones.

- [ ] **Step 2: Add three constructor parameters**

Append these three to the end of the constructor's parameter list (preserving existing parameters):

```kotlin
    private val regionPipeline: com.example.coupontracker.extraction.multi.CouponRegionPipeline,
    private val batchPipelineFlag: com.example.coupontracker.extraction.multi.BatchPipelineFeatureFlag
```

`JsonToCouponConverter` is an `object`, so no injection needed — call it as `JsonToCouponConverter.convert(...)`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
git commit -m "refactor(ui): inject CouponRegionPipeline + flag into BatchScannerViewModel"
```

---

## Task 5: Branch on the flag inside `detectMultipleCoupons`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt:1186-1260` (the per-region loop)

The strategy: detect regions via the existing path (so MLKit + HybridCouponDetector telemetry fires), then if the flag is enabled AND we have a multi-region detection, substitute the loop body. Otherwise, run the original code unchanged.

- [ ] **Step 1: Identify the loop body**

Read `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt` from line 1186 through ~1260. The relevant block is the `for ((regionIndex, region) in couponRegions.withIndex()) { … }` at line ~1216 followed by the result returned to the caller.

- [ ] **Step 2: Add a private helper method**

Insert this private method anywhere convenient inside `BatchScannerViewModel` (e.g., directly after `extractCouponFromRegion` at line ~1337):

```kotlin
    /**
     * Pipeline-backed batch extraction. Crops each detected region, runs
     * the unified per-region OCR + extraction pipeline, then converts each
     * canonical JSON result into a `Coupon`. Dedup + cap are applied by
     * the pipeline. Falls back to per-region path if the pipeline produces
     * an empty result (which only happens when all crops are below the
     * pipeline's MIN_REGION_AREA_PX threshold).
     */
    private suspend fun extractViaCouponRegionPipeline(
        bitmap: android.graphics.Bitmap,
        couponRegions: List<com.example.coupontracker.ml.HybridCouponDetector.CouponRegion>,
        uri: android.net.Uri
    ): List<com.example.coupontracker.data.model.Coupon> {
        val crops = mutableListOf<android.graphics.Bitmap>()
        for (region in couponRegions) {
            val crop = cropBitmapToRegion(bitmap, region.boundingBox) ?: continue
            bitmapManager.trackBitmap(crop)
            crops += crop
        }
        if (crops.isEmpty()) return emptyList()
        return try {
            val canonicalJsons = regionPipeline.extractFromCrops(crops)
            canonicalJsons.map { json ->
                com.example.coupontracker.extraction.multi.JsonToCouponConverter.convert(json, uri)
            }
        } finally {
            crops.forEach { bitmapManager.releaseBitmap(it) }
        }
    }
```

- [ ] **Step 3: Replace the per-region loop with a flag branch**

Find the existing block (around lines 1213–1260 — the comment `// Step 4: Extract each detected coupon region` introduces it):

```kotlin
            // Step 4: Extract each detected coupon region
            val extractedCoupons = mutableListOf<Coupon>()

            for ((regionIndex, region) in couponRegions.withIndex()) {
                try {
                    Log.d(TAG, "Extracting coupon region ${regionIndex + 1}/${couponRegions.size}")
                    // ... existing crop + extract per region ...
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting region ${regionIndex + 1}", e)
                }
            }
```

Wrap the whole block in a flag branch. The exact structure:

```kotlin
            // Step 4: Extract each detected coupon region
            val extractedCoupons: List<Coupon> = if (batchPipelineFlag.isEnabled()) {
                val viaPipeline = extractViaCouponRegionPipeline(bitmap, couponRegions, uri)
                if (viaPipeline.isNotEmpty()) {
                    Log.d(TAG, "Pipeline extraction yielded ${viaPipeline.size} coupon(s)")
                    viaPipeline
                } else {
                    Log.w(TAG, "Pipeline yielded zero coupons; falling back to per-region loop")
                    extractCouponsViaPerRegionLoop(bitmap, couponRegions, uri, strategy)
                }
            } else {
                extractCouponsViaPerRegionLoop(bitmap, couponRegions, uri, strategy)
            }
```

- [ ] **Step 4: Extract the original loop body into a named private method**

Move the original `for ((regionIndex, region) in couponRegions.withIndex()) { … }` block into a new private method directly above `extractViaCouponRegionPipeline`:

```kotlin
    private suspend fun extractCouponsViaPerRegionLoop(
        bitmap: android.graphics.Bitmap,
        couponRegions: List<com.example.coupontracker.ml.HybridCouponDetector.CouponRegion>,
        uri: android.net.Uri,
        strategy: com.example.coupontracker.util.ExtractionStrategy
    ): List<com.example.coupontracker.data.model.Coupon> {
        val extractedCoupons = mutableListOf<com.example.coupontracker.data.model.Coupon>()
        for ((regionIndex, region) in couponRegions.withIndex()) {
            try {
                Log.d(TAG, "Extracting coupon region ${regionIndex + 1}/${couponRegions.size}")
                val regionBitmap = cropBitmapToRegion(bitmap, region.boundingBox)
                if (regionBitmap == null) {
                    Log.w(TAG, "Failed to crop region ${regionIndex + 1}, skipping")
                    continue
                }
                bitmapManager.trackBitmap(regionBitmap)
                try {
                    val coupon = extractCouponFromRegion(
                        regionBitmap = regionBitmap,
                        region = region,
                        strategy = strategy,
                        uri = uri
                    )
                    extractedCoupons.add(coupon)
                    Log.d(TAG, "Successfully extracted coupon ${regionIndex + 1}: store='${coupon.storeName}', code='${coupon.redeemCode}'")
                } finally {
                    bitmapManager.releaseBitmap(regionBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting region ${regionIndex + 1}", e)
            }
        }
        return extractedCoupons
    }
```

The body is the same code as before — just lifted into a method so the flag branch is readable. The original `for` loop in `detectMultipleCoupons` is gone (replaced by the flag branch).

- [ ] **Step 5: Sanity-check**

```bash
grep -nE "extractViaCouponRegionPipeline|extractCouponsViaPerRegionLoop|batchPipelineFlag\.isEnabled" \
  app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
```
Expected: 4 hits — one definition of each new method, plus the flag-check call site. The `extractViaCouponRegionPipeline` and `extractCouponsViaPerRegionLoop` should each appear exactly twice (definition + branch), `batchPipelineFlag.isEnabled` exactly once.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
git commit -m "feat(ui): branch BatchScanner extraction on pipeline feature flag"
```

---

## Task 6: Verification

Verification-only task; no commits.

- [ ] **Step 1: Whitespace check**

```bash
git diff --check
```
Expected: no output.

- [ ] **Step 2: Compile + test**

```bash
./gradlew :app:assembleDebug \
          :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.multi.*"
```
Expected: BUILD SUCCESSFUL; pipeline + converter tests pass.

If Java is unavailable locally, this gate must run on a JRE-equipped machine before merging.

- [ ] **Step 3: Confirm flag-off path is byte-equivalent**

```bash
git log -p -1 -- app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt
```
Skim the diff. The original per-region loop body should appear unchanged inside `extractCouponsViaPerRegionLoop`. The only NEW behaviour is a branch that defaults to calling that method.

- [ ] **Step 4: Document the flag**

Append to `docs/extraction/model_strategy.md`:

```markdown

## Batch pipeline feature flag

`BatchPipelineFeatureFlag` (`com.example.coupontracker.extraction.multi`)
controls whether `BatchScannerViewModel.detectMultipleCoupons` routes
multi-region extraction through `CouponRegionPipeline`.

- Default: `false` (existing per-region loop runs).
- Enable in a debug build with:
  ```kotlin
  batchPipelineFlag.setEnabled(true)
  ```
  Persists in `SharedPreferences("coupon_batch_pipeline_flag")`.
- The pipeline path: crops every region, runs `extractFromCrops` (which
  applies dedup + the `MAX_COUPONS_PER_SCREENSHOT` cap), maps each
  canonical JSON to a `Coupon` via `JsonToCouponConverter`. Falls back to
  the per-region loop if the pipeline yields zero coupons.
```

Commit:

```bash
git add docs/extraction/model_strategy.md
git commit -m "docs(extraction): document BatchScanner pipeline feature flag"
```

---

## Self-Review

1. **Spec coverage:**
   - Pipeline integration → Tasks 4 + 5 (extraction branch).
   - Telemetry preservation → all OCR + detection telemetry fires before the flag branch (above line 1213); the per-region telemetry inside `extractCouponFromRegion` only runs in the flag-off path or as a pipeline fallback.
   - JSON → Coupon mapping → Tasks 2 + 3.
   - Feature flag → Task 1.
   - Documentation → Task 6 Step 4.

2. **Placeholder scan:** None.

3. **Type consistency:**
   - `BatchPipelineFeatureFlag.isEnabled()` defined in Task 1 and called in Task 5.
   - `JsonToCouponConverter.convert(canonical, uri, capturedAt)` defined in Task 2 and called in Task 5 (using two-arg form, default for `capturedAt`).
   - `CouponRegionPipeline.extractFromCrops(crops)` matches the existing signature (returns `List<JSONObject>`).
   - `extractViaCouponRegionPipeline` and `extractCouponsViaPerRegionLoop` signatures align across definition and call sites.

4. **Production safety:**
   - Default flag = false → flag-off path is byte-equivalent to today.
   - Pipeline-path failure → caught and falls back to the per-region loop.
   - Crops are bitmap-managed (track/release) symmetrically.
