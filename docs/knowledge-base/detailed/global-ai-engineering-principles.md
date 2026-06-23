# Global AI Engineering Principles

This document describes how a coupon extraction app should work if designed by
an AI engineer for users across countries, languages, currencies, merchant
formats, and device types.

It is intentionally product- and architecture-focused. Do not treat one brand,
one country, one wallet app, or one screenshot layout as the whole problem.

## Product Goal

The app should help users capture, understand, save, review, and reuse coupons
with minimal manual effort and maximum trust.

The app should never pretend uncertain extraction is correct.

Correct behavior:

```text
read coupon evidence
-> extract likely fields
-> validate field meaning and source
-> show confidence/review state
-> save only as trusted when evidence is strong
```

Incorrect behavior:

```text
model returned text
-> save as verified
```

## Global User Assumptions

Users can be from different countries and coupon ecosystems.

The app must support variation in:

- languages,
- scripts,
- currencies,
- date formats,
- decimal separators,
- merchant naming,
- phone screen sizes,
- local wallet apps,
- coupon layouts,
- right-to-left or left-to-right text,
- online/offline usage,
- low-end and high-end devices.

No product rule should assume that all coupons look Indian, American, English,
or like one merchant app.

## Practical Android Strategy

Training custom models is expensive and should not be the default assumption.

For an Android coupon app, the practical strategy should be:

```text
Option 1: local/on-device VLM reads one coupon region directly
Option 2: OCR extracts text, then local/on-device LLM cleans or structures it
Fallback: deterministic rules + user review
```

Do not design the core app around a paid training pipeline unless the product
has the budget, data operations, evaluation process, and deployment pipeline to
support it.

The app should run acceptably on Android phones. That means every AI path must
handle:

- limited memory,
- thermal throttling,
- slow first model load,
- model download/storage size,
- offline operation,
- runtime failures,
- low-end device fallbacks.

The product should prefer a reliable reviewable result over a slow or unstable
"fully automatic" result.

## How The App Should Work

### 1. Capture Or Import

The app receives an image from:

- camera,
- screenshot,
- share sheet,
- gallery,
- batch import,
- pasted image,
- external app intent.

The capture layer should only collect input and metadata.

It should not decide final coupon fields.

### 2. Region Detection

Before extraction, the app should identify the coupon unit:

- one coupon card,
- one voucher row,
- one offer tile,
- one email coupon block,
- one receipt coupon area.

The extraction unit should be a coupon region, not the full screen, whenever
more than one content block may exist.

Universal rule:

```text
Segment first. Extract second. Validate third.
```

### 3. OCR And Visual Reading

OCR should produce:

- raw text,
- line text,
- word/block bounding boxes,
- confidence,
- language/script when available,
- image region id.

The app must preserve this evidence. Cleaned text is useful, but raw OCR should
remain available for debugging and validation.

### 4. Candidate Extraction

The app should extract field candidates, not final fields.

Example:

```text
STORE candidate: value, source, confidence, region, bounding box, evidence
CODE candidate: value, source, confidence, region, bounding box, evidence
EXPIRY candidate: value, source, confidence, region, bounding box, evidence
OFFER candidate: value, source, confidence, region, bounding box, evidence
```

Each candidate should explain why it exists.

### 5. Model Assistance

Models can help with:

- text cleanup,
- visual confirmation,
- field proposal,
- translation,
- normalization,
- ambiguity resolution.

Preferred Android order:

1. VLM on the cropped coupon region, when model/runtime/device capability is
   available.
2. OCR plus local LLM cleanup when VLM is unavailable, too slow, or fails.
3. Deterministic OCR/rules plus user review when model assistance is unavailable.

Models must not be the final authority.

Model output must pass:

- schema validation,
- evidence validation,
- field-specific validation,
- region validation,
- merge policy.

### 6. Field Validation

Every field should have its own validator.

Store validator:

- accepts merchant-like text,
- rejects code-shaped strings,
- rejects app chrome/header text,
- rejects expiry/action/generic words.

Code validator:

- prefers label proximity such as code/apply/use/redeem,
- accepts strong alphanumeric/code-shaped tokens,
- rejects generic words,
- rejects unlabeled alpha-only logo-like words unless evidence is strong.

Offer validator:

- prefers complete offer phrases,
- requires value/action context when possible,
- rejects tiny fragments,
- rejects expiry-only text,
- rejects code/store duplicates.

Expiry validator:

- parses absolute dates,
- parses relative dates,
- handles local formats,
- normalizes to canonical internal representation,
- keeps display localized for the user.

Amount/payment validator:

- understands local currency,
- distinguishes cashback, discount, minimum order, and payment method.

### 7. Final Bundle Validation

Before save, the app should validate the coupon as a bundle.

Required check:

```text
store + offer + code + expiry must belong to the same coupon region
```

If this cannot be proven, the app should mark the coupon as review-needed.

The final result should include:

- final field values,
- source per field,
- confidence per field,
- evidence per field,
- review status,
- warnings or reasons.

### 8. Review And Correction

The app should let the user correct uncertain fields.

User corrections are high-quality feedback and should be stored as:

- corrected field,
- original field,
- original evidence,
- correction timestamp,
- source image/region,
- reason if available.

Corrections should improve future extraction through tests, rules, or training
data. They should not become hardcoded brand patches.

## Function Design Rules

Functions should be small, named by responsibility, and testable.

Good function shape:

```kotlin
fun validateCouponCode(candidate: FieldCandidate, evidence: CouponEvidence): ValidationResult
```

Poor function shape:

```kotlin
fun fixCouponEverything(...)
```

Each function should do one job:

- normalize text,
- detect candidates,
- score candidates,
- validate one field,
- merge field proposals,
- validate final bundle,
- map domain model to UI model,
- save data.

Do not mix these in one function:

- OCR,
- model inference,
- database writes,
- UI state,
- navigation,
- logging,
- analytics,
- validation.

## Modularity Rules

The app should be organized by ownership:

```text
capture/          image input, metadata, permissions
region/           coupon/card/row detection
ocr/              OCR engines, OCR result model, OCR cleanup
evidence/         field evidence, bounding boxes, provenance
rules/            deterministic extraction rules
validation/       field and bundle validators
merge/            OCR/model/user merge policy
models/           local Android model adapters and runtime status
domain/           app use cases and domain models
data/             persistence, preferences, repositories
ui/               screens, view state, review/edit flows
workers/          background cleanup, expiry reminders, verification
analytics/        quality metrics and failure reporting
```

Modules should communicate through typed contracts, not raw maps or loose JSON
unless crossing a model/API boundary.

## Internationalization Rules

Internal values and display values are different.

Internal:

- canonical date/time,
- normalized currency amount,
- normalized field type,
- stable source/evidence labels.

Display:

- localized date format,
- localized currency,
- translated labels,
- local number formatting,
- local text direction.

Do not store only localized display text if the app needs to compare, sort,
dedupe, or remind later.

## Data And Privacy Rules

Coupon images and OCR text can contain sensitive data.

Default product rule:

```text
local-first storage
no cloud upload
no cloud sync
no remote training upload
unless the user explicitly opts in and the product clearly documents it
```

The app should:

- minimize stored raw images when possible,
- explain what is stored,
- avoid uploading by default,
- require explicit consent before any cloud storage, cloud sync, remote model
  inference, analytics upload, or training-data contribution,
- redact sensitive logs,
- keep user corrections private unless user opts into sharing,
- allow deletion of images and extracted data.

For global users, privacy expectations and laws vary. Design for the stricter
case by default.

If a product does not implement cloud storage, its UI, store listing, privacy
policy, and docs should say local-only/local-first. Do not imply cloud backup,
cloud sync, or account-based restore exists unless it is actually implemented.

## AI Safety And Trust Rules

The app should not overstate AI certainty.

Use honest states:

- extracted,
- needs review,
- verified by OCR evidence,
- verified by vision evidence,
- corrected by user,
- failed safely.

Do not use labels like verified, checked, or trusted unless the evidence really
supports that status.

If the app is uncertain, it should say so.

## Anti-Hardcoding Rules

Do not hardcode:

- merchant names,
- country-specific assumptions,
- one currency,
- one date format,
- one app layout,
- one language,
- one model path outside model management,
- one threshold scattered across screens.

Allowed:

- configurable lexicons,
- locale-aware parsers,
- data-driven merchant hints,
- tested generic rule sets,
- feature flags,
- centralized thresholds.

Observed failures should become generic rules:

```text
brand-like alpha token without code label is weak code evidence
```

not:

```text
if token == "PORTRONICS" then reject
```

## Testing Rules

Every real-world failure should create a regression test.

Test categories:

- single coupon extraction,
- multi-coupon screenshots,
- VLM success/failure on cropped coupon region,
- OCR plus LLM cleanup success/failure,
- no-code coupons,
- alpha-only codes with labels,
- alpha-only brand/logo text without labels,
- multiple currencies,
- multiple date formats,
- relative expiry,
- right-to-left text,
- low OCR confidence,
- model hallucination,
- offline mode,
- duplicate coupon handling,
- user correction flow.

Tests should assert behavior, not implementation details.

## Quality Metrics

Track accuracy by field:

- store accuracy,
- offer accuracy,
- code accuracy,
- expiry accuracy,
- false verified rate,
- review-needed rate,
- user correction rate,
- duplicate save rate,
- model failure rate,
- OCR blank/low-confidence rate.

The most dangerous metric is false confidence:

```text
wrong coupon saved as trusted
```

That should be treated as worse than:

```text
correct coupon marked needs review
```

## Engineering Standard

The app should prefer:

- explicit uncertainty over fake certainty,
- generic evidence rules over brand patches,
- typed contracts over loose strings,
- region-first extraction over full-screen guessing,
- user correction over silent overwrite,
- validators over prompt-only fixes,
- regression tests over repeated manual debugging.

Final engineering rule:

```text
No field is final until the app knows what it is, where it came from,
why it belongs to the coupon, and how confident that evidence is.
```
