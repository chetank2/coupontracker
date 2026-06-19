# Anti-Hardcoded Rules

## Problem

Hardcoded brands, coupon phrases, placeholder fields, model paths, thresholds,
and UI strings make the app brittle and can hide extraction failures.

## Target Structure

Configuration belongs in typed config objects, resources, repositories, feature
flags, or lexicon/rule files. Domain defaults belong in a small documented
normalization layer. UI text belongs in resources or screen state.

## Solution

- Do not add brand-specific extraction branches unless they are data-driven and
  covered by tests.
- Do not use `"Unknown Store"`, `"No description"`, `"Coupon offer"`, or similar
  placeholders as final values when OCR evidence can produce a better fallback.
- Do not hardcode model file names or directories outside model-management code.
- Do not hardcode thresholds in ViewModels; put them in extraction config or use
  cases.
- Do not duplicate date, currency, code, or store rules across screens.
- When a constant is necessary, name it for the product rule it represents and
  test boundary behavior.

## Files

Watch `util/*`, `extraction/*`, `ui/viewmodel/*`, `data/model/*`,
`ui/screen/*`, `extraction/model/*`, and model import code.

## Tests

Add regression tests for any new default, threshold, model selection rule, or
field normalization rule. Test that missing evidence produces review state
rather than confident fake data.

## Risks

Hardcoded behavior usually passes one fixture and fails broad real-world coupon
formats. It also makes cleanup appear successful while corrupting user data.

## Definition Of Done

New constants are centralized, named, tested, and not brand-specific unless the
product explicitly supports a data-driven brand rule.

