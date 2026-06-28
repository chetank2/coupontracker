# Code Structure Rules

## Problem

The app currently mixes UI, extraction, persistence, model adapters, preferences,
and worker orchestration across legacy packages. This makes refactor slices risky
because a simple file move can change extraction behavior, Room schema, Hilt
bindings, navigation imports, or background work.

## Target Structure

This is the target package shape, not the current full package map.

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

Current important packages that are not fully represented in the target shape:

- `data/local` and `data/model`: current Room database/DAO/entity locations.
- `llm`, `ml`, `extraction/model`, `verification`: current AI/model-related
  code. `ai/` is a future consolidation target and does not currently exist.
- `ocr`: current OCR package.
- `work` and `worker`: current WorkManager code is split between both.
- `di`, `runtime`, `util`: Hilt modules, device/runtime policy, and shared
  utilities.

## Solution

Move code by dependency direction, not by file name. UI depends on domain use
cases. Domain depends on domain models and repository contracts. Data implements
contracts and owns Room, entity mappers, and preferences. Current Room code
remains in `data/local` and `data/model` until a dedicated migration-safe split.
Extraction owns OCR text interpretation, crop-aware pipelines, validation, and
merge logic. Current model loading, cleanup, and verification adapters live
under `llm`, `ml`, `extraction/model`, and `verification`; the future `ai`
package should consolidate that code only in a dedicated slice. Workers call use
cases or small enqueue helpers instead of embedding business rules.

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
