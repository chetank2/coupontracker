# ExtractCoupon Use Case

## Problem

Extraction is currently reachable from scanner ViewModels, image processors,
input managers, and multi-coupon services. The flow needs a single domain entry
point that preserves OCR-first and crop-first behavior.

## Target Structure

`domain/usecase/ExtractCouponUseCase` accepts image input, optional crop input,
capture metadata, and scan mode. It delegates orchestration to
`extraction/pipeline` and returns a review-ready domain result with fields,
confidence, evidence, and provenance.

## Solution

Keep the use case thin. It should select the correct pipeline entry point,
enforce that card crops win over full screenshots, and convert pipeline results
into domain state. It should not run OCR, parse fields, load models, or persist
coupons directly.

## Files

Current files include `domain/usecase/ExtractCouponUseCase.kt`,
`ui/viewmodel/ScannerViewModel.kt`, `util/CouponInputManager.kt`,
`util/ImageProcessor.kt`, `extraction/capture/OcrFirstCouponExtractor.kt`,
`extraction/multi/CouponRegionPipeline.kt`, and future
`extraction/pipeline/*`.

## Tests

Use fake pipeline dependencies to assert routing for single image, selected crop,
multi-coupon crops, OCR failure, and low-confidence review paths.

## Risks

Moving too much logic into the use case would create a second pipeline. Missing
crop priority would regress multi-coupon extraction.

## Definition Of Done

All scanner/capture callers use the use case, crop-first routing is tested, and
the use case returns reviewable results without persistence side effects.

