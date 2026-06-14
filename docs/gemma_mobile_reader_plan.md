# Gemma Mobile Reader Plan

## Goal

Add a mobile-compatible Gemma reader as an optional on-device cleanup engine for Coupon Tracker, without replacing the current OCR-first flow.

## Recommendation

Use Gemma through Google's mobile inference path, LiteRT/LiteRT-LM or MediaPipe GenAI, as a second engine behind a shared reader interface. Keep OCR as the primary fast path. Keep Qwen as the current default until Gemma is proven faster and more accurate on real coupon screenshots.

## Phase 1: Prove The Model Outside The App

1. Test Gemma on the same Android phone with Google AI Edge Gallery or a minimal LiteRT-LM sample.
2. Use real coupon OCR text from Coupon Tracker as input.
3. Measure first-token latency, total JSON cleanup time, memory, thermal behavior, and JSON reliability.
4. Compare the same coupons against the current Qwen cleanup path.

## Phase 2: Make Reader Engines Swappable

Introduce one app-facing interface:

```kotlin
interface CouponReaderEngine {
    suspend fun isAvailable(): Boolean
    suspend fun cleanCoupon(input: CouponReaderInput): CouponReaderResult
}
```

Implement:

- `QwenCouponReaderEngine`
- `GemmaLiteRtCouponReaderEngine`

The scanner, detail screen, and cleanup worker should only call the interface.

## Phase 3: Expand ModelCatalog

Model metadata should live in one place:

- model id
- display name
- backend type
- expected file size
- local path
- minimum RAM recommendation
- download source
- text-only or multimodal capability

## Phase 4: Download And Selection UI

Settings -> Offline scanning should support:

- current reader model
- download Gemma
- make Gemma default
- remove downloaded model
- reader check per model

The Qwen and Gemma download flows should not share backend-specific assumptions.

## Phase 5: Runtime Flow

Keep the UX unchanged:

1. OCR extracts immediately.
2. Coupon saves immediately.
3. Background cleanup runs through selected `CouponReaderEngine`.
4. Cleanup progress appears on Home/Detail.
5. Cleaned fields merge conservatively into the coupon.

## Phase 6: Prompt And Deterministic Rules

Use text-only OCR input first:

```text
You are cleaning OCR text from a coupon screenshot.
Return strict JSON only:
{
  "storeName": string|null,
  "offer": string|null,
  "redeemCode": string|null,
  "expiryDate": "YYYY-MM-DD"|null,
  "minimumPurchase": number|null,
  "maximumDiscount": number|null,
  "paymentMethod": string|null,
  "confidence": number
}
Do not invent values. If not visible, return null.
OCR:
...
```

Maintain synonyms in deterministic extraction code, not only prompts. The prompt can guide the model, but OCR-fast extraction must keep working without any model.

## Decision Gate

Make Gemma the default only if it is measurably better than Qwen on real app coupons:

- faster cleanup time
- lower memory pressure
- fewer invented fields
- more reliable strict JSON
- better offer/code/expiry accuracy
