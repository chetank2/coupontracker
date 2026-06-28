# Extraction Pipeline Heuristics

## Heuristic fallback layer
The universal extractor now adds a dedicated heuristic pass that only runs when
high-confidence strategies leave specific fields blank. The pass synthesizes
field candidates from the cleaned OCR transcript and tags them with
`heuristic_*` context so downstream scoring can track their origin:

- **Expiry date** – falls back to the natural-language date parser when the
  deterministic passes miss a value, emitting the ISO-normalized date and the
  parser reason metadata.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L188-L213】
- **Cashback/amount** – captures stacked offers such as `₹200 + ₹50 cashback`
  so the scorer can reason about compound redemptions.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L215-L224】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L607-L610】
- **Coupon code** – promotes contextual regex matches (`use CODE123`) when the
  learned pattern and semantic passes fail.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L227-L235】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L612-L616】
- **Store hinting** – reuses `brandHint` plus lightweight "from/at/by" phrase
  detection to supply a plausible merchant name without reintroducing generic
  placeholders.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L239-L247】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L618-L633】
- **Description fallback** – chooses the first meaningful OCR sentence while
  filtering boilerplate such as T&C lines so the UI never surfaces `"Coupon
  offer"` by default.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L251-L259】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L635-L646】

Because the pass only executes for fields that remain unresolved after earlier
stages, it complements (rather than overrides) LLM and pattern-driven results.
The helper methods (`detectCompoundAmount`, `detectCouponCode`,
`extractStoreFromContext`, and `extractMeaningfulSnippet`) are kept alongside
the pass for quick tuning during incident response.【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L607-L647】

## Current scanner routing
Heuristic fallbacks ride on the universal extraction pipeline, but the current
Compose scanner does not branch into separate `LEGACY`, `OCR_FIRST`,
`LLM_FIRST`, and `HYBRID` scan routes. `ScannerViewModel.scanImage(...)` reads
and logs the configured strategy for telemetry, then routes through crop-first
detection with `routeDetectedCouponCrops(...)`.

Current behavior:

1. Decode the shared/uploaded image.
2. Try coupon crop detection and layout routing.
3. Run crop OCR extraction for isolated single coupons.
4. Use `MultiCouponExtractionService` for multi-region screenshots.
5. Fall back to review-safe OCR only when crop/layout isolation is unavailable
   and the screenshot is not classified as multi-coupon.

The extraction strategy setting still influences downstream services that use
`ExtractionConfig`, but it is no longer a top-level scanner switch between four
separate capture paths. Any future domain-owned scanner router should preserve
this crop-first contract and make strategy selection explicit in one place.

## Troubleshooting notes
- **Manual field removals remain respected.** When a reviewer clears or edits a
  field, `ValidatorFeedbackRecorder` records a `user_override` event and
  `UniversalExtractionService.learnFromCorrection` penalises the old candidate
  in the pattern learner. Subsequent scans stop reintroducing the removed value
  because the incorrect pattern loses weight.【F:app/src/main/kotlin/com/example/coupontracker/feedback/ValidatorFeedbackRecorder.kt†L139-L175】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L678-L735】
- **Currency detection after the fix.** `CurrencyUtils.detectSymbol` now keeps
  USD/EUR/GBP symbols instead of coercing everything to ₹, and
  `DescriptionUtils.formatCashbackDetail` reuses that symbol (plus parsed
  numeric amounts) before emitting the `Cashback:` line. If users still see INR
  defaults, confirm the raw OCR text actually lacks any currency token.【F:app/src/main/kotlin/com/example/coupontracker/data/util/CurrencyUtils.kt†L5-L60】【F:app/src/main/kotlin/com/example/coupontracker/data/util/DescriptionUtils.kt†L33-L74】
