# Model Strategy Layer

## Roles

`ModelRole` defines the slots in the pipeline where a model runs:

| role | when it fires | Plan 2 default |
|---|---|---|
| DEFAULT | main extraction, every image | TEXT_QWEN |
| LOW_CONFIDENCE_RETRY | retry after default flags needsAttention | TEXT_QWEN (no-op) |
| EXPERIMENT | A/B slot driven by feature flag | TEXT_QWEN |
| BENCHMARK | golden-set runner | BENCHMARK_REPLAY |

Later plans wire different modes:
- Plan 4 moves EXPERIMENT to TEXT_GEMMA.
- Plan 5 moves LOW_CONFIDENCE_RETRY to VLM_QWEN / VLM_MINICPM.

## Changing strategy at runtime

```kotlin
modelStrategyConfig.setModeFor(ModelRole.EXPERIMENT, ModelMode.TEXT_GEMMA)
```

Values persist in `SharedPreferences("coupon_model_strategy")`. Unknown
values fall back to the code default. Remote Config can drive this by
reading the remote value and writing it to the config — keep the resolution
in one place.

## Adding an adapter

1. Create a class implementing `CouponExtractionModel` under
   `app/src/main/kotlin/com/example/coupontracker/extraction/model/`.
2. Set `mode = ModelMode.XXX`.
3. Register it in `di/ModelModule.kt` with `@Binds @IntoSet`.
4. `ModelSelectorInstrumentedTest` will fail if the new adapter conflicts
   (duplicate mode) or if a role resolves to an unregistered mode in prod
   configuration.

## Batch pipeline feature flag

`BatchPipelineFeatureFlag` (`com.example.coupontracker.extraction.multi`)
controls whether `BatchScannerViewModel.detectMultipleCoupons` routes
multi-region extraction through `CouponRegionPipeline`.

- Default: `false` (existing per-region loop runs).
- Enable in a debug build with:
  ```kotlin
  batchPipelineFlag.setEnabled(true)
  ```
  Persists in `SharedPreferences("coupon_batch_pipeline_flag")`.
- The pipeline path: crops every region, runs `extractFromCrops` (which
  applies dedup + the `MAX_COUPONS_PER_SCREENSHOT` cap), maps each
  canonical JSON to a `Coupon` via `JsonToCouponConverter`. Falls back to
  the per-region loop if the pipeline yields zero coupons.

## Schema-v2 feature flag

`SchemaVersionFlag` (`com.example.coupontracker.extraction`) controls
end-to-end v2 enablement. Default: `false`.

When enabled (`schemaVersionFlag.setV2Enabled(true)`):

- `PromptBuilder` lists v2 fields as optional and relaxes "no extra keys".
- `LocalLlmOcrService.enforceCanonicalFields` permits v2 keys via
  `CouponJsonContract.enforceWithV2`.
- `parseLlmResponseToCouponInfo` populates `category`, `paymentMethod`,
  `minimumPurchase`, `maximumDiscount` onto `CouponInfo` via `applyV2Fields`.
- New `Coupon` columns from `MIGRATION_13_14` (`redeemCodes`,
  `primaryRedeemCode`, `storeUrl`, `offerType`) are available for write
  once an upstream consumer maps them — that mapping is out of this
  plan's scope.

Disable to roll back instantly; v2 fields persist on rows already written
but the flag-off path stops emitting them.

PromptBuilder receives the flag through its third constructor parameter
(default `null`). `LocalLlmOcrService` does not yet thread its injected
flag into the PromptBuilder it constructs — so v2-aware prompting only
fires today when a caller constructs PromptBuilder directly with the
flag. Wiring through `LocalLlmOcrService.preparePrompt` is a small
follow-up.
