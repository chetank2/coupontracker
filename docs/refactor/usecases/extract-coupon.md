# ExtractCoupon Use Case

## Problem

Extraction is currently reachable from scanner ViewModels, image processors,
input managers, and multi-coupon services. The flow needs a single domain entry
point that preserves OCR-first and crop-first behavior.

## Target Structure

`domain/usecase/ExtractCouponUseCase` accepts image input, optional crop input,
capture metadata, and scan mode. Capture metadata is required evidence for
relative expiry phrases such as `expires in 10 days`; those phrases must be
calculated from the screenshot timestamp, not from scan time, unless metadata is
unavailable. It delegates orchestration to
`extraction/pipeline` and returns a review-ready domain result with fields,
confidence, evidence, and provenance.

## Current Implementation

`ExtractCouponUseCase` now exposes typed requests:

- `ExtractCouponRequest.BitmapInput` for OCR-from-bitmap extraction.
- `ExtractCouponRequest.ScopedOcrInput` for already-cropped OCR text and OCR
  blocks.

The compatibility `invoke(bitmap, imageUri, captureTimestamp)` entry still
routes to `BitmapInput`. The use case does not yet own scan-mode selection,
multi-coupon orchestration, or all scanner route execution. Those
responsibilities still live in scanner routing and extraction services.

## Solution

Keep the use case thin. It should select the correct pipeline entry point,
enforce that card crops win over full screenshots, and convert pipeline results
into domain state. It should not run OCR, parse fields, load models, or persist
coupons directly.

Expiry is a core field rule. Every extraction route must pass the same
`captureTimestamp` into OCR rules, universal extraction, progressive extraction,
model cleanup, and final validation. Any route that parses relative expiry
without this timestamp is incomplete.

## Files

Current files include `domain/usecase/ExtractCouponUseCase.kt`,
`ui/viewmodel/ScannerViewModel.kt`, `util/CouponInputManager.kt`,
`util/ImageProcessor.kt`, `extraction/capture/OcrFirstCouponExtractor.kt`,
`extraction/multi/CouponRegionPipeline.kt`, and future
`extraction/pipeline/*`.

## Tests

Current minimum tests assert the use case forwards bitmap, image URI, capture
timestamp, and scoped OCR evidence to `OcrFirstCouponExtractor`.

Target tests: use fake pipeline dependencies to assert routing for single image,
selected crop, multi-coupon crops, OCR failure, low-confidence review paths, and
relative expiry calculation from screenshot metadata.

## Risks

Moving too much logic into the use case would create a second pipeline. Missing
crop priority would regress multi-coupon extraction.

## Definition Of Done

All scanner/capture callers use the use case, crop-first routing is tested, and
the use case returns reviewable results without persistence side effects.
Relative expiry tests prove `expires in N days` uses screenshot metadata across
single, batch, model, and cleanup paths.
