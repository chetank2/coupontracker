# Dynamic Memory

This file captures dated project lessons, recent fixes, device observations,
and branch-specific decisions. For stable rules, use [Static Memory](static-memory.md).

Use [Knowledge Base Contract](knowledge-base-contract.md) to decide what belongs
here. Dynamic memory should capture dated fixes, commit summaries, device/logcat
observations, failed approaches, regression tests added, installed APK evidence,
known current bugs, and open follow-ups.

## 2026-06-27: State-Aware Vision Pipeline Follow-Ups

Context:

- Repo: `/Users/C/Documents/coupontracker-qwen`
- Branch: `feature/qwen-multi-coupon-extraction`
- Main commit: `13b674071` (`Implement state-aware vision verification pipeline`)
- Related commits:
  - `2c326061a` (`fix(worker): keep Gemma verification from persisting partial OCR cleanup`)
  - `4f779afba` (`fix(extraction): save multi-coupon previews`)

### What Changed

`13b674071` made state-aware VLM verification current architecture:

- Room DB moved to schema 16.
- `Coupon` gained `codeState`, `expiryState`, `layoutState`, and
  `debugVisionEvidence`.
- New `extraction/vision` classes parse VLM cards, field states, layout states,
  bounds, evidence, and VLM/OCR merge decisions.
- Confidence scoring and UI learned no-code/no-visible-expiry states.

`2c326061a` fixed a worker bug:

- deterministic OCR cleanup is a baseline when Gemma will run,
- it should not be persisted as final trusted cleanup before vision completes,
- user edits are preserved through the latest-state merge.

`4f779afba` fixed a multi-coupon data-loss issue:

- layout-detected preview must keep all detected coupons,
- not only the highest-confidence coupon.

### Why It Was Needed

Device/logcat checks showed the same root class repeatedly:

- full-screen OCR mixed foreground modal text with background cards,
- background no-code text overrode active-card codes,
- previous-card expiry beat current-card expiry,
- weak fragments such as `off` or `Saved offer` became descriptions,
- Gemma parse failures surfaced raw JSON errors,
- verification UI looked finished while WorkManager was still running or was
  replaced by duplicate taps.

The product-level correction was:

```text
VLM for visual structure and field labels.
OCR for exact text.
Validator/scorer for trust.
UI for explicit review state.
```

### Two-Pass Gemma Verification

Current follow-up work moves verification toward:

```text
full screenshot -> Gemma layout JSON only
active card/modal crop with padding
crop OCR for exact visible text
Gemma crop field-label JSON
merge OCR-supported values + VLM states
review on low confidence or contradiction
```

Implementation points:

- `VerifyCouponWorker.prepareTwoPassVisionInput(...)` runs full-screen layout
  detection first and falls back to OCR-targeted crop.
- Layout JSON uses normalized bounds and rejects final coupon fields.
- Crop details, parse failures, normalized bounds, and raw vision JSON are
  stored in `debugVisionEvidence`.
- Crop OCR is used as the support text for final field-label merging.

P1 rule:

- Cropped vision verification must never use full-screen OCR as proof for a
  code, expiry, store, or offer field.
- Full-screen OCR may help select or debug a crop, but it cannot validate final
  cropped field evidence.
- If crop OCR is blank or unusable for a field, route the coupon to review
  instead of proving it from full-screen OCR.

### Vision Parser Lessons

Recent parser hardening:

- support layout-only JSON with normalized bounds,
- support field-label JSON under `fields`,
- allow confidence `0.0` so low-confidence outputs can route to review instead
  of failing parse,
- clamp minor normalized-bound drift,
- reject final coupon fields in layout-only responses,
- convert raw parser failures into safe UI messages.

### Merge Lessons

`mergeVisionFieldLabels(...)` is now code-first:

- OCR-supported current code or VLM-labeled code wins.
- `NO_CODE_NEEDED` is accepted only if no supported code exists.
- Unsupported preserved codes force review.
- VLM-labeled expiry can replace stale OCR expiry when supported by crop OCR.
- A coupon cannot be cleaned with `expiryState=PRESENT` and `expiryDate=null`.

`VisionOcrMergePolicy` now uses labeled evidence for store, description, code,
and expiry; it keeps legal boilerplate out of descriptions and marks low
confidence/unknown state for review.

### MakeMyTrip Device Failure

Observed screenshot:

- File on phone: `coupon_20260626_181426_916.jpg`
- Foreground modal:
  - store: `MAKEMYTRIP FLIGHTS`
  - offer: `Flat 15% off*`
  - code: `FLYMART`
- Background cards contained `NO CODE NEEDED`.

DB before fix:

- `redeemCode=null`
- `codeState=NO_CODE_NEEDED`
- `layoutState=LOW_CONFIDENCE`
- cleanup failed after a Gemma parse issue

Root cause:

- `CouponCodeExtractor` had a global no-code veto.
- Once removed, generic whole-screen code fallback could still pick a
  background code before the active modal code.

Fix:

- no-code text is no longer a global code-extraction veto,
- standalone alpha codes such as `FLYMART` are accepted only with offer/action
  context,
- selected-offer/store-scoped code lookup runs before generic whole-screen code
  fallback,
- hyphenated selected-card codes preserve hyphens.

Regression tests:

- `CouponCodeExtractorTest.extracts foreground modal code even when background card says no code needed`
- `TextExtractorRobolectricTest.extractCouponInfo_keepsMakeMyTripModalCodeWhenBackgroundCardsSayNoCodeNeeded`

### BigBasket And Expiry Lessons

BigBasket-style wallet screens showed that previous-card expiry can survive
verification if crop-labeled expiry is ignored.

Fix direction:

- parse crop-labeled expiry even when current OCR already has an expiry,
- replace stale full-screen expiry when crop OCR supports the VLM-labeled expiry,
- review if `PRESENT` does not produce an actual saved date.

### IDFC No-Code Lessons

IDFC-style no-code modals are valid coupons:

- store can be `IDFC FIRST Bank`,
- description can be `Monthly Interest`,
- `redeemCode=null`,
- `codeState=NO_CODE_NEEDED`,
- `expiryState=NOT_VISIBLE`.

Do not fail only because code or expiry is absent when field states explain the
absence.

### Apple/AGEasy Description Lessons

Legal/support boilerplate must not become the saved offer.

Keep:

- main redeemable offer in `description`,
- terms/legal/support text in `rawOcrText`, debug evidence, or future terms
  storage.

### Rupee OCR Artifact Lessons

Rupee glyphs are often misread as `7` or `z`, but broad fixes are dangerous.

Rejected pattern:

```text
every 7xxx* amount -> ₹xxx*
```

Reason:

- legitimate prices such as `7999*` can be real.

Current direction:

- repair only when raw OCR has supporting split-line evidence,
- keep tests for legitimate high-value prices.

### Verification UI Lessons

Current product guidance:

- disable Verify while cleanup is pending/running,
- use `ExistingWorkPolicy.KEEP` to avoid duplicate Verify taps replacing a job,
- show Gemma-running state clearly,
- avoid raw JSON/parser error text in UI,
- long term: expose which field needs review.

Risk:

- `KEEP` can block retries if a job gets stuck pending/running, so state reset
  and retry UX need attention.

### Testing Evidence

After the MakeMyTrip fix, these checks passed:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Installed debug APK on device `ZD2226NNY9`:

```text
versionCode=10129
versionName=1.1.29
lastUpdateTime=2026-06-26 18:29:34
```

### Open Follow-Ups

- Add worker tests for layout success, layout fallback, field-label parse
  failure, `KEEP` retry behavior, and no-code/no-expiry persistence.
- Add a P1 regression/device check proving cropped vision verification routes
  to review when crop OCR is blank instead of using full-screen OCR as proof.
- Consider consolidating duplicated merge logic between `VerifyCouponWorker`
  and reusable vision merge policies.
- Improve review UI to identify the uncertain field/stage.
- Keep adding screenshot corpus cases from real device failures.
