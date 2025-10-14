# Coupon Field Flow Audit (Oct 2025)

## 1. Single Screenshot → `ImageProcessor`

- Entry points: `CouponInputManager.processCouponFromBitmap`, `ScannerViewModel.scanWithLegacyPath`.
- `ImageProcessor.processImage(bitmap, captureTimestamp, originalUri)` first attempts the progressive pipeline when injected `ProgressiveExtractionService` is available.
- Progressive pipeline builds a `Coupon` via `ProgressiveExtractionService.finishExtraction()` → `buildFinalResult()`.
  - `storeName`: primary from MiniCPM pass, otherwise best pattern candidate; falls back to `"Unknown Store"` if heuristics reject.
  - `description`: MiniCPM, pattern, or heuristics; final fallback is first 200 chars of OCR text with `"Coupon offer"` default.
  - `redeemCode`: MiniCPM candidate, structured patterns, heuristics.
  - `expiryDate`: MiniCPM or structured pass; normalized via `ParseDate` and `IndianDateParser`; relative dates ignore screenshot metadata until rule added.
- When progressive pipeline fails (OCR blank, exception), flow drops to legacy `ModelBasedOCRService` → `CouponInfo` which still returns `"Unknown Store"`/`"No description"` when patterns miss.

## 2. Multi-Coupon Screenshots → `MultiCouponExtractionService`

- Triggered from `CouponInputManager.processCouponFromBitmap` when screenshot classifier marks `MULTI_COUPON_APP`.
- Steps: hybrid detector segments → per-region extraction via `ProgressiveExtractionService.extractCoupon`.
- Post-processing: `handleMultiCouponResult()` simply picks the highest-confidence coupon and returns raw `Coupon` from progressive pipeline with minimal normalization.
- When `extractMultipleCoupons` returns multiple coupons, only the top candidate is surfaced to `CouponInputManager` today; batch save happens via `ScannerViewModel` multi-flow.

## 3. `ScannerViewModel` Paths

- Strategies (`LEGACY`, `LLM_FIRST`, `OCR_FIRST`, `HYBRID`) each funnel to helper builders:
  - `buildCouponFromLlmResult()` converts `CouponInfo` into `Coupon`, defaulting description to `"Extracted via LLM"` and store to `"Unknown Store"` when blank.
  - `UniversalExtractionService` and two-stage detector paths similarly yield `Coupon` objects with minimal field validation.
- Multi-coupon actions (`processCouponBatch`, `processSingleCoupon`) rely on `createCouponFromInstance()` which uses extracted map fields and again defaults missing store/description to placeholders.

## 4. Batch Upload (`CouponInputManager.processCouponsInBatch*`)

- Loops over URIs, invoking single-image path for each; no aggregation or validation after `Coupon` creation.
- Failure per image logs error but continues; partial successes return list of raw `Coupon`s.

## Gaps vs Required Rules

1. **Correct Store Name**: multiple builders still allow `"Unknown Store"` even when text contains brand (due to aggressive heuristics or missing post-pass).
2. **Correct Coupon Code**: `redeemCode` may remain null when code present but filtered, no final pass to re-scan description text.
3. **Expiry Date with Relative Logic**: relative phrases handled inconsistently; metadata timestamp ignored after legacy fallback.
4. **Description = Residual Content**: placeholders like `"No description"` or `"Extracted via LLM"` survive instead of using remaining OCR text.

## Next Actions

- Introduce a shared field post-processor enforcing rules before returning any `Coupon`.
- Feed capture timestamp into progressive + legacy fallbacks so relative expiry calculations succeed.
- Relax heuristics so legitimate short brand names survive but still filter UI chrome.

