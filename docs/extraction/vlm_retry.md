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
| VLM_QWEN | QwenVlmCouponModel | MLC LLM (existing) | shipped |
| VLM_MINICPM | MiniCpmVlmCouponModel | n/a | not yet built — needs concrete MiniCpmRuntime |
| VLM_GEMMA | GemmaVisionCouponModel | MediaPipe GenAI | not yet built — needs API verification |

Select which candidate runs in the retry slot with:
```
modelStrategyConfig.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN)
```

## Pipeline integration status

Plan 5 shipped the building blocks (`VlmRetryEvaluator`, `VlmMerger`,
`QwenVlmCouponModel`) but did NOT wire the retry into
`LocalLlmOcrService.processCouponImage`. That integration is a follow-up.
The pieces compose like this:

```kotlin
val triggers = vlmRetryEvaluator.evaluate(canonicalObj, ocrText, ocrSpansCount)
if (triggers.isNotEmpty()) {
    val vlmAdapter = modelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY)
    val vlmJson = vlmAdapter.extractFromImage(bitmap, ocrText, prompt).canonicalJson
    canonicalObj = VlmMerger.merge(canonicalObj, JSONObject(vlmJson), ocrText)
}
```

## Shipping a candidate as retry

1. Hermetic bake-off must show ≥ Qwen text-mode accuracy with hallucination < 10%.
2. Live on-device latency must be p95 < 3× Qwen text-mode.
3. Memory peak must fit target low-end device tier (see Plan 7).
4. After ship, watch `extraction.vlm_retry.fired` counter and
   `extraction.vlm_retry.unavailable` error rate in telemetry.
