# Scanner UI Package

## Problem

Scanner code currently carries capture UI, OCR orchestration, extraction routing,
multi-coupon handling, crop persistence, and save/verification decisions. This
is the highest-risk UI package.

## Target Structure

`ui/scanner` owns scanner screens, camera/upload state, permissions, capture
events, progress, and review navigation. Extraction work belongs to
`ExtractCouponUseCase` and `extraction/pipeline`.

## Solution

Split scanner state from extraction behavior. Scanner collects an image or crop,
calls `ExtractCouponUseCase`, shows progress/errors, and navigates to review.
It does not parse OCR text, choose field values, or save coupons directly except
through review/save use cases.

## Files

Current files include `ui/screen/ScannerScreen.kt`,
`ui/screen/BatchScannerScreen.kt`, `ui/screen/SmartCameraScreen.kt`,
`ui/screen/UnifiedCameraScreen.kt`, `ui/screen/UnifiedUploadScreen.kt`,
`ui/viewmodel/ScannerViewModel.kt`, `BatchScannerViewModel.kt`,
`UnifiedCameraViewModel.kt`, `UnifiedUploadViewModel.kt`, and camera helpers.

## Tests

Test permission state, image selected, crop result routing, extraction success,
extraction failure, multi-coupon review navigation, and no direct field parsing.

## Risks

Changing scanner can break capture. The key regression is bypassing coupon-card
crops and extracting from a full multi-card screenshot.

## Definition Of Done

Scanner owns capture UX only, extraction is use-case driven, crop-first behavior
is tested, and review/save flows are separate.

