# Pipeline Package

## Problem

Extraction orchestration is scattered across progressive services, universal
services, scanner fallbacks, multi-coupon services, retry runners, and utility
classes. This makes behavior hard to reason about and test.

## Target Structure

`extraction/pipeline` owns orchestration from image or crop input to canonical
extraction result. Subpackages provide crop, OCR, rules, validation, and merge
capabilities. Domain use cases call the pipeline, not individual stages.

## Solution

Define a small pipeline contract with inputs for image/crop, capture timestamp,
source URI, strategy, and optional existing OCR evidence. The pipeline executes
stages in order: crop selection, OCR, deterministic rules, validation,
optional retry/merge, dedupe, and review result mapping.

## Files

Current files include `extraction/ProgressiveExtractionService.kt`,
`extraction/MultiCouponExtractionService.kt`, `extraction/capture/OcrFirstCouponExtractor.kt`,
`extraction/multi/CouponRegionPipeline.kt`, `extraction/retry/*`,
`universal/UniversalExtractionService.kt`, `util/ImageProcessor.kt`, and
`util/CouponInputManager.kt`.

## Tests

Add pipeline tests for single crop, multi-coupon crops, OCR failure, low
confidence retry, merge conflict, dedupe, relative expiry, and provenance.

## Risks

A pipeline rewrite can accidentally reorder fallbacks. Avoid changing field
priority while moving code unless the slice explicitly includes behavior changes.

## Definition Of Done

There is one pipeline contract, domain extraction uses it, stage order is tested,
and full-screenshot multi-coupon field extraction is blocked when crops exist.

