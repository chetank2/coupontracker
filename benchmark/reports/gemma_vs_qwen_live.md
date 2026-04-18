# Gemma vs Qwen live A/B (on-device)

Not yet generated. Procedure:

1. Ship a debug build with `ModelStrategyConfig.setModeFor(EXPERIMENT, TEXT_GEMMA)`.
2. Run the benchmark with `-PcouponBenchmark=live -PmodelRole=EXPERIMENT`
   to drive Gemma, and separately with `-PmodelRole=DEFAULT` for Qwen.
3. For each sample, copy the canonical JSON from the device into
   `benchmark/goldenset/replay_gemma/<id>.json` (Gemma) and
   `benchmark/goldenset/replay/<id>.json` (Qwen).
4. Re-run `GoldenSetAbTest`.
5. Copy `app/build/reports/goldenset/ab_hermetic.md` over
   `benchmark/reports/gemma_vs_qwen_hermetic.md`.
6. Measure latency and memory on-device separately; append to this file.

Decision criteria:
- If Gemma redeemCode-accuracy ≥ Qwen and latency within 1.3× → promote
  Gemma to DEFAULT.
- If Gemma wins on one metric but loses on another → keep as EXPERIMENT,
  drive via feature flag, collect field data.
- If Gemma trails by ≥ 5 percentage points on any accuracy metric → drop.
