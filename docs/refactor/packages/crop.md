# Crop Package

## Problem

Crop and region logic is spread across camera, extraction regionizers, scanner
ViewModels, and image utilities. Crop behavior is product-critical because
multi-coupon extraction must operate on coupon-card crops.

## Target Structure

`extraction/crop` owns smart crop, coupon regionization, crop bounds validation,
crop persistence helpers, and crop result models. Camera UI may request crops
but does not own crop rules.

## Solution

Move crop logic from camera/extraction/util into a crop package with explicit
inputs and outputs. Every crop result should include bitmap or URI, bounds,
source image reference, confidence, and reason. The pipeline must prefer crop
input over full screenshot input for field extraction.

## Files

Current files include `camera/SmartCropProcessor.kt`,
`camera/LiveTextDetectionAnalyzer.kt`, `extraction/region/CouponRegionizer.kt`,
`CouponRegionizerConfig.kt`, `extraction/multi/CouponRegion.kt`,
`ui/viewmodel/ScannerViewModel.kt` crop persistence helpers, and image utilities.

## Tests

Test bounds clamping, global crop configuration, multi-card region detection,
crop persistence failure, and crop-first routing into extraction.

## Risks

Invalid crop bounds can crash bitmap operations. Losing source URI or bounds
metadata makes review and debugging harder.

## Definition Of Done

Crop behavior is centralized, all multi-coupon field extraction receives card
crops, and crop metadata is preserved through review.

