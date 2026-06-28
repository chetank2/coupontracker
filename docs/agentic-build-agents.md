# Agentic Build Agents

These agents are development agents for building and repairing CouponTracker.
They do not run inside the Android app. They are repo workflows for human or AI
contributors to use when changing code, docs, tests, models, or release
artifacts.

Use one primary agent per task. Hand off only when the task crosses a boundary.
Each agent must preserve the root `AGENTS.md` rules.

## Shared Rules

- Read `AGENTS.md` first.
- Read `docs/knowledge-base/high-level/README.md` and
  `docs/knowledge-base/static-memory.md` before extraction, model, worker, or UI
  state changes.
- Keep changes narrow and behavior-preserving unless the task explicitly asks
  for a migration.
- Preserve crop-first extraction.
- Keep capture OCR-first and keep model cleanup explicit/background.
- Do not move Room entities, DAOs, workers, legacy fragments, or model adapters
  without a scoped migration plan and tests.
- Turn every real extraction failure into a regression test or documented
  playbook entry.
- After every non-trivial code change, run the Knowledge Base Agent update
  protocol. Do not leave why/what/quality/risk only in chat.
- Keep saved coupons traceable to screenshot, crop, OCR text, model output,
  validator decision, final database row, and review state.
- Never duplicate a coupon only because the screenshot URI, filename, or scroll
  position differs.
- Never invent expiry dates. Parse exact dates directly; calculate relative
  expiry from capture time when available; persist `expiryState=NOT_VISIBLE`
  when expiry is not visible.
- Brand-specific logic is allowed only as a documented, test-backed adapter or
  normalizer. Do not hide brand patches inside general extraction flow.

## Agent Roster

### 1. Repo Navigator Agent

Use when the task is unclear, touches many packages, or asks "where does this
work?"

Responsibilities:

- Build a function-level map from entrypoint to save/verify/UI.
- Identify current files, target package, risks, and tests before code edits.
- Separate current package reality from target architecture.

Primary docs:

- `docs/knowledge-base/roadmap.md`
- `docs/knowledge-base/refactor-roadmap.md`
- `docs/refactor/README.md`

Output:

- Short implementation map.
- Files to edit.
- Required checks.

### 2. Extraction Fix Agent

Use for wrong store, offer, code, expiry, multi-coupon, no-code, no-expiry, OCR
noise, or confidence issues.

Responsibilities:

- Trace the active path before changing code.
- Preserve this authority model:
  `VLM = layout/ownership/field states`, `OCR = exact text`,
  `validator = trust`.
- Prefer scoped routing, evidence, parser, normalizer, merge, or validation
  fixes over brand-specific patches.
- Treat expiry as protected:
  exact date -> save date, `expires in N days` -> calculate from capture date,
  `expires in N hours` -> save the closest supported timestamp/date behavior,
  not visible -> `expiryState=NOT_VISIBLE`.
- Add or update focused tests.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/capture/OcrFirstCouponExtractor.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/layout/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/rules/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/vision/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/validation/`
- `app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt`

Tests to consider:

- `TextExtractorTest`
- `CouponCodeExtractorTest`
- `PostOcrCouponNormalizerTest`
- `CouponExtractionConfidenceScorerTest`
- `VisionFieldJsonParserTest`
- `VisionOcrMergePolicyTest`
- `CouponFieldBundleValidatorTest`

### 3. Vision And Model Agent

Use for Qwen, Gemma, MiniCPM, model selection, retry, model install, or vision
schema work.

Responsibilities:

- Keep text and vision model roles separate.
- Use raw vision extraction for layout/state schemas.
- Never let a VLM invent exact coupon codes.
- Preserve configured retry modes; do not coerce user-selected text modes into
  vision modes.
- Verify schema parsing and failure fallback.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/extraction/model/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/retry/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/layout/VlmCouponLayoutDetector.kt`
- `app/src/main/kotlin/com/example/coupontracker/extraction/vision/`
- `app/src/main/kotlin/com/example/coupontracker/llm/`
- `app/src/main/kotlin/com/example/coupontracker/runtime/`

Primary docs:

- `docs/extraction/vlm_retry.md`
- `docs/extraction/model_strategy.md`
- `docs/gemma_mobile_reader_plan.md`

### 4. Worker And Verification Agent

Use for Verify button, cleanup status, WorkManager, background verification, or
stale/partial cleanup writes.

Responsibilities:

- Keep enqueue logic, WorkManager constraints, and Hilt worker construction
  intact.
- Prevent duplicate verification jobs.
- Do not mark `VISION_VERIFIED` only because Gemma ran.
- Do not use full-screen OCR as crop proof.
- Keep parser/model errors user-safe and debug details in evidence fields.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/worker/VerifyCouponWorker.kt`
- `app/src/main/kotlin/com/example/coupontracker/work/`
- `app/src/main/kotlin/com/example/coupontracker/ui/details/`
- `app/src/main/kotlin/com/example/coupontracker/ui/settings/`

Tests to consider:

- Worker/enqueue tests when practical.
- Vision parse failure tests.
- Merge-policy tests for partial cleanup.

### 5. UI State Agent

Use for home, detail, scanner, review, model settings, or state display issues.

Responsibilities:

- Make uncertainty visible.
- Show `No code needed` from `codeState=NO_CODE_NEEDED`.
- Show `Not visible` from `expiryState=NOT_VISIBLE`.
- Show `Needs review` for low confidence, contradictions, unsupported values,
  parse failures, or layout failures.
- Keep ViewModels thin; move business behavior to use cases or extraction
  classes.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/ui/components/`
- `app/src/main/kotlin/com/example/coupontracker/ui/home/`
- `app/src/main/kotlin/com/example/coupontracker/ui/details/`
- `app/src/main/kotlin/com/example/coupontracker/ui/review/`
- `app/src/main/kotlin/com/example/coupontracker/ui/settings/`
- `app/src/main/kotlin/com/example/coupontracker/ui/model/`

### 6. Data And Migration Agent

Use for Room, repositories, persisted field states, schema files, dedupe, or
entity/domain split work.

Responsibilities:

- Do not move Room entities or DAOs casually.
- Verify table names, migrations, converters, schema export, and repository
  behavior.
- Keep persisted field states separate from exact coupon values.
- Protect `CouponRepositoryImpl.saveOrMergeCoupon()` behavior.
- Keep dedupe behavior explicit:
  primary key is normalized store/app plus normalized coupon code when code is
  present; fallback is normalized store/app plus offer value, normalized
  description, and expiry when no code exists.
- Preserve no-code and no-expiry coupons as first-class states instead of
  converting missing values into fake text.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/data/local/`
- `app/src/main/kotlin/com/example/coupontracker/data/model/`
- `app/src/main/kotlin/com/example/coupontracker/data/repository/`
- `app/schemas/`

Checks:

- Room migration tests when schema changes.
- Unit tests around dedupe/merge behavior when repository logic changes.

### 7. Refactor Slice Agent

Use for package moves or layered architecture cleanup.

Responsibilities:

- Move one package slice at a time.
- Preserve behavior first, improve structure second.
- Update imports, Hilt modules, navigation/Safe Args, tests, and docs in the
  same slice.
- Avoid broad "cleanup" commits that mix UI, extraction, data, and worker moves.

Primary docs:

- `docs/refactor/packages/*.md`
- `docs/knowledge-base/refactor-roadmap.md`

Required output:

- Before/after package map.
- Risk list.
- Tests run.

### 8. Test And Device Verification Agent

Use when code changed, the phone app looks stale, Gradle fails, or behavior must
be proven.

Responsibilities:

- Run the smallest meaningful test first, then required checks.
- Use Java 17 for Gradle.
- If UI/source changes are not visible on device, rebuild, install, and verify
  the installed package.
- Prefer evidence from tests, logs, database rows, screenshots, and code paths.

Commands:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Device commands:

```bash
/Users/C/Library/Android/sdk/platform-tools/adb devices
/Users/C/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
/Users/C/Library/Android/sdk/platform-tools/adb shell dumpsys package com.example.coupontracker
```

### 9. Release Agent

Use for APK/AAB, signing, store upload, version bumps, target SDK, or store
metadata.

Responsibilities:

- Prefer signed release artifacts for distribution tasks.
- Verify version, target SDK, package name, and signing before reporting done.
- If a store rejects an upload, fix the blocking field and rebuild.

Primary docs:

- `docs/store/`
- `config/version.properties`
- `keystore.properties.example`

Useful checks:

- `aapt dump badging`
- `apksigner verify --verbose --print-certs`

### 10. Knowledge Base Agent

Use after any non-trivial code change, discovery, repeated failure, architecture
decision, or completed refactor slice. This agent is the automatic documentation
gate for code-changing work.

Responsibilities:

- Inspect `git diff --name-only` and identify changed code, tests, migrations,
  docs, model/config, or release files.
- Decide whether the change belongs in static memory, dynamic memory, a detailed
  topic doc, a source map, a failure playbook, or no KB entry.
- For every non-trivial code change, record:
  why it was done, what it solves, how it works, how good the fix is, remaining
  risk, and tests/evidence.
- Keep durable rules in static memory.
- Keep dated/device-specific lessons in dynamic memory.
- Add failure playbook entries for real bugs.
- Do not leave final project knowledge only in private chat notes.
- If no KB update is needed, write the reason in the handoff/final answer.

Primary docs:

- `docs/knowledge-base/knowledge-base-contract.md`
- `docs/knowledge-base/static-memory.md`
- `docs/knowledge-base/dynamic-memory.md`
- `docs/knowledge-base/roadmap.md`

Required output:

```text
KB decision:
Files changed:
Entry target:
Why:
What it solves:
How it works:
Quality: Durable | Good | Temporary | Risky
Remaining risk:
Tests/evidence:
Follow-up:
```

### 11. Dataset And Annotation Agent

Use for screenshot fixtures, golden JSON, duplicate groups, partial cards,
annotation exports, and real-world coupon examples.

Responsibilities:

- Keep real coupon screenshots grouped by scenario and expected result.
- Mark duplicate, overlapping, partial, background, foreground-modal, no-code,
  and no-expiry cases explicitly.
- Maintain expected coupon JSON for regression tests.
- Keep fixture names and metadata stable so failures can be reproduced.
- Route ambiguous labels to review instead of forcing fake certainty.

Primary folders:

- `multi_coupon_training/`
- `coupon-training/`
- `mobile-coupon-trainer/`
- `benchmark/goldenset/`
- future `testdata/coupons/`
- future `docs/extraction/failures/`

Output:

- Screenshot set.
- Expected coupons.
- Known duplicate groups.
- Partial or uncertain cards.
- Required regression tests.

### 12. Extraction Regression Harness Agent

Use when real screenshots need to prove extraction quality beyond unit tests.

Responsibilities:

- Run screenshot-to-coupon regression checks.
- Compare actual results against expected coupon JSON.
- Report missing coupons, wrong fields, duplicate saves, false positives, and
  false negatives.
- Track field-specific outcomes for store/app name, code, offer, expiry,
  expiry state, duplicate count, confidence state, and review state.
- Add every confirmed real failure as a fixture before or with the fix.

Primary files and folders:

- `benchmark/`
- `multi_coupon_training/`
- `app/src/androidTest/java/com/example/coupontracker/extraction/`
- future `testdata/coupons/`
- future extraction harness scripts under `scripts/` or `benchmark/`

Output:

- Pass/fail summary by screenshot.
- Field mismatch table.
- Duplicate/false-positive report.
- Fixture updates needed before code changes.

### 13. Evidence And Observability Agent

Use for logs, debug screens, extraction traces, confidence reports, saved
evidence, and device/database investigations.

Responsibilities:

- Make every saved coupon traceable from source screenshot to final database
  row.
- Preserve crop bounds, OCR raw text, extraction source, confidence score,
  field states, failure reason, and model/debug evidence when available.
- Ensure user-facing errors are safe while raw parser/model details remain
  available for debugging.
- Check that `needsAttention`, `cleanupStatus`, `cleanupError`,
  `extractionSource`, and `debugVisionEvidence` tell the same story.
- Prefer structured logs and debug snapshots over ad hoc log strings.

Primary files:

- `app/src/main/kotlin/com/example/coupontracker/debug/`
- `app/src/main/kotlin/com/example/coupontracker/analytics/`
- `app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt`
- `app/src/main/kotlin/com/example/coupontracker/worker/VerifyCouponWorker.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/details/`

Evidence checklist:

- Source screenshot URI.
- Crop bounds or layout card bounds.
- Raw OCR text for the active crop.
- Model raw/canonical JSON when used.
- Validator/scorer decision.
- Final database row fields and states.
- User-visible review/cleanup state.

### 14. Code Health Audit Agent

Use when the task asks how much code is trash, shortcut-driven,
non-modular, hardcoded, overfitted to one coupon, or drifting away from the
ideal flow in the knowledge base.

This agent does not fix code first. It audits and ranks code so the next agent
can delete, replace, or refactor the right parts.

Responsibilities:

- Compare implementation against the ideal flow:
  `full screenshot -> layout ownership -> crop -> crop OCR -> VLM field
  states -> merge -> validation/scoring -> save or review`.
- Identify code that bypasses crop-first ownership, uses full-screen OCR as
  proof, trusts model output without evidence, or saves before trust is known.
- Identify non-modular code: oversized classes, mixed UI/domain/data/model
  responsibilities, duplicated merge logic, and business behavior buried in
  ViewModels/workers.
- Identify hardcoding:
  brand names, merchant names, exact phrases, fixed screen percentages,
  stopword patches, regex piles, model-output workarounds, timeout tuning used
  as architecture, and one-off data repairs.
- Separate acceptable deterministic rules from brittle hardcoded patches.
- Produce a percentage estimate and a deletion/refactor priority list, but only
  with cited files/lines.

Primary docs:

- `docs/knowledge-base/high-level/README.md`
- `docs/knowledge-base/static-memory.md`
- `docs/knowledge-base/refactor-roadmap.md`
- `docs/refactor/rules/anti-hardcoded-rules.md`
- `docs/refactor/rules/universal-extraction-solution-rules.md`
- `docs/refactor/rules/code-structure.md`

Primary files to inspect:

- `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/`
- `app/src/main/kotlin/com/example/coupontracker/worker/`
- `app/src/main/kotlin/com/example/coupontracker/extraction/`
- `app/src/main/kotlin/com/example/coupontracker/util/`
- `app/src/main/kotlin/com/example/coupontracker/universal/`
- `app/src/main/kotlin/com/example/coupontracker/data/repository/`

Audit dimensions:

```text
Ideal-flow drift:
  0 = follows knowledge-base flow
  1 = minor ordering/provenance issue
  2 = bypasses one stage
  3 = directly violates crop-first/evidence/validator authority

Modularity debt:
  0 = single responsibility and reusable
  1 = small mixed concern
  2 = multiple concerns or duplicated logic
  3 = god class / hidden business logic / hard to test

Hardcoding debt:
  0 = generic evidence-backed rule
  1 = configurable or documented heuristic
  2 = brittle regex/phrase/threshold without strong fixture coverage
  3 = brand-specific, screen-specific, or model-output-specific patch

Test reality:
  0 = covered by real screenshot regression
  1 = covered by focused unit test
  2 = only happy-path or synthetic test
  3 = no meaningful test
```

Output format:

```text
Summary:
  Trash/replace estimate:
  Refactor estimate:
  Acceptable heuristic estimate:
  Keep estimate:

Top violations:
  [P1] file:line - why it violates ideal flow
  [P2] file:line - why it is non-modular
  [P2] file:line - why it is hardcoded

Module scores:
  Module/path | Ideal-flow drift | Modularity debt | Hardcoding debt | Test reality | Action

Trash bucket:
  Delete/replace:
  Refactor:
  Isolate behind adapter/config:
  Keep:

Next agent:
  Refactor Slice Agent / Extraction Fix Agent / Dataset And Annotation Agent /
  Extraction Regression Harness Agent / Knowledge Base Agent
```

Judgment rules:

- Do not call code trash just because it is old. Call it trash when it blocks
  the ideal flow, creates repeated failures, cannot be tested, or encodes one
  screenshot/brand/model mistake as a global rule.
- Do not count stable domain constants, schema enums, Room table names,
  resource IDs, or documented model modes as hardcoding.
- Regex is acceptable only when it is field-specific, evidence-scoped,
  documented by tests, and not compensating for missing layout ownership.
- Brand-specific code is acceptable only inside an explicit adapter or
  documented normalizer with fixtures. Hidden brand `if` branches count as
  hardcoding debt.
- Fixed crop percentages count as hardcoding debt unless they are temporary,
  documented, and guarded by review-only behavior.
- Parser tolerance must not silently upgrade malformed model output into trusted
  fields. Malformed output should become evidence plus review unless crop OCR
  and validators support it.

## Suggested Multi-Agent Flow

For code health/trash audits:

```text
Code Health Audit Agent
-> Repo Navigator Agent, only if a flow map is missing
-> Dataset And Annotation Agent, when audit needs real screenshots
-> Extraction Regression Harness Agent, when claims need proof
-> Refactor Slice Agent or Extraction Fix Agent
-> Knowledge Base Agent
```

For extraction bugs:

```text
Repo Navigator Agent
-> Extraction Fix Agent
-> Vision And Model Agent, only if model/schema behavior is involved
-> Dataset And Annotation Agent, when the failure comes from a real screenshot
-> Extraction Regression Harness Agent
-> Evidence And Observability Agent, when logs/debug state are unclear
-> Test And Device Verification Agent
-> Knowledge Base Agent
```

For UI state bugs:

```text
Repo Navigator Agent
-> UI State Agent
-> Evidence And Observability Agent, when state/provenance is unclear
-> Test And Device Verification Agent
-> Knowledge Base Agent
```

For package refactors:

```text
Repo Navigator Agent
-> Refactor Slice Agent
-> Data And Migration Agent or Worker And Verification Agent, only if touched
-> Evidence And Observability Agent, if persisted/debug fields change
-> Test And Device Verification Agent
-> Knowledge Base Agent
```

For release/store work:

```text
Release Agent
-> Test And Device Verification Agent
-> Knowledge Base Agent, only if a new blocker or process lesson appears
```

## Handoff Format

When one agent hands off to another, include:

```text
Goal:
Files changed:
Current behavior:
Dataset/fixtures touched:
Evidence available:
Known risk:
Tests already run:
Knowledge base decision:
Next required check:
```
