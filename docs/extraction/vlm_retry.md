# VLM retry runbook

## When retry fires

`VlmRetryEvaluator` checks the default text-path output against:
- `redeemCode in {"", "unknown"}` → RedeemCodeMissing
- `storeName in {"", "unknown"}` → StoreNameUnknown
- `expiryDate in {"", "unknown"}` → ExpiryMissing
- `needsAttention == true` → NeedsAttentionFlag
- `ocrText.length < 20` → OcrTooShort

Any fired trigger enables retry.

## Merge rules

`VlmMerger` only adopts VLM values under strict conditions:
- `redeemCode` — VLM value must appear in the OCR text.
- `storeName` — VLM value must come with non-empty evidence array.
- `expiryDate` — VLM value must parse as ISO `yyyy-MM-dd`.
- `description` — VLM value must be longer than existing and not a generic phrase.

If all three key fields are known after merge, `needsAttention` clears to false.

## Candidates

| mode | adapter | runtime | status |
|---|---|---|---|
| VLM_QWEN | QwenVlmCouponModel | MLC LLM `runInference` | shipped |
| VLM_MINICPM | MiniCpmVlmCouponModel | MLC LLM `runInference` (legacy MiniCPM weights when loaded) | shipped |
| VLM_GEMMA | GemmaVisionCouponModel | MediaPipe GenAI multimodal session | shipped (pinned to tasks-genai 0.10.14 API) |

Select which candidate runs in the retry slot with:
```
modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN)
```

## Pipeline integration status

`VlmRetryRunner` (in `extraction.retry`) wraps the evaluator + selector +
merger into a single Hilt-injectable entry point. It is fully unit-tested
(see `VlmRetryRunnerTest`) but not yet called from
`LocalLlmOcrService.processCouponImage`. To adopt:

1. Field-inject `vlmRetryRunner: VlmRetryRunner` into `LocalLlmOcrService`.
2. After `parseWithOptionalRetry` produces a `CouponInfo` (around line 1224
   in `processCouponImageTyped`), wrap with:

```kotlin
val (mergedJson, triggers) = vlmRetryRunner.maybeRetry(
    canonicalJson = couponInfo.toCanonicalJsonString(),
    bitmap = bitmap,
    ocrText = rawOcrText,
    prompt = promptResult.prompt
)
val finalCouponInfo = if (triggers.isEmpty()) couponInfo
    else parseLlmResponseToCouponInfo(mergedJson, rawOcrText, captureTimestamp, structuredCandidates)
```

3. Implement `CouponInfo.toCanonicalJsonString()` as a simple
   `JSONObject` round-trip emitting the seven canonical keys.

The runner returns the original JSON unchanged on any internal failure
(unconfigured adapter, VLM exception, invalid VLM JSON), so the wiring is
fail-safe: production behaviour cannot regress because of retry plumbing.

## Shipping a candidate as retry

1. Hermetic bake-off must show ≥ Qwen text-mode accuracy with hallucination < 10%.
2. Live on-device latency must be p95 < 3× Qwen text-mode.
3. Memory peak must fit target low-end device tier (see Plan 7).
4. After ship, watch `extraction.vlm_retry.fired` counter and
   `extraction.vlm_retry.unavailable` error rate in telemetry.
