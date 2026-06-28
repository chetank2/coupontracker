# Extraction And OCR Knowledge

Use this page for OCR, deterministic rules, field extraction, spatial validation,
multi-coupon extraction, and reliability work.

## Current Principle

```text
Segment first. Extract second. Validate spatial consistency third.
```

Extraction must be region-first and evidence-backed.

The current VLM/OCR authority split is:

```text
OCR = exact visible text
Gemma Vision = layout ownership and field labels/states
validator/scorer = final trust gate
```

See also:

- [Static Memory](../static-memory.md)
- [Dynamic Memory](../dynamic-memory.md)

## Current Code Areas

- `app/src/main/kotlin/com/example/coupontracker/extraction/rules`
- `app/src/main/kotlin/com/example/coupontracker/extraction/quality`
- `app/src/main/kotlin/com/example/coupontracker/extraction/merge`
- `app/src/main/kotlin/com/example/coupontracker/extraction/validation`
- `app/src/main/kotlin/com/example/coupontracker/extraction/multi`
- `app/src/main/kotlin/com/example/coupontracker/ocr`
- `app/src/main/kotlin/com/example/coupontracker/util/ImageProcessor.kt`
- `app/src/main/kotlin/com/example/coupontracker/worker/VerifyCouponWorker.kt`

## Current Docs

- [Coupon extraction rules](../../ai_guardrails/COUPON_EXTRACTION_RULES.md)
- [Extraction pipeline](../../extraction_pipeline.md)
- [Extraction reliability program](../../extraction_reliability_program.md)
- [OCR/LLM pipeline issues](../../OCR_LLM_PIPELINE_ISSUES.md)
- [Offer text deprecation plan](../../OFFER_TEXT_DEPRECATION_PLAN.md)
- [Field flow audit](../../extraction/field-flow-audit.md)
- [Device tiers](../../extraction/device_tiers.md)
- [VLM retry](../../extraction/vlm_retry.md)
- [Qwen coupon plan review](../../qwen_coupon_plan_review.md)
- [Debug extraction score plan](../../debug_extraction_score_plan.md)

## Current Failure Classes

### Multi-Card Field Mixing

Symptoms:

- code from one coupon,
- merchant/offer from another coupon,
- expiry missed or copied from adjacent card.

Fix pattern:

- detect card/region first,
- crop one region,
- run OCR/model per crop,
- validate all fields belong to the same region.

### Wallet/Header Noise

Symptoms:

- app/header words such as `vouchers`, `active`, `lifetime`, or OCR artifacts
  become store names.

Fix pattern:

- penalize pre-card header candidates,
- require coupon-card context,
- reject generic/action/chrome tokens.

### Weak Offer Descriptions

Symptoms:

- `5TH`,
- `you won off`,
- expiry fragments,
- raw OCR chunks.

Fix pattern:

- `GenericFieldHeuristics`,
- `OfferTextQuality`,
- `CouponFieldNoise`,
- tests for every observed failure.

### Code/Store Confusion

Symptoms:

- alphanumeric code token becomes store,
- short partial code becomes trusted.

Fix pattern:

- reject code-shaped store candidates,
- require stronger evidence for short codes,
- prefer explicit nearby `code:`/`use code`/`apply code` labels.

### Background No-Code Overriding Active Code

Symptoms:

- foreground modal has a real code,
- background card says `NO CODE NEEDED`,
- saved coupon shows no-code state and drops the real code.

Fix pattern:

- scope code extraction to the active card/modal first,
- treat no-code text as absence evidence only after scoped code search fails,
- persist no-code through `codeState`, not as a `redeemCode` string,
- require OCR/visible-text evidence before trusting a coupon code.

### VLM Layout And Field-Label Failures

Symptoms:

- valid layout JSON parses empty because schema enforcement stripped `cards`,
- raw parser errors appear in UI,
- Gemma runs but saved fields remain wrong or partially trusted.

Fix pattern:

- use raw vision extraction for layout and field-label contracts,
- keep layout JSON separate from final coupon field JSON,
- store parse details in `debugVisionEvidence`,
- route low-confidence/malformed VLM output to review while preserving OCR.

## Historical Docs

Use for history only:

- [Universal extraction solution](../../archive/UNIVERSAL_EXTRACTION_SOLUTION.md)
- [Extraction explained](../../archive/EXTRACTION_EXPLAINED.md)
- [Extraction learning system](../../archive/EXTRACTION_LEARNING_SYSTEM.md)
- [Final Tesseract to ML Kit migration](../../archive/FINAL_TESSERACT_TO_MLKIT_MIGRATION.md)
- [OCR improvements README](../../archive/README-OCR-Improvements.md)
- [Extraction root cause analysis](../../archive/fixes/EXTRACTION_ROOT_CAUSE_ANALYSIS.md)
- [Extraction failure analysis](../../archive/fixes/EXTRACTION_FAILURE_ANALYSIS.md)
- [Testing instructions](../../archive/testing/TESTING_INSTRUCTIONS.md)

## Tests To Add For New Bugs

Add unit tests near the failing component:

- `TextExtractorTest` for rule extraction.
- `CouponCodeExtractorTest` for code/no-code conflicts.
- `PostOcrCouponNormalizerTest` for OCR artifact repairs.
- `GenericFieldHeuristicsTest` for generic/noise filtering.
- `SpatialFieldConsistencyValidatorTest` for region/anchor behavior.
- `ModelCleanupMergePolicyTest` for model merge decisions.
- `ModelExpiryNormalizerTest` for model date parsing.
- `VisionFieldJsonParserTest` and `VisionOcrMergePolicyTest` for VLM schema
  and merge rules.

Every real device failure should become a small regression test.
