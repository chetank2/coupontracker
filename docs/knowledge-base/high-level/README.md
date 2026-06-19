# High-Level Knowledge Base

This level is for quick orientation.

Read this when you need to understand the product, architecture, rules, and
current priorities without digging through implementation history.

## One-Screen Summary

CouponTracker is an Android-first coupon wallet and extraction app.

It reads coupon screenshots, extracts coupon fields, lets users review/save
coupons, and supports local model cleanup/verification.

The app also has supporting training and annotation tools:

- Python/web training interface.
- Mobile PWA/offline annotation flow.
- Mac-side extraction harness and benchmarks.

## Current Product Surfaces

### Android App

Primary user-facing product.

Core jobs:

- scan/import screenshots,
- detect coupon regions,
- extract store, offer, code, expiry, amount/payment fields,
- save coupons locally,
- clean/verify uncertain coupons with local models,
- review, share, delete, and manage coupons.

### Training Interface

Supports model/data work.

Core jobs:

- collect coupon images,
- annotate coupon fields,
- train/evaluate model assets,
- prepare Android-compatible assets.

### Mobile PWA

Supports mobile/offline annotation.

Core jobs:

- annotate on phone/tablet,
- store work offline,
- sync/export training data later.

## Current Architecture Rule

The extraction architecture must be:

```text
screenshot
-> coupon/card region
-> OCR/model extraction inside one region
-> field bundle
-> validation
-> save or needs review
```

Avoid:

```text
full screenshot -> model -> final coupon
```

That caused multi-card field mixing.

## Current Extraction Rule

The universal extraction rule is:

```text
Segment first. Extract second. Validate spatial consistency third.
```

Fields should not be trusted unless they are supported by:

- same-card region,
- OCR evidence,
- field-specific validation,
- model contract validation when model output is used.

## Current Model Rule

Qwen/Gemma/MiniCPM can propose or clean fields.

They must not blindly overwrite protected fields.

Required checks:

- JSON contract/schema validation,
- OCR evidence support,
- no generic/noise field values,
- same-card context,
- confidence/needs-review policy.

## Current Verification Label Rule

`VISION_VERIFIED` must mean core fields were actually supported by vision.

Do not mark `VISION_VERIFIED` just because Gemma ran.

If store or description are preserved from weak OCR:

```text
needsAttention = true
extractionSource is not upgraded to VISION_VERIFIED
```

## Current Refactor Direction

Target package shape:

- `ui/home`
- `ui/details`
- `ui/review`
- `ui/settings`
- `ui/modelsettings`
- `data/preferences`
- `domain/usecase`
- `extraction/rules`
- `extraction/quality`
- `extraction/merge`
- `extraction/validation`
- `worker`

Do not create giant catch-all files.

## Current Known Risk Areas

1. Multi-card screenshots.
2. Wallet/header OCR noise.
3. Short or partial coupon codes.
4. Weak descriptions such as `you won off`.
5. Expiry badge fragments becoming store/description.
6. Model-readable dates not being canonical ISO.
7. Model cleanup preserving bad OCR but making the result look verified.

## Must-Read Docs

- [Full project diary](../../PROJECT_KNOWLEDGE_DIARY.md)
- [Refactor diary](../../refactor/knowledge-diary-2026-06-19.md)
- [AI editing checklist](../../ai_guardrails/AI_EDITING_CHECKLIST.md)
- [Coupon extraction rules](../../ai_guardrails/COUPON_EXTRACTION_RULES.md)
- [Universal extraction rules](../../refactor/rules/universal-extraction-solution-rules.md)
- [Anti-hardcoded rules](../../refactor/rules/anti-hardcoded-rules.md)

## Current Checks

Run before committing Android changes:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```
