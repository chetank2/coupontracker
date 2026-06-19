# Code Structure Rules

## Problem

The app currently mixes UI, extraction, persistence, model adapters, preferences,
and worker orchestration across legacy packages. This makes refactor slices risky
because a simple file move can change extraction behavior, Room schema, Hilt
bindings, navigation imports, or background work.

## Target Structure

```text
com.example.coupontracker/
  ui/
    home/
    details/
    review/
    settings/
    modelsettings/
    scanner/
  domain/
    model/
    repository/
    usecase/
  data/
    db/
    entity/
    mapper/
    preferences/
    repository/
  extraction/
    crop/
    ocr/
    rules/
    validation/
    merge/
    pipeline/
  ai/
    model/
    cleanup/
    verification/
  worker/
```

## Solution

Move code by dependency direction, not by file name. UI depends on domain use
cases. Domain depends on domain models and repository contracts. Data implements
contracts and owns Room, entity mappers, and preferences. Extraction owns OCR
text interpretation, crop-aware pipelines, validation, and merge logic. AI owns
model loading, cleanup, and verification adapters. Workers call use cases or
small enqueue helpers instead of embedding business rules.

## Files

Current high-risk areas include `ui/viewmodel`, `ui/screen`, `ui/fragment`,
`data/model/Coupon.kt`, `data/local/CouponDao.kt`, `util/ImageProcessor.kt`,
`util/CouponInputManager.kt`, `extraction/*`, `ocr/*`, `extraction/model/*`,
`worker/*`, and `work/*`.

## Tests

For every move, run `git diff --check`,
`:app:testDebugUnitTest`, and `:app:assembleDebug` with Java 17. Add focused
unit tests when behavior is extracted from a ViewModel, repository, or pipeline.

## Risks

Main risks are broken Hilt bindings, hidden import changes, navigation route
breakage, Room schema drift, changed extraction order, and duplicate class names
during transitional adapter phases.

## Definition Of Done

The package compiles, behavior is unchanged, imports point at the new owner,
legacy aliases are deliberate and temporary, and the moved code follows the
dependency direction above.

