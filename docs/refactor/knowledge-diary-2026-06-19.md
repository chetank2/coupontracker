# Knowledge Diary: Refactor And Extraction Reliability

Date: 2026-06-19

Branch checked: `feature/qwen-multi-coupon-extraction`

Remote checked: `origin` at `https://github.com/chetank2/coupontracker.git`

## Git State Checked

We fetched all remote refs with:

```bash
git fetch --all --prune
```

Fetched branch inventory:

- `feature/qwen-multi-coupon-extraction`
- `origin/feature/qwen-multi-coupon-extraction`
- `origin/main`
- `origin/gh-pages`
- local publish branches: `publish-main-privacy`, `publish-privacy-policy`
- local Claude branch: `claude/trusting-blackburn-c08471`

At the time of this diary, `feature/qwen-multi-coupon-extraction` was aligned with
`origin/feature/qwen-multi-coupon-extraction` before the new local commit.

Notable commits already on the feature branch:

- `46bbd2ddc Add crop-first spatial extraction safeguards`
- `36923226c Implement robust coupon verification pipeline`
- `ff4949321 Improve coupon extraction reliability`
- `05af9e073 Polish coupon detail and batch imports`
- `ef3be15c2 Reject weak OCR store fragments`
- `3e8087590 Require OCR evidence for coupon cleanup`
- `494d166fa Validate Qwen self-test output`
- `7c3c70e55 Modularize OCR-first capture flow`
- `c003d90a7 Remove automatic LLM capture paths`

Commits present on `origin/main` but not on this feature branch at fetch time:

- `39ab73ba5 Remove publishing note from public docs`
- `ac6ded79a Publish app review docs`
- `80c27c505 Merge pull request #166 from chetank2/codex/fix-twostagedetector-and-database-issues-kncls1`
- `c16a61d95 Document blockers after merging guard changes into feature branch`
- `6f5817f11 Merge pull request #162 from chetank2/codex/fix-twostagedetector-and-database-issues`
- `529eab4c9 Add tests for confidence breakdown migration and model guard`

Do not assume `origin/main` and this feature branch contain the same release state.
Merge/rebase should be planned explicitly.

## What We Did

### 1. Refactor Guardrails And Knowledge Base

Added project-level instructions and refactor documentation:

- `AGENTS.md`
- `app/AGENTS.md`
- `docs/refactor/README.md`
- `docs/refactor/rules/*`
- `docs/refactor/usecases/*`
- `docs/refactor/packages/*`
- `docs/refactor_architecture.md`

Purpose:

- Make future agent edits safer.
- Define code structure, product rules, design rules, design-system rules,
  universal extraction rules, and anti-hardcoding rules.
- Document small use cases such as extract, clean, save, delete, and share.

### 2. UI And Package Structure Refactor

Moved large screen/viewmodel files into scoped UI packages:

- `ui/details`
- `ui/home`
- `ui/modelsettings`
- `ui/review`
- `ui/settings`

Updated navigation and fragment imports so existing routes continue to resolve.

Also moved preference implementation toward `data/preferences` while preserving
compatibility through existing utility entry points.

### 3. Extraction Modularization

Split extraction helpers into smaller modules:

- `extraction/rules/CouponCodeExtractor.kt`
- `extraction/rules/CouponTextBlocks.kt`
- `extraction/merge/ModelCleanupMergePolicy.kt`
- `extraction/quality/OfferTextQuality.kt`

Purpose:

- Keep code extraction, text block handling, model cleanup merge policy, and offer
  quality scoring out of the monolithic extractor.
- Make each failure mode testable in isolation.

### 4. Crop-First And Spatial Safeguards

Earlier feature branch commits added crop-first extraction safeguards. The key
architectural rule is:

```text
Segment first. Extract second. Validate spatial consistency third.
```

The target failure was multi-card association:

- merchant from one card
- offer from another card
- code from another card
- expiry missed or copied from the wrong region

The current pipeline now prefers detected coupon-card crops before full-image
LLM/VLM extraction, and spatial validation has been improved to choose the
tightest matching OCR anchor group instead of always using the first occurrence.

### 5. Qwen Cleanup Guardrails

Qwen text cleanup is treated as a cleanup stage, not an authority that can freely
replace OCR-backed protected fields.

`ModelCleanupMergePolicy` records per-field decisions in `extractionRunPath` and
rejects model regressions when the model lacks OCR evidence.

Important rule:

```text
If the model output is weak, do not overwrite OCR-backed protected fields.
If both model output and current field are weak, mark needsAttention.
```

### 6. Gemma Vision Guardrails

Gemma Vision was restored to usable runtime settings after a too-small token
limit caused runtime failures.

Current relevant settings:

- `GemmaVisionRuntime.DEFAULT_MAX_TOKENS = 512`
- longer response timeout
- bounded image edge scaling for mobile runtime stability

`VerifyCouponWorker` now uses OCR-targeted crops before invoking Gemma Vision.

## What Went Wrong

### Issue A: Full Screenshot Entity Mixing

Symptoms:

- A coupon code from one card could be paired with merchant/offer from another
  card.
- Example class: Uber/IKEA multi-card association failure.

Root cause:

- Full-screen extraction asked the model to solve card association.
- Vision-language models can read distinctive codes but still mix nearby cards.

Fix:

- Route main scanner and batch scanner through card detection/crops where possible.
- Use crop bitmap as the canonical extraction unit.
- Add spatial validation foundation with OCR bounding boxes.

### Issue B: OCR/Rules Temporarily Picked Bad Descriptions

Symptoms:

- Descriptions like `5TH` or `you won off` could be treated as meaningful.
- Wallet/card OCR fragments could leak into final saved descriptions.

Root cause:

- Numeric presence was treated as enough signal in some descriptions.
- Savings phrases without actual amount/product/value were not rejected strongly
  enough.

Fix:

- `GenericFieldHeuristics` now rejects:
  - ordinal/date fragments like `5TH`
  - date-only phrases like `05 May, 2025`
  - expiry-only phrases like `Ends 5th May`
  - savings text without concrete value like `you won off`
- Added regression tests in `GenericFieldHeuristicsTest`.

### Issue C: Gemma Expiry Format Was Rejected

Symptoms:

- Gemma could return readable expiry strings such as
  `05 May, 2025, 11:59 PM`.
- The verifier only accepted canonical ISO dates, so readable dates were rejected.

Root cause:

- Expiry parsing was private and ISO-only inside `VerifyCouponWorker`.

Fix:

- Added reusable `ModelExpiryNormalizer`.
- It accepts ISO dates and readable model dates, including trailing time.
- `VerifyCouponWorker` now uses the shared normalizer.
- Added `ModelExpiryNormalizerTest`.

### Issue D: Spatial Validation False Positives On Full-Screen OCR

Symptoms:

- Spatial validation reported fields too far apart before Gemma/merge produced
  final saved fields.
- Example: a store logo/header occurrence was picked as the anchor instead of the
  matching coupon-card occurrence.

Root cause:

- Spatial validation used the first OCR match for a field.

Fix:

- `SpatialFieldConsistencyValidator` now collects multiple anchor candidates.
- It chooses the tightest vertical grouping before deciding consistency.
- Added tests for same-card acceptance and duplicate full-screen store anchors.

### Issue E: Wallet Header Noise Beating Real Store

Symptoms:

- Latest device logs showed `NamM` selected as store while the visible coupon
  card was `Skullcandy`.

Root cause:

- Store scoring overvalued early/header OCR text in wallet screens.
- Header/app OCR appeared before the first coupon signal and could win by
  position/line score.

Fix:

- `TextExtractor` now detects wallet chrome lines such as `vouchers`, `active`,
  and `lifetime`.
- Store candidates before the first coupon signal are penalized.
- Added regression test:
  `extractCouponInfo ignores wallet header merchant noise for visible coupon card`.

### Issue F: Code-Shaped Tokens Becoming Store Candidates

Symptoms:

- A fallback test revealed `CREDJP70` could become a store candidate.

Root cause:

- Store validation allowed alphanumeric candidates that looked like coupon codes.

Fix:

- `StoreCandidateValidator` now rejects token candidates that contain both
  letters and digits.
- Existing LEAF fallback behavior remains covered.

### Issue G: Vision Cleanup Could Mark Weak Preserved Fields As Verified

Symptoms:

- Latest device row showed:
  - `storeName = NamM`
  - weak description preserved
  - Gemma only changed/replaced code
  - final source was still `VISION_VERIFIED`

Root cause:

- `VerifyCouponWorker` allowed preserved OCR store/description to remain while
  marking the whole coupon as vision verified.

Fix:

- Vision cleanup now computes field sources before saving.
- If core fields (`storeName`, `description`) are preserved rather than supplied
  by valid vision output, the coupon does not get upgraded to `VISION_VERIFIED`.
- Such rows stay `needsAttention`.

## Universal Rules Learned

### Extraction Unit Rule

Never trust fields extracted from an unsegmented multi-card screenshot.

Use:

```text
full screenshot -> card regions -> one card crop -> OCR/VLM -> field bundle
```

Avoid:

```text
full screenshot -> model -> final fields
```

### Bundle Rule

Final save should be bundle-oriented, not field-by-field only.

Fields should carry:

- value
- source stage
- confidence
- OCR evidence
- card/region context

The final saved coupon should be verified only when the accepted core fields
belong to the same coupon region.

### Model Rule

Qwen/Gemma can propose fields, but proposals must pass:

- OCR evidence support
- generic/noise checks
- same-card context
- field-specific validation

Weak model output should not bless weak OCR output.

### Store Rule

Reject or penalize store candidates that are:

- wallet/app/header chrome
- near `vouchers`, `active`, `lifetime`
- alphanumeric code-shaped tokens
- generic action words
- expiry fragments

### Description Rule

Descriptions must contain concrete coupon value or product context.

Reject:

- `5TH`
- `05 May, 2025`
- `Ends 5th May`
- `you won off`

Accept:

- `you won 80% off on Skullcandy`
- `you've won neck fan at ₹1100`

### Code Rule

Short codes need stronger evidence. A code should be trusted when:

- it is near an explicit label such as `code:`, `coupon code`, `use code`, or
  `apply code`; or
- it has enough code-like structure and same-card context.

Do not classify code-shaped tokens as store names.

### Verification Label Rule

`VISION_VERIFIED` must mean the core coupon fields were actually verified or
provided by vision, not merely that Gemma ran.

If Gemma runs but store/description are preserved from weak OCR:

```text
needsAttention = true
extractionSource remains OCR/Qwen/user source, not VISION_VERIFIED
```

## Tests Run

The following checks passed after the latest fixes:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Device install was attempted, but adb reported no connected device:

```text
List of devices attached
```

## Files Most Relevant To These Lessons

- `app/src/main/kotlin/com/example/coupontracker/extraction/rules/TextExtractor.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/rules/CouponCodeExtractor.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/quality/OfferTextQuality.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/merge/ModelCleanupMergePolicy.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/validation/SpatialFieldConsistencyValidator.kt`
- `app/src/main/kotlin/com/example/coupontracker/util/GenericFieldHeuristics.kt`
- `app/src/main/kotlin/com/example/coupontracker/util/ModelExpiryNormalizer.kt`
- `app/src/main/kotlin/com/example/coupontracker/util/StoreCandidateValidator.kt`
- `app/src/main/kotlin/com/example/coupontracker/worker/VerifyCouponWorker.kt`

## Remaining Follow-Ups

- Add a first-class `CouponRegionContext`.
- Add a first-class `CouponFieldBundle`.
- Add a final `CouponBundleValidator` before database save.
- Make all extraction stages produce field provenance, not only final values.
- Merge or reconcile `origin/main` publishing/doc commits into this feature branch
  only after reviewing conflicts and release intent.
