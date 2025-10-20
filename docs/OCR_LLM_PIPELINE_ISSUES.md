# OCR ↔ LLM Pipeline Audit and Fix Plan

## How the current pipeline stitches OCR and the LLM together
- `LocalLlmOcrService.processCouponImageTyped` always begins by running on-device OCR to build the prompt that is handed to the LLM, and aborts if that OCR transcript is blank. 【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L362-L487】
- The LLM path returns structured coupon JSON when inference succeeds; otherwise `ScannerViewModel.scanWithLlmFirstPath` falls back to the universal extractor and, ultimately, the legacy OCR heuristics. 【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L390-L512】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L1594-L1788】
- The OCR-first strategy and all fallbacks reuse the same OCR transcript so deterministic passes, progressive extraction, and the learning hooks can continue processing even if the LLM response is rejected. 【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L560-L614】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L1594-L1779】

## Issues uncovered

### 1. Strategy gating keeps production stuck on OCR/legacy paths
`ExtractionConfig` exposes only `OCR_FIRST` and `LEGACY` unless the hidden "advanced" flag is toggled, so any saved `LLM_FIRST` or `HYBRID` choice is rejected during startup. In practice, the strategy resolver loads `LEGACY` for many users and never lets the progressive or LLM-first flows execute. 【F:app/src/main/kotlin/com/example/coupontracker/util/ExtractionStrategy.kt†L52-L141】

**Plan**
1. Detect real model availability during boot and enable `LLM_FIRST` / `HYBRID` when files are present instead of hiding them behind a persisted flag.
2. Allow Remote Config and Settings selections to persist without being downgraded, falling back to `LEGACY` only when the chosen strategy throws.
3. Add telemetry on strategy coercions so QA can catch unexpected downgrades quickly.

### 2. LLM inference times out before returning structured JSON
`processCouponImageTyped` runs the MiniCPM text inference under a 90 s timeout. When the runtime stalls or the model has to warm up, the coroutine times out and surfaces `ExtractResult.Failed`, immediately forcing the pipeline down to the OCR-only path. 【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L399-L512】

**Plan**
1. Warm the model proactively during app startup and reuse the handle between requests instead of deferring warmup to the first capture.
2. Stream token chunks out of `runTextInference` so long prompts do not hit the timeout; fail fast only when the runtime is unresponsive.
3. Surface richer error metadata to the validator so the app can retry the LLM path (or switch to a lighter prompt) before giving up on structured extraction.

### 3. OCR transcripts are discarded before reaching the universal extractor
All OCR-first and fallback paths collapse `MultiEngineOCR.OCRResult.Success` to `extractedInfo.values.joinToString(" ")` rather than using the raw `text` field. That replaces the cleaned transcript with heuristic guesses, preventing the progressive pipeline from seeing the real OCR output. 【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L560-L583】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L1600-L1635】【F:app/src/main/kotlin/com/example/coupontracker/util/MultiEngineOCR.kt†L35-L118】

**Plan**
1. Pass the raw `text` payload through every universal extraction call and persist it alongside heuristic fields for auditing.
2. Update `MultiCouponExtractionService`, `HybridCouponDetector`, and batch flows to do the same so the progressive passes never operate on lossy data.
3. Add regression tests that fail whenever the OCR transcript is replaced by derived fields.

### 4. Legacy `TextExtractor` heuristics remain the terminal fallback
When universal extraction or the LLM fails, `createCouponFromExtractedInfo` drops back to `TextExtractor`'s frequency/regex heuristics, which still mislabel merchants and codes on noisy receipts. 【F:app/src/main/kotlin/com/example/coupontracker/util/TextExtractor.kt†L103-L200】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L1916-L1950】

**Plan**
1. Replace the `TextExtractor` fallback with the progressive extractor’s field fusion so the same candidate scoring logic runs in every path.
2. Feed fallback coupons and their OCR transcripts into the learning APIs so repeated corrections retrain the detector instead of masking issues.
3. Gate the legacy heuristics behind a developer toggle for debugging only, ensuring production users always hit the progressive or LLM-based flows.

## Long-term safeguards
- Instrument strategy selection, LLM latency, and fallback counts in telemetry dashboards to detect regressions early.
- Maintain end-to-end tests that exercise each strategy (LLM-first, OCR-first, hybrid, legacy) so future refactors cannot silently reintroduce the same failures.
