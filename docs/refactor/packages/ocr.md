# OCR Package

## Problem

OCR code is split between `ocr`, `util`, extraction services, and scanner
ViewModels. OCR should provide text and evidence, not decide final coupon fields.

## Target Structure

`extraction/ocr` owns OCR engine interfaces, engine adapters, fallback
predicates, result merging, boxed text, text cleanup, and OCR evidence models.
Field extraction stays in `extraction/rules` and `extraction/pipeline`.

## Solution

Move `ocr/*` and OCR-specific `util/*` helpers behind a small OCR service
contract. Keep engine-specific details hidden. Return raw text, normalized text,
bounding boxes, confidence, and engine provenance so downstream validation can
reason about evidence.

## Files

Current files include `ocr/OcrEngine.kt`, `MlKitOcrEngine.kt`,
`TesseractOcrEngine.kt`, `OcrCoordinator.kt`, `OcrMerger.kt`,
`OcrResultProcessor.kt`, `util/MultiEngineOCR.kt`, `util/OcrTextCleaner.kt`,
`util/OcrChromeFilter.kt`, and `util/CouponCardOcrNormalizer.kt`.

## Tests

Test fallback decisions, merge behavior, text cleanup, boxed OCR normalization,
empty OCR, and placeholder rejection.

## Risks

OCR movement can change capture behavior if callers start receiving cleaned text
without raw evidence. Engine initialization can also break if Android context or
TessData setup is moved incorrectly.

## Definition Of Done

OCR has one package contract, callers receive evidence-rich results, field logic
is outside OCR, and engine fallback behavior is tested.

