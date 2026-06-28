# Static Memory

This file captures stable project knowledge: product direction, architecture,
design rules, development boundaries, testing standards, and code hygiene.

Use this before changing the app. For recent device findings and dated fixes,
use [Dynamic Memory](dynamic-memory.md).

Use [Knowledge Base Contract](knowledge-base-contract.md) to decide whether a
new lesson belongs in static memory, dynamic memory, a source map, a decision
log, a failure playbook, or a testing playbook.

## Static Memory Scope

Static memory should preserve:

- product principles,
- current and target architecture boundaries,
- package ownership,
- extraction authority model,
- design/UI rules,
- Room/schema rules,
- WorkManager rules,
- code hygiene rules,
- testing standards,
- required commands.

## Product Principles

CouponTracker is an Android-first coupon wallet and extraction app.

The product must optimize for:

- correct coupon fields over fast-looking automation,
- reviewable uncertainty over silent bad saves,
- local/on-device extraction and verification where possible,
- usable no-code and no-expiry coupons as first-class cases.

`NO_CODE_NEEDED` and `NOT_VISIBLE` are valid user-facing states. They are not
generic failures and should not be converted into fake field text.

## Extraction Authority Model

The durable extraction contract is:

```text
OCR = exact visible text reader
Gemma Vision = visual structure, ownership, and semantic field labeling
rules/normalizers = deterministic cleanup from evidence
validator/scorer = final trust decision
Room/UI = persisted result plus review state
```

Gemma should not be treated as final truth for coupon codes. A coupon code may
only be trusted when exact OCR or visible-text evidence supports it.

## Static Pipeline Shape

Preferred flow:

```text
1. Full screenshot
2. Detect foreground coupon/card/modal
3. Crop active coupon with padding
4. OCR crop for exact text
5. Gemma field-labels crop text/visuals
6. Merge field states and OCR values
7. Validate trust/review
8. Save or mark needs review
```

Avoid:

```text
full multi-card screenshot -> final field extraction
```

That path mixes foreground modal text, previous/background coupon cards, wallet
chrome, status bar text, CTAs, and terms/legal text.

## Static Vision Contracts

Use separate VLM contracts:

- **Layout contract**: full screenshot, layout/card ownership only, normalized
  bounds, foreground/modal selection. It must not return final coupon fields.
- **Field-label contract**: cropped active coupon, semantic labels for store,
  description, code, expiry, evidence, state, and noise.

Use raw vision extraction for these contracts. The normal coupon JSON contract
can strip task-specific keys such as `cards`, `bounds`, and `fields`.

## Field-State Persistence

`Coupon` field states are persisted separately from exact values:

- `codeState`
- `expiryState`
- `layoutState`
- `debugVisionEvidence`

These states explain absence, visibility, layout confidence, and debug
evidence. They are not substitutes for `redeemCode`, `expiryDate`,
`storeName`, or `description`.

## Merge Rules

Code:

- If OCR/crop supports an exact code, use the code and set `codeState=PRESENT`.
- Accept VLM code labels only when exact OCR/visible evidence supports them.
- Accept `NO_CODE_NEEDED` only when no supported active-card code exists.
- Never let background `NO CODE NEEDED` override a visible active-card code.
- Unsupported preserved codes require review.

Expiry:

- If VLM labels expiry as `PRESENT`, parse a real `expiryDate` or require review.
- A crop-supported VLM expiry can replace stale full-screen OCR expiry.
- `expiryState=NOT_VISIBLE` should not punish a missing expiry date.

Store and description:

- Store may use visual label evidence if it does not contradict OCR.
- Description must be the main redeemable offer.
- Legal text, support boilerplate, CTAs, website context, and raw OCR chunks
  belong in `rawOcrText`, debug evidence, or future terms fields.

Layout:

- `LOW_CONFIDENCE`, `PARTIAL`, full-screen fallback, or contradiction should
  mark review instead of direct save.

## Product And Design Rules

Home/detail cards should show state directly:

- show `No code needed` when `codeState=NO_CODE_NEEDED`,
- show `Not visible` when `expiryState=NOT_VISIBLE`,
- show `Needs review` for low confidence, contradictions, unsupported codes, or
  parse/layout failures.

Verification UI should:

- make it visible when Gemma is running,
- disable duplicate Verify taps while a job is pending/running,
- avoid raw parser errors such as "unterminated JSON",
- explain which field or stage needs review when possible.

Do not hide bad extraction behind a successful-looking cleaned state.

## Development Boundaries

Capture remains OCR-first and crop-first. Model cleanup is explicit/background
verification, not foreground capture.

## Current And Target Package Map

Target package shape:

- `ui/`: screens, fragments, UI state, navigation, viewmodels.
- `domain/`: use cases and future pure domain models/repository contracts.
- `data/`: Room, repositories, mappers, preferences.
- `extraction/`: crop, OCR interpretation, rules, merge, pipeline, validation,
  layout, and vision extraction contracts.
- `ai/`: future model management, cleanup, and verification adapters.
- `worker/`: target home for WorkManager jobs and enqueue helpers.

Current package map also includes:

- `data/local` and `data/model`: current Room DAO/database/entity locations.
- `llm`, `ml`, `extraction/model`, `verification`: current model/AI-related
  code. `ai/` does not currently exist and is only a future target.
- `ocr`: current OCR package.
- `work` and `worker`: current WorkManager code is split between both.
- `di`: Hilt modules and dependency wiring.
- `runtime`: device capability/tier/runtime policy.
- `util`: shared utilities and legacy extraction helpers.

Do not move Room, workers, model adapters, or OCR packages just to match the
target map. Move them only in dedicated, migration-safe, test-backed slices.

Keep these areas separate:

- scanner/capture routing,
- OCR/rules extraction,
- VLM layout and field labeling,
- merge policy,
- validation/scoring,
- persistence,
- UI state rendering,
- WorkManager verification.

Avoid broad refactors while fixing one extraction failure. Add the smallest
general rule that preserves the architecture.

## Testing Standards

Every device/logcat extraction failure should become a focused regression test.

Useful test targets:

- `TextExtractorTest` for deterministic text/rule extraction.
- `CouponCodeExtractorTest` for code detection and no-code conflicts.
- `PostOcrCouponNormalizerTest` for OCR artifact repair.
- `CouponExtractionConfidenceScorerTest` for trust/review scoring.
- `VisionFieldJsonParserTest` for VLM schema parsing.
- `VisionOcrMergePolicyTest` for VLM/OCR merge behavior.
- Worker tests for layout success/fallback, field-label parse failure, and
  WorkManager retry behavior.

Keep a screenshot corpus around:

- Apple/AGEasy legal boilerplate,
- IDFC no-code modal,
- BigBasket previous/current-card expiry,
- MakeMyTrip foreground code with background no-code cards,
- Leaf/low-confidence outputs,
- multi-card wallet screens.

Required checks for Android changes:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

When the phone build may be stale:

```bash
/Users/C/Library/Android/sdk/platform-tools/adb devices
/Users/C/Library/Android/sdk/platform-tools/adb install -r <debug-apk>
/Users/C/Library/Android/sdk/platform-tools/adb shell dumpsys package com.example.coupontracker
```

## Code Hygiene

Prefer routing, scoping, and evidence checks over keyword patches.

Rules:

- Do not add brand-specific extraction rules unless explicitly intended and
  documented.
- Do not globally rewrite OCR text when a local/scoped repair is possible.
- Rupee glyph fixes need supporting evidence; do not rewrite all `7xxx*`
  amounts.
- Keep parser errors user-safe and store raw details in debug evidence.
- Keep `debugVisionEvidence` debug-focused, not UI-critical.
- Keep Room migrations explicit and schema files updated.
- Do not mark a coupon `VISION_VERIFIED` just because Gemma ran.

Known hygiene risk:

- merge behavior is split between worker-specific logic and reusable vision
  merge policies. Future cleanup should reduce drift without changing behavior.
