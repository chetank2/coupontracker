# feature/qwen-multi-coupon-extraction sync

The feature branch now includes the "Add regression tests for extraction confidence defaults and model guard" changes (commit 9d7b4a02). This ensures:

- Model assets are validated before loading so placeholder binaries fail fast.
- Room keeps `extractionConfidenceBreakdown` non-null through defaults and migrations.
- Progressive extraction filters the fallback store candidate "JUST".
- Robolectric coverage protects the migration, model integrity guard, and stopword heuristics.

Keeping this branch aligned with the guard changes avoids regressions when continuing Qwen multi-coupon extraction work.
