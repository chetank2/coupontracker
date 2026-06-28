# Universal Extraction Solution Rules

## Problem

Extraction has many paths: OCR-first capture, progressive extraction, universal
field detection, multi-coupon crops, deterministic rules, VLM retry, and cleanup.
The refactor must converge these paths without changing the core product rule:
crop-first OCR extraction is the source of truth for capture.

## Target Structure

```text
extraction/
  crop/        regionization, smart crop, crop persistence helpers
  ocr/         OCR interfaces, engines, merging, text cleanup
  rules/       deterministic field extraction and normalization
  validation/  field validators and evidence checks
  merge/       OCR/default plus model merge and dedupe
  pipeline/    orchestration from crop or image input to canonical result
ai/
  cleanup/     explicit model cleanup of saved/reviewed coupons
  verification/background verification adapters
```

`ai/` in this diagram is a future target package. Current model/AI-related code
still lives under `llm`, `ml`, `extraction/model`, `model`, `runtime`, and
`verification`.

## Solution

- Single coupon image: OCR crop or image, extract deterministic fields, validate,
  normalize, and return reviewable coupon state.
- Multi-coupon image: detect card regions, crop each region, run the same
  per-crop pipeline, dedupe by canonical store/code/expiry, and surface review.
- Relative expiry phrases are metadata-dependent. `expires in N days`,
  `expires in N weeks`, and similar phrases must use the screenshot capture
  timestamp as the base date through every scanner, batch, progressive,
  universal, model, and cleanup path.
- VLM/model retry may assist low-confidence fields only after OCR evidence and
  merge rules are available.
- Cleanup must be separate from capture and must preserve the original OCR text,
  image URI, confidence, and user edits.

## Files

Current inputs include `extraction/ProgressiveExtractionService.kt`,
`extraction/MultiCouponExtractionService.kt`, `extraction/multi/*`,
`extraction/region/*`, `extraction/merge/*`, `extraction/retry/*`,
`universal/*`, `util/ImageProcessor.kt`, `util/CouponInputManager.kt`, and
`util/MultiEngineOCR.kt`.

## Tests

Use fixture OCR text, fixture cropped images, and multi-coupon screenshots.
Assert crop-first routing, field provenance, dedupe behavior, and no placeholder
promotion. Include relative-expiry fixtures with historical screenshot
timestamps. Keep canary expected outputs updated only when behavior changes are
intentional and reviewed.

## Risks

The biggest risk is accidentally sending a full multi-card screenshot into field
extraction after crop detection. Other risks include model hallucination,
duplicate coupon saves, lost provenance, and inconsistent fallback order.

## Definition Of Done

There is one documented capture pipeline contract, all paths preserve crop-first
behavior, model cleanup is separate, and tests prove multi-coupon crops are the
input to per-coupon extraction.
