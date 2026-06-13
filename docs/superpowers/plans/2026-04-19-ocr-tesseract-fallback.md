# OCR Policy: Tesseract as Fallback (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the OCR policy explicit: MLKit is primary; Tesseract runs only when MLKit's output fails a concrete set of fallback predicates (too little text, missing code-like regions, missing date candidates, low confidence). Merge the two outputs with a defined precedence so downstream extraction sees one OCR result.

**Architecture:** Today `OcrModule` binds `MlKitOcrEngine` as the sole `OcrEngine`; `TesseractOcrEngine` exists but is not wired into the Hilt graph. This plan introduces `OcrCoordinator` — a new `@Singleton` that depends on both engines and exposes the same `OcrEngine` shape. The coordinator runs MLKit first, evaluates `OcrFallbackPredicate`s against the result, and, if any predicate fires, runs Tesseract and merges. `OcrModule` is rewired so `OcrEngine` resolves to `OcrCoordinator` in production; Tesseract remains available for direct injection where needed (e.g., verification tooling).

**Tech Stack:** Kotlin 1.9, Hilt 2.48, MLKit TextRecognizer (already in deps), Tesseract4Android (already in deps), JUnit 4, MockK.

---

## Pre-flight

- `OcrEngine` interface is at `app/src/main/kotlin/com/example/coupontracker/ocr/OcrEngine.kt`.
- `MlKitOcrEngine` and `TesseractOcrEngine` both implement it.
- `OcrModule` currently binds only MLKit; the plan widens the module.
- `OcrResultProcessor` produces `OcrTextSpan` lists that the rest of the pipeline already understands — merging stays at that representation.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/ocr/OcrFallbackPredicate.kt` — interface + five concrete predicates (text-length, code-region, date-region, confidence, disagreement).
- `app/src/main/kotlin/com/example/coupontracker/ocr/OcrFallbackReason.kt` — sealed class the coordinator emits to telemetry.
- `app/src/main/kotlin/com/example/coupontracker/ocr/OcrCoordinator.kt` — primary engine; orchestrates MLKit + optional Tesseract.
- `app/src/main/kotlin/com/example/coupontracker/ocr/OcrMerger.kt` — pure function merging MLKit + Tesseract span lists.
- `app/src/test/java/com/example/coupontracker/ocr/OcrFallbackPredicateTest.kt`
- `app/src/test/java/com/example/coupontracker/ocr/OcrCoordinatorTest.kt`
- `app/src/test/java/com/example/coupontracker/ocr/OcrMergerTest.kt`

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/di/OcrModule.kt` — bind `OcrCoordinator` as the `@Singleton OcrEngine`; keep explicit `MlKitOcrEngine` and `TesseractOcrEngine` providers for the coordinator to inject.

---

## Task 1: Add fallback-reason enum + predicates

**Files:**
- Create `OcrFallbackReason.kt`
- Create `OcrFallbackPredicate.kt`

- [ ] **Step 1: Create reason enum**

```kotlin
package com.example.coupontracker.ocr

enum class OcrFallbackReason {
    /** MLKit returned fewer than MIN_TEXT_CHARS useful characters. */
    TOO_LITTLE_TEXT,
    /** No token looks like a coupon code (uppercase alnum run ≥ 4 chars). */
    NO_CODE_REGION,
    /** No token parses as a date or date-ish phrase. */
    NO_DATE_REGION,
    /** Aggregate recognition confidence below threshold. */
    LOW_CONFIDENCE,
    /** Never fires; sentinel for "no fallback needed". */
    NONE
}
```

- [ ] **Step 2: Create predicate interface and implementations**

```kotlin
package com.example.coupontracker.ocr

import java.util.regex.Pattern

/**
 * Given an MLKit OCR result, returns the reason Tesseract should run as
 * fallback, or `NONE` if MLKit was sufficient.
 */
fun interface OcrFallbackPredicate {
    fun evaluate(primary: OcrResult): OcrFallbackReason
}

data class OcrResult(
    val text: String,
    val spans: List<OcrTextSpan>,
    val meanConfidence: Float
)

object OcrFallbackPredicates {

    const val MIN_TEXT_CHARS = 20
    const val MIN_CONFIDENCE = 0.55f
    private val CODE_LIKE = Pattern.compile("\\b[A-Z0-9]{4,}\\b")
    private val DATE_LIKE = Pattern.compile(
        "\\b\\d{1,2}[/\\-\\s](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|\\d{1,2})[/\\-\\s]\\d{2,4}\\b",
        Pattern.CASE_INSENSITIVE
    )

    val TOO_LITTLE_TEXT = OcrFallbackPredicate { r ->
        if (r.text.trim().length < MIN_TEXT_CHARS) OcrFallbackReason.TOO_LITTLE_TEXT
        else OcrFallbackReason.NONE
    }

    val NO_CODE_REGION = OcrFallbackPredicate { r ->
        if (!CODE_LIKE.matcher(r.text).find()) OcrFallbackReason.NO_CODE_REGION
        else OcrFallbackReason.NONE
    }

    val NO_DATE_REGION = OcrFallbackPredicate { r ->
        if (!DATE_LIKE.matcher(r.text).find()) OcrFallbackReason.NO_DATE_REGION
        else OcrFallbackReason.NONE
    }

    val LOW_CONFIDENCE = OcrFallbackPredicate { r ->
        if (r.meanConfidence < MIN_CONFIDENCE) OcrFallbackReason.LOW_CONFIDENCE
        else OcrFallbackReason.NONE
    }

    /** In order of severity — first non-NONE wins. */
    val DEFAULT_CHAIN: List<OcrFallbackPredicate> = listOf(
        TOO_LITTLE_TEXT,
        LOW_CONFIDENCE,
        NO_CODE_REGION,
        NO_DATE_REGION
    )
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ocr/OcrFallbackReason.kt \
        app/src/main/kotlin/com/example/coupontracker/ocr/OcrFallbackPredicate.kt
git commit -m "feat(ocr): add OCR fallback predicates and reason enum"
```

---

## Task 2: Test predicates

**Files:** `OcrFallbackPredicateTest.kt`.

- [ ] **Step 1: Write the tests**

```kotlin
package com.example.coupontracker.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrFallbackPredicateTest {

    private fun result(text: String, conf: Float = 0.9f) = OcrResult(text, emptyList(), conf)

    @Test
    fun `short text triggers TOO_LITTLE_TEXT`() {
        assertEquals(OcrFallbackReason.TOO_LITTLE_TEXT,
            OcrFallbackPredicates.TOO_LITTLE_TEXT.evaluate(result("hi")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.TOO_LITTLE_TEXT.evaluate(
                result("Flipkart Big Saving Days code FLIP100")))
    }

    @Test
    fun `missing code triggers NO_CODE_REGION`() {
        assertEquals(OcrFallbackReason.NO_CODE_REGION,
            OcrFallbackPredicates.NO_CODE_REGION.evaluate(result("just prose no codes here")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.NO_CODE_REGION.evaluate(result("use SAVE50 today")))
    }

    @Test
    fun `missing date triggers NO_DATE_REGION`() {
        assertEquals(OcrFallbackReason.NO_DATE_REGION,
            OcrFallbackPredicates.NO_DATE_REGION.evaluate(result("SAVE50 forever")))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.NO_DATE_REGION.evaluate(result("valid till 31 Dec 2026")))
    }

    @Test
    fun `low confidence fires`() {
        assertEquals(OcrFallbackReason.LOW_CONFIDENCE,
            OcrFallbackPredicates.LOW_CONFIDENCE.evaluate(result("any text", conf = 0.3f)))
        assertEquals(OcrFallbackReason.NONE,
            OcrFallbackPredicates.LOW_CONFIDENCE.evaluate(result("any text", conf = 0.9f)))
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/ocr/OcrFallbackPredicateTest.kt
git commit -m "test(ocr): cover OCR fallback predicates"
```

---

## Task 3: Implement `OcrMerger`

**Files:**
- Create `OcrMerger.kt` + `OcrMergerTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.ocr

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMergerTest {

    private fun span(text: String, conf: Float, y: Float): OcrTextSpan =
        OcrTextSpan(text = text, bounds = RectF(0f, y, 100f, y + 20f), confidence = conf)

    @Test
    fun `primary spans kept when no overlap`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("AJIO", 0.9f, 0f)),
            secondary = listOf(span("SAVE50", 0.8f, 30f))
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `overlapping spans prefer higher confidence`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("AJ10", 0.5f, 0f)),       // misread from MLKit
            secondary = listOf(span("AJIO", 0.9f, 2f))      // better from Tesseract
        )
        assertEquals(1, merged.size)
        assertEquals("AJIO", merged.single().text)
    }

    @Test
    fun `overlap heuristic uses vertical proximity within 10px`() {
        val merged = OcrMerger.merge(
            primary = listOf(span("line1", 0.9f, 0f)),
            secondary = listOf(span("line1 alt", 0.95f, 50f))  // far away → no overlap
        )
        assertEquals(2, merged.size)
    }
}
```

- [ ] **Step 2: Implement `OcrMerger`**

```kotlin
package com.example.coupontracker.ocr

/**
 * Merges two OCR span lists. Primary (MLKit) is the base; for each secondary
 * (Tesseract) span, we either add it (if no primary span covers the same
 * vertical band within 10px) or replace a lower-confidence primary span.
 *
 * Vertical-band overlap is a coarse proxy for "same text region" since OCR
 * engines occasionally differ in horizontal tokenisation.
 */
object OcrMerger {

    const val VERTICAL_OVERLAP_PX = 10f

    fun merge(primary: List<OcrTextSpan>, secondary: List<OcrTextSpan>): List<OcrTextSpan> {
        val out = primary.toMutableList()
        for (sec in secondary) {
            val overlapIndex = out.indexOfFirst { isOverlap(it.bounds, sec.bounds) }
            if (overlapIndex < 0) {
                out += sec
            } else if (sec.confidence > out[overlapIndex].confidence) {
                out[overlapIndex] = sec
            }
        }
        return out
    }

    private fun isOverlap(a: android.graphics.RectF, b: android.graphics.RectF): Boolean {
        val midA = (a.top + a.bottom) / 2f
        val midB = (b.top + b.bottom) / 2f
        return kotlin.math.abs(midA - midB) <= VERTICAL_OVERLAP_PX
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.ocr.OcrMergerTest"
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ocr/OcrMerger.kt \
        app/src/test/java/com/example/coupontracker/ocr/OcrMergerTest.kt
git commit -m "feat(ocr): merge MLKit and Tesseract spans by vertical band"
```

---

## Task 4: Implement `OcrCoordinator`

**Files:** Create `OcrCoordinator.kt` + `OcrCoordinatorTest.kt`.

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCoordinatorTest {

    private val bitmap = mockk<Bitmap>(relaxed = true)

    private fun span(t: String) =
        OcrTextSpan(t, RectF(0f, 0f, 10f, 10f), 0.9f)

    @Test
    fun `MLKit result passes predicates so Tesseract never runs`() = runBlocking {
        val mlkit = mockk<MlKitOcrEngine>()
        coEvery { mlkit.recognize(bitmap) } returns "SAVE50 expires 01 Jun 2026 at Flipkart"
        coEvery { mlkit.recognizeWithBoxes(bitmap) } returns listOf(span("SAVE50"))

        val tesseract = mockk<TesseractOcrEngine>()
        val coordinator = OcrCoordinator(mlkit, tesseract, OcrFallbackPredicates.DEFAULT_CHAIN)

        val spans = coordinator.recognizeWithBoxes(bitmap)
        assertEquals(1, spans.size)
        coVerify(exactly = 0) { tesseract.recognize(any()) }
    }

    @Test
    fun `short MLKit text triggers Tesseract and merges`() = runBlocking {
        val mlkit = mockk<MlKitOcrEngine>()
        coEvery { mlkit.recognize(bitmap) } returns "hi"
        coEvery { mlkit.recognizeWithBoxes(bitmap) } returns listOf(span("hi"))

        val tesseract = mockk<TesseractOcrEngine>()
        coEvery { tesseract.recognize(bitmap) } returns "Flipkart SAVE50 01 Jun 2026"
        coEvery { tesseract.recognizeWithBoxes(bitmap) } returns listOf(span("Flipkart"))

        val coordinator = OcrCoordinator(mlkit, tesseract, OcrFallbackPredicates.DEFAULT_CHAIN)

        val spans = coordinator.recognizeWithBoxes(bitmap)
        assertTrue("Tesseract span must be merged in", spans.any { it.text == "Flipkart" })
        coVerify(exactly = 1) { tesseract.recognizeWithBoxes(any()) }
    }

    @Test
    fun `isReady is true if MLKit is ready`() {
        val mlkit = mockk<MlKitOcrEngine>().also { io.mockk.every { it.isReady() } returns true }
        val tesseract = mockk<TesseractOcrEngine>().also { io.mockk.every { it.isReady() } returns false }
        val coordinator = OcrCoordinator(mlkit, tesseract, emptyList())
        assertTrue(coordinator.isReady())
    }
}
```

- [ ] **Step 2: Implement `OcrCoordinator`**

```kotlin
package com.example.coupontracker.ocr

import android.graphics.Bitmap
import com.example.coupontracker.util.ExtractionTelemetryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary OcrEngine. Runs MLKit first; if any fallback predicate fires, also
 * runs Tesseract and merges the two span lists. The merged list is used as
 * the downstream OCR input; `recognize()` returns the flattened text.
 */
@Singleton
class OcrCoordinator @Inject constructor(
    private val primary: MlKitOcrEngine,
    private val secondary: TesseractOcrEngine,
    private val predicates: List<OcrFallbackPredicate>,
    private val telemetry: ExtractionTelemetryService? = null
) : OcrEngine {

    /** Hilt-friendly secondary constructor using the default predicate chain. */
    @Inject
    constructor(
        primary: MlKitOcrEngine,
        secondary: TesseractOcrEngine,
        telemetry: ExtractionTelemetryService
    ) : this(primary, secondary, OcrFallbackPredicates.DEFAULT_CHAIN, telemetry)

    override suspend fun recognize(bitmap: Bitmap): String {
        val spans = recognizeWithBoxes(bitmap)
        return spans.joinToString("\n") { it.text }
    }

    override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> {
        val primaryText = primary.recognize(bitmap)
        val primarySpans = primary.recognizeWithBoxes(bitmap)
        val meanConfidence = if (primarySpans.isEmpty()) 0f
            else primarySpans.sumOf { it.confidence.toDouble() }.toFloat() / primarySpans.size

        val reason = predicates
            .map { it.evaluate(OcrResult(primaryText, primarySpans, meanConfidence)) }
            .firstOrNull { it != OcrFallbackReason.NONE }
            ?: OcrFallbackReason.NONE

        if (reason == OcrFallbackReason.NONE) {
            telemetry?.recordOcrFallback(reason.name, triggered = false)
            return primarySpans
        }

        telemetry?.recordOcrFallback(reason.name, triggered = true)
        val secondarySpans = runCatching { secondary.recognizeWithBoxes(bitmap) }
            .getOrElse {
                telemetry?.recordOcrFallbackFailure(reason.name, it.javaClass.simpleName)
                return primarySpans
            }
        return OcrMerger.merge(primarySpans, secondarySpans)
    }

    override fun isReady(): Boolean = primary.isReady()

    override fun release() {
        runCatching { primary.release() }
        runCatching { secondary.release() }
    }
}
```

If `ExtractionTelemetryService` does not already expose `recordOcrFallback(name, triggered)` and `recordOcrFallbackFailure(name, exceptionName)`, add the two suspend-or-regular methods as thin wrappers over whatever counter API the service already has. Locate the class at `app/src/main/kotlin/com/example/coupontracker/util/ExtractionTelemetryService.kt` — read it first to understand the existing pattern (likely `incrementCounter(name, tags)`).

- [ ] **Step 3: Run the coordinator tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.ocr.OcrCoordinatorTest"
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ocr/OcrCoordinator.kt \
        app/src/test/java/com/example/coupontracker/ocr/OcrCoordinatorTest.kt \
        app/src/main/kotlin/com/example/coupontracker/util/ExtractionTelemetryService.kt
git commit -m "feat(ocr): add OcrCoordinator with MLKit primary and Tesseract fallback"
```

(If you didn't need to touch `ExtractionTelemetryService.kt`, drop it from the staging line.)

---

## Task 5: Rewire `OcrModule`

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/di/OcrModule.kt`.

- [ ] **Step 1: Update the module**

Replace its body (after imports) with:

```kotlin
/**
 * Hilt module for OCR. Production binds OcrEngine → OcrCoordinator, which
 * orchestrates MlKitOcrEngine (primary) and TesseractOcrEngine (fallback).
 * Direct-injection sites that need one engine specifically can still inject
 * MlKitOcrEngine or TesseractOcrEngine by concrete type.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(coordinator: OcrCoordinator): OcrEngine
}
```

Also ensure the file imports `OcrCoordinator`. Remove any prior `@Provides` for `MlKitOcrEngine` as an `OcrEngine` — Hilt will still provide it by concrete type because `MlKitOcrEngine` is `@Inject constructor(...)`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/di/OcrModule.kt
git commit -m "refactor(di): bind OcrEngine to OcrCoordinator"
```

---

## Task 6: Smoke test the wiring on-device

**Files:** Create `app/src/androidTest/java/com/example/coupontracker/ocr/OcrCoordinatorInstrumentedTest.kt`.

- [ ] **Step 1: Create the smoke test**

```kotlin
package com.example.coupontracker.ocr

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
class OcrCoordinatorInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var engine: OcrEngine

    @Test
    fun recognizesTextFromGoldenSetFixture() = runBlocking {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val stream = ctx.assets.open("multi_coupon_fixture.png")
        val bitmap = BitmapFactory.decodeStream(stream)
        val text = engine.recognize(bitmap)
        assertTrue("expected non-empty text from coordinator", text.isNotBlank())
    }
}
```

This reuses the existing `app/src/androidTest/assets/multi_coupon_fixture.png`.

- [ ] **Step 2: Commit**

```bash
git add app/src/androidTest/java/com/example/coupontracker/ocr/OcrCoordinatorInstrumentedTest.kt
git commit -m "test(ocr): instrumented smoke test for OcrCoordinator wiring"
```

---

## Self-Review

1. **Spec coverage:** Phase 3 — MLKit primary with explicit fallback predicates → Tasks 1–2. Merge policy → Tasks 3. Coordinator → Task 4. Production wiring → Task 5. On-device smoke → Task 6.
2. **Placeholder scan:** None.
3. **Type consistency:** `OcrResult`, `OcrFallbackReason`, `OcrFallbackPredicate`, `OcrCoordinator`, `OcrMerger` defined and used consistently. `OcrTextSpan` reused from existing `com.example.coupontracker.ocr`.
