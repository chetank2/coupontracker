# Android Refactor Guide

## Package Ownership

- `ui/home`, `ui/details`, `ui/review`, `ui/settings`, `ui/modelsettings`,
  `ui/scanner`: feature UI packages. Move these one feature at a time.
- `domain/usecase`: orchestration seams for UI and workers. Use cases may wrap
  current data models until the Room entity/domain split is done.
- `data/db`: future Room home. Do not move `Coupon` here until `CouponEntity`
  and mapper work is implemented in a dedicated migration-safe pass.
- `extraction/merge`: pure merge/dedupe helpers.
- `extraction/validation`: guardrails and confidence policies.

## Safe Move Order

1. Pure helpers and adapters.
2. Domain use cases.
3. Compose-only leaf UI screens and their ViewModels.
4. Scanner UI state models.
5. Scanner ViewModels and extraction orchestration.
6. Room entity/domain split.
7. Legacy fragments and XML navigation.

Run `:app:compileDebugKotlin` or `:app:testDebugUnitTest` after every slice.

