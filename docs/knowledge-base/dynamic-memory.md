# Dynamic Memory

This file captures dated project lessons, recent fixes, device observations,
and branch-specific decisions. For stable rules, use [Static Memory](static-memory.md).

Use [Knowledge Base Contract](knowledge-base-contract.md) to decide what belongs
here. Dynamic memory should capture dated fixes, commit summaries, device/logcat
observations, failed approaches, regression tests added, installed APK evidence,
known current bugs, and open follow-ups.

## 2026-06-28: VerifyCouponWorker Thin-Slice Use Case Extraction

Files changed:

- `app/src/main/kotlin/com/example/coupontracker/worker/VerifyCouponWorker.kt`
- `app/src/main/kotlin/com/example/coupontracker/domain/usecase/VerifyCouponUseCase.kt`
- `app/src/test/java/com/example/coupontracker/domain/usecase/VerifyCouponUseCaseTest.kt`

Why:

- `VerifyCouponWorker` mixed WorkManager orchestration with pure cleanup-state
  decisions, making future worker thinning risky.
- The first safe slice was to extract state transitions and verification gating
  without touching crop-first extraction, model prompts, merge policy, Room, or
  WorkManager enqueue behavior.

What it solves:

- The worker no longer owns the pure decisions for starting cleanup, holding a
  deterministic baseline in `RUNNING` while vision runs, deciding whether
  automatic vision verification should run, or merging latest cleanup state.

How it works:

- `VerifyCouponWorker` delegates pure cleanup-state decisions to
  `VerifyCouponUseCase`.
- The use case is Android-free and testable with a fixed clock, while the worker
  still owns repository IO, bitmap lifecycle, WorkManager result handling, and
  model/crop orchestration.

How good it is:

- Narrow architecture refactor. Behavior should be unchanged because the moved
  code is copied behind the same call sites and covered by focused tests.

Remaining risk:

- Low. The worker still owns most IO, bitmap/model work, deterministic cleanup,
  and persistence orchestration, so this is only the first thinning slice.

Tests/evidence:

- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests com.example.coupontracker.domain.usecase.VerifyCouponUseCaseTest`
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests com.example.coupontracker.worker.VerifyCouponWorkerTest`
- `git diff --check`

Follow-up:

- Continue moving deterministic cleanup construction, crop/model orchestration,
  and vision failure persistence into focused use cases or policies.

## 2026-06-28: Knowledge Base Update Gate For Code Changes

Files changed:

- `AGENTS.md`
- `docs/agentic-build-agents.md`
- `docs/knowledge-base/knowledge-base-contract.md`
- `docs/knowledge-base/dynamic-memory.md`

Why:

- Agents were able to diagnose and patch code, but the reasoning behind changes
  could remain only in chat or in a one-off audit.
- When the reason for a change is not captured in the repo, later agents must
  reconstruct it from diffs and can confuse current behavior with older audits.

What it solves:

- Every non-trivial code change now has an explicit knowledge-base update
  checkpoint.
- The update must explain why the change was made, what it solves, how it works,
  how good the fix is, remaining risk, tests/evidence, and follow-up.

How it works:

- Root `AGENTS.md` now requires a knowledge-base update decision whenever code
  changes.
- `docs/knowledge-base/knowledge-base-contract.md` defines the code-change entry
  format and quality scale.
- `docs/agentic-build-agents.md` makes the Knowledge Base Agent the automatic
  documentation gate after code-changing work and adds a handoff field for the
  KB decision.

How good it is:

- Good process fix. It makes future agent behavior explicit and reviewable.
- It is not a runtime automation or CI hook yet; it depends on agents following
  the repo instructions.

Remaining risk:

- A future agent can still forget the rule unless a CI/check script or commit
  hook validates KB updates against code diffs.

Tests/evidence:

- Documentation-only change. Verified with `git diff --check`.

Follow-up:

- Add a lightweight script or CI check that warns when `app/src/main` changes
  without a matching knowledge-base update or an explicit no-update rationale.

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

## 2026-06-28: Production Shortcut Removal And Routing Seams

### What Changed

- Removed the user-visible API diagnostics placeholder route and its dead
  `ApiTester`.
- Removed the model checksum bypass sentinel from model config/downloaders.
- Removed production Room destructive migration fallback.
- Removed the reachable native-LLM-unavailable stub that returned fake coupon
  data such as `Mock Store` and `MOCK50`.
- Added `SingleScanRoutingUseCase` as the first pure domain seam for scanner
  crop-count routing decisions.
- Updated extraction docs to reflect current crop-first scanner routing instead
  of old strategy-switched routes.

### Why

- User coupon data must not be wiped by a missing migration.
- Model failures must fail closed or route to review; they must never synthesize
  production-looking coupon fields.
- Scanner routing needs to move out of `ScannerViewModel` incrementally without
  changing crop-first behavior.
- Docs should describe active routes so future agents do not patch stale paths.

### How It Works

- Room now uses only explicit migrations registered in `CouponDatabase`.
- Model download config uses `null` for unknown upstream checksums; any supplied
  checksum must be a real SHA-256.
- `LlmRuntimeManager.createMLCEngine()` returns `null` when native runtime is
  unavailable instead of creating a mock inference engine.
- `ScannerViewModel.routeDetectedCouponCrops(...)` delegates crop-count route
  choice to `SingleScanRoutingUseCase`, while the ViewModel still performs UI
  state updates and persistence side effects.

### Verification

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Focused tests added:

- `CouponUseCaseBoundaryTest`
- `RealModelConfigTest`
- `SingleScanRoutingUseCaseTest`

### Remaining Risk

- `ScannerViewModel` still owns most scan orchestration and side effects.
- `BatchScannerViewModel` still owns batch orchestration.
- Screenshot regression corpus is still too small for the real coupon failures
  seen on device.
- Legacy XML fragments/navigation remain until a dedicated migration removes
  Safe Args references.

### Follow-Up

- Move layout routing and guarded fallback execution behind scanner use cases.
- Move batch routing into a batch use case/router.
- Expand real screenshot regression fixtures for BigBasket, MakeMyTrip,
  Lenskart no-code, crop OCR blank, and malformed Gemma JSON.

## 2026-06-28: Scanner And Batch Refactor Slice

### What Changed

- Added `SingleScanRoutingUseCase` for crop-count scanner route decisions.
- Added `BatchCaptureItemProcessor` for per-item batch routing.
- Added regression harness cases for MakeMyTrip, BigBasket, Lenskart no-code,
  and full-image evidence rejection.

### Why

- `ScannerViewModel` and `BatchScannerViewModel` are still too large, but route
  moves must be behavior-preserving.
- The safest first move is pure decision/routing code with focused tests while
  leaving UI state and persistence side effects in the existing ViewModels.
- Real screenshot failures need executable regression coverage before deeper
  extractor refactors.

### How It Works

- `SingleScanRoutingUseCase` maps detector availability and crop count to:
  layout-then-guarded-fallback, single-crop processing, or multi-coupon
  selection.
- `BatchCaptureItemProcessor` maps one selected batch item to PDF processing,
  unsupported-file failure, bitmap decode failure, or image coupon extraction
  while making bitmap release explicit.
- Regression harness tests exercise merge and crop-evidence contracts without
  requiring device Gemma runtime.

### Verification

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
```

Focused tests:

- `SingleScanRoutingUseCaseTest`
- `BatchCaptureItemProcessorTest`
- `ScreenshotExtractionRegressionHarnessTest`

### Remaining Risk

- ViewModels still own side-effect execution and UI state transitions.
- Layout route execution and guarded fallback execution are not yet extracted.
- Batch save/review orchestration is still in `BatchScannerViewModel`.

### Follow-Up

- Move scanner layout execution into a use case after adding route-state tests.
- Move batch save/review orchestration into a use case.
- Continue splitting field extraction only with screenshot fixture coverage.

## 2026-06-28: Full-Image Fallback Probe Extraction

### What Changed

- Added `FullImageFallbackProbe` under `extraction/capture`.
- `ScannerViewModel` no longer normalizes fallback OCR text or performs fallback
  screenshot classification directly.
- Existing fallback decision policy remains unchanged.

### Why

- Full-image fallback is a sensitive path because it can reintroduce background
  card contamination if it grows unchecked.
- Moving OCR-result normalization and classification into extraction/capture
  makes the behavior testable while preserving the ViewModel's UI/persistence
  responsibilities.

### How It Works

- The probe runs the provided OCR function, converts OCR success/error into
  `rawOcrText`, classifies non-blank text, and returns a
  `FullImageFallbackDecision`.
- The ViewModel logs the route and either runs guarded full-image OCR or saves a
  review coupon exactly as before.

### Verification

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests com.example.coupontracker.ui.viewmodel.ScannerViewModelFullImageFallbackTest
```

### Remaining Risk

- The guarded full-image path still exists for likely single screenshots.
- The next deeper slice is moving the final fallback execution/save behavior
  behind a use case or route executor.

### Follow-Up

- Extract layout-route execution from `ScannerViewModel`.
- Extract fallback save/review execution from `ScannerViewModel`.

## 2026-06-28: Fallback Review Factory And Batch Save Use Case

### What Changed

- Added `FullImageFallbackReviewCouponFactory`.
- Added `SaveBatchCouponsUseCase`.
- `ScannerViewModel` no longer owns fallback review coupon field/evidence
  construction.
- `BatchScannerViewModel` no longer owns the repository insert loop for saving
  processed batch coupons.

### Why

- Fallback review coupon metadata must remain consistent and tested because it
  protects against unsafe full-image extraction.
- Batch save behavior belongs at a use-case boundary, not inside UI state code.

### How It Works

- `FullImageFallbackReviewCouponFactory` builds the low-confidence review coupon
  with fallback guard evidence, raw OCR, image URI, and capture timestamp.
- `SaveBatchCouponsUseCase` inserts processed coupons in order through
  `CouponRepository`.
- ViewModels still own UI state, errors, and reset behavior.

### Verification

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests com.example.coupontracker.domain.usecase.SaveBatchCouponsUseCaseTest --tests com.example.coupontracker.extraction.capture.FullImageFallbackReviewCouponFactoryTest
```

### Remaining Risk

- Fallback review persistence and preview routing are still in
  `ScannerViewModel`.
- Batch save success/error UI handling is still in `BatchScannerViewModel`.

### Follow-Up

- Extract fallback review persistence/preview routing.
- Extract batch save result state mapping.
