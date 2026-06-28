# Knowledge Base Roadmap

This roadmap tracks what the knowledge base still lacks compared with the
architecture, product, extraction, and testing plans developed during the
CouponTracker refactor.

Use this as the backlog for improving project knowledge. It is not an app
feature roadmap.

## Current Gaps

For codebase migration order and package-level refactor gates, use
[Refactor Roadmap](refactor-roadmap.md).

### 1. End-To-End Function Map

The docs say the app is crop-first and state-aware, but they do not yet provide
a complete function-level routing map.

Needed:

```text
scan/import entrypoint
-> crop/layout routing
-> OCR extraction
-> VLM layout/field-label path
-> validation/scoring
-> save/merge
-> post-save verification worker
-> UI rendering
```

The map should name the main classes/functions and distinguish active lanes:

- URI upload flow,
- camera/captured bitmap flow,
- multi-coupon preview flow,
- OCR-first fallback,
- Verify button / WorkManager flow,
- repository save/merge boundary.

### 2. Source-Of-Truth Package Map

The docs now clarify current vs target package shape, but the package map is
still narrative. It should become a table.

Needed columns:

```text
Concern | Current package/files | Target package | Move status | Risks | Tests
```

Include:

- `data/local`, `data/model`,
- `worker`, `work`,
- `llm`, `ml`, `model`, `runtime`, `verification`,
- `ocr`,
- `extraction/model`,
- `ui/viewmodel`, `ui/screen`, `ui/fragment`,
- `di`, `util`.

### 3. VLM/OCR Contract Spec

The knowledge base has rules, but it needs a formal contract spec.

Needed:

- layout JSON schema,
- field-label JSON schema,
- accepted field states,
- rejected states,
- examples of valid/invalid Gemma output,
- merge rules,
- review rules,
- what full screenshot VLM may do,
- what crop VLM may do,
- what VLM must never do.

Important invariant:

```text
Layout pass may select a crop.
Field-label pass may classify visible text.
Neither pass may invent exact coupon codes.
```

### 4. Failure Playbook By Coupon Type

The dynamic memory contains recent failures, but there is no structured
playbook grouped by coupon shape.

Needed groups:

- foreground modal over background cards,
- no-code benefit modal,
- no-expiry visible coupon,
- multi-card wallet screen,
- single card with expiry badge above offer,
- offer with legal boilerplate,
- OCR rupee glyph artifact,
- visible code without explicit "code:" label,
- stale saved coupon after worker failure.

Each entry should include:

```text
Symptom
Likely cause
Files to inspect
Fix pattern
Regression test location
Device/logcat/DB check
```

### 5. UI State Matrix

The docs say to show `No code needed`, `Not visible`, and `Needs review`, but
they do not fully specify UI behavior.

Needed matrix:

```text
codeState + redeemCode -> home card display -> detail display -> copy button
expiryState + expiryDate -> display text -> review badge
cleanupStatus + needsAttention -> verify button state -> status message
layoutState -> review severity
```

Include expected behavior for:

- `NO_CODE_NEEDED`,
- `NOT_VISIBLE`,
- `UNKNOWN`,
- `LOW_CONFIDENCE`,
- `FAILED`,
- `PENDING`,
- `RUNNING`,
- `CLEANED`.

### 6. Testing Matrix

The docs list test classes, but they need a concrete matrix that maps risk to
tests.

Needed:

```text
Risk | Required unit test | Optional worker/device test | Screenshot corpus case
```

Must cover:

- MakeMyTrip foreground code + background no-code,
- BigBasket previous-card expiry vs current-card expiry,
- IDFC no-code modal,
- Apple/AGEasy legal boilerplate,
- Leaf low-confidence parse/review,
- cropped vision verification with blank crop OCR must route to review and must
  not use full-screen OCR as field proof,
- rupee artifact repair without corrupting 7999*,
- malformed/unterminated Gemma JSON,
- Verify button duplicate tap behavior.

### 7. Device Debugging Runbook

Device checks are spread across conversation history. The repo needs a runbook.

Needed commands:

- install latest debug APK,
- verify package version/time,
- pull latest coupon images,
- pull Room DB/WAL/SHM,
- inspect latest coupon rows,
- inspect logcat tags,
- verify Gemma ran,
- distinguish OCR capture from Gemma verification.
- confirm cropped verification proof came from crop OCR/debug evidence, not
  full-screen OCR, and force review when crop OCR is blank.

Include the package name:

```text
com.example.coupontracker
```

### 8. Review Checklist

The knowledge base should include a review checklist for future patches.

Needed checks:

- Does capture remain OCR-first?
- Does final field extraction use crop when available?
- Can no-code override a supported code?
- Can VLM code be saved without OCR evidence?
- Can cropped vision verification use full-screen OCR as proof? It must not.
- Does blank crop OCR route to review?
- Can `PRESENT` expiry save without `expiryDate`?
- Can low-confidence layout save directly?
- Are Room migrations/schema files correct?
- Are UI states consistent with persisted field states?
- Are tests tied to the real failure?

### 9. Open Technical Debt

The docs mention code hygiene, but should track specific debt.

Current debt to document:

- merge logic is split between `VerifyCouponWorker` and reusable merge policies,
- regex/heuristic growth needs consolidation and tests,
- `worker` and `work` are still split,
- `ai/` package is target-only and not implemented,
- Room entity/domain split is not done,
- multiple scanner/capture lanes still need a unified source map,
- review UI should say which field is uncertain.

## Roadmap

### Phase 1: Make Current Behavior Navigable

Goal: a new agent can trace the app without guessing.

Add:

- end-to-end function map,
- current package/source map table,
- device debugging runbook,
- UI state matrix.

Definition of done:

- Every major behavior points to files/classes.
- Current vs target package ownership is unambiguous.
- A connected-phone issue can be investigated from docs alone.

### Phase 2: Formalize Extraction Contracts

Goal: prevent architecture drift.

Add:

- VLM layout contract spec,
- VLM field-label contract spec,
- OCR exact-text contract,
- merge/review rules,
- examples of valid and invalid JSON.

Definition of done:

- Full-screen Gemma and crop Gemma responsibilities are separate.
- No-code/no-expiry behavior is explicitly specified.
- Exact code trust rules are impossible to miss.
- Cropped vision verification cannot borrow full-screen OCR proof; blank crop
  OCR remains reviewable.

### Phase 3: Build Failure And Testing Playbooks

Goal: every real bug becomes reproducible.

Add:

- coupon-type failure playbook,
- testing matrix,
- screenshot corpus index,
- regression-test naming conventions.

Definition of done:

- A failure report maps to the test file to update.
- Known failures have expected behavior and regression coverage.
- P1 crop-proof failures have both automated and device/debugging coverage.

### Phase 4: Add Product And Design Operating Rules

Goal: UI and product behavior stay consistent with extraction states.

Add:

- field-state UI matrix,
- review/verification UX rules,
- detail/home display rules,
- copy-button behavior,
- status/error message rules.

Definition of done:

- UI never hides uncertainty.
- UI never shows no-code when a supported code exists.
- Raw parser/model errors do not leak to users.

### Phase 5: Track Refactor Debt And Migration Plans

Goal: future cleanup is deliberate instead of accidental.

Add:

- `worker`/`work` consolidation plan,
- `ai/` target migration plan,
- Room entity/domain split plan,
- merge-policy consolidation plan,
- regex heuristic retirement/consolidation plan.

Definition of done:

- Target package moves have prerequisites and tests.
- No one moves Room/model/worker code without a scoped migration plan.

## Maintenance Rules

Update static memory when a lesson becomes durable.

Update dynamic memory when a lesson is dated, branch-specific, or device
observed.

Update the failure playbook when:

- a device screenshot exposes a new failure,
- a logcat/DB check explains a bug,
- a regression test is added.

Update the source map when:

- a major function moves,
- a new extraction lane is added,
- Room/worker/model ownership changes.

Update testing docs when:

- a new required test class is introduced,
- a device command changes,
- a flaky or blocked check needs a known workaround.
