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
