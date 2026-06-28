# Android Refactor Guide

## Package Ownership

- `ui/home`, `ui/details`, `ui/review`, `ui/settings`, `ui/modelsettings`,
  `ui/scanner`: feature UI packages. Move these one feature at a time.
- `domain/usecase`: orchestration seams for UI and workers. Use cases may wrap
  current data models until the Room entity/domain split is done.
- `data/local` and `data/model`: current Room DAO/database/entity locations.
  Keep them here unless doing a dedicated migration-safe entity/domain split.
- `data/db`: future Room home. Do not move `Coupon` here until `CouponEntity`
  and mapper work is implemented in a dedicated migration-safe pass.
- `extraction/merge`: pure merge/dedupe helpers.
- `extraction/validation`: guardrails and confidence policies.
- `llm`, `ml`, `extraction/model`, `verification`: current AI/model-related
  locations. A future `ai` package may consolidate these, but it does not
  currently exist.
- `ocr`: current OCR package.
- `worker` and `work`: current WorkManager code is split; `worker` is the
  target home, while `work/CouponReminderWorker.kt` still exists.
- `di`, `runtime`, `util`: current dependency injection, device/runtime policy,
  and shared utility packages.

## Safe Move Order

1. Pure helpers and adapters.
2. Domain use cases.
3. Compose-only leaf UI screens and their ViewModels.
4. Scanner UI state models.
5. Scanner ViewModels and extraction orchestration.
6. Room entity/domain split.
7. Legacy fragments and XML navigation.

Run `:app:compileDebugKotlin` or `:app:testDebugUnitTest` after every slice.
