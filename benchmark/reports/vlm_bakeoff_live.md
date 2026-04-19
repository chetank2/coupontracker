# VLM bake-off (live, on-device)

Not yet generated. Procedure (after MiniCPM and Gemma Vision adapters land):

1. Set the LOW_CONFIDENCE_RETRY mode for each candidate in turn:
   ```
   modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN)
   modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_MINICPM)
   modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_GEMMA)
   ```
2. For each, run the live golden-set benchmark on a device with the model installed.
3. Copy each candidate's per-sample canonical JSON into
   `benchmark/goldenset/replay_vlm/<candidate>/<id>.json`.
4. Re-run `VlmBakeOffTest` and copy `app/build/reports/goldenset/vlm_bakeoff.md`
   over `benchmark/reports/vlm_bakeoff_hermetic.md`.

Decision criteria:
- redeemCode-accuracy delta ≥ +3 pp vs baseline → promote candidate.
- hallucination rate < 10% required.
- p95 latency within 3× Qwen text-mode required.
