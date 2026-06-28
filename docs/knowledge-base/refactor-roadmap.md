# Refactor Roadmap

This roadmap is for codebase refactoring only. It is separate from product and
extraction behavior roadmaps.

The main risk in this repo is not that code is merely messy. The main risk is
that moving code can silently change extraction behavior, Room schema, Hilt
bindings, WorkManager scheduling, navigation, or model loading.

## Current Package Map

Current reality:

- `ui/*`: feature UI packages that already exist.
- `ui/viewmodel`: legacy and active ViewModels.
- `ui/fragment`: legacy fragments and XML navigation callers.
- `ui/screen`: Compose screens.
- `data/local`: current Room database, DAO, converters.
- `data/model`: current Room-backed models/entities such as `Coupon`.
- `data/repository`: repositories and save/merge boundaries.
- `domain/usecase`: current and future use-case orchestration seams.
- `extraction/*`: extraction rules, capture, layout, merge, validation, vision,
  quality, retry, deterministic cleanup.
- `extraction/model`: current model adapter interfaces and local model wrappers.
- `llm`, `llm/gemma`: current LLM/Gemma runtime-related code.
- `ml`: current ML/detector-related code.
- `model`: current model catalog/path metadata.
- `ocr`: current OCR package.
- `verification`: current verification-related package.
- `worker`: current verification/cleanup/background workers.
- `work`: current reminder worker package.
- `di`: Hilt modules and bindings.
- `runtime`: device/runtime capability and tier policy.
- `util`: shared utilities and legacy extraction helpers.
- `receiver`: boot/broadcast receivers.

## Target Package Map

Target shape:

```text
com.example.coupontracker/
  ui/
  domain/
  data/
  extraction/
  ai/        future only
  worker/
```

Important:

- `ai/` is not current. It is a future consolidation target.
- `data/db` is not current. Room remains in `data/local` and `data/model` until
  a dedicated entity/domain split.
- `worker` is the target background-work home, but `work` still exists.

## Safe Move Order

Move code by risk, not by aesthetic package name.

Recommended order:

1. Pure helpers with no Android/Room/Hilt dependencies.
2. Extraction rules and their focused tests.
3. Domain use cases around existing repositories/models.
4. Repository boundary helpers and mappers that do not alter Room schema.
5. Compose-only leaf UI screens.
6. ViewModels after use cases exist.
7. Worker enqueue helpers.
8. Model/AI package consolidation.
9. Room entity/domain split.
10. Legacy fragments and XML navigation.

Do Room and navigation late. They carry the highest data-loss and compile-time
breakage risk.

## Area Plans

### UI Refactor

Goal:

- feature UI packages own screen rendering,
- ViewModels stay thin,
- product logic moves to use cases or extraction/domain services.

Move order:

1. Compose leaf components.
2. Screen-level state models.
3. Feature ViewModels after use cases exist.
4. Legacy fragments only after navigation imports and Safe Args are verified.

Risks:

- broken navigation routes,
- duplicated UI state,
- extraction logic accidentally left in UI,
- user-visible state mismatch.

Verification:

- compile,
- relevant UI/unit tests,
- manual/screenshot check for changed screens when needed.

### Scanner And Extraction Orchestration

Goal:

- scanner routes images and user intent,
- extraction pipeline owns crop/OCR/VLM/validation behavior,
- no final field extraction on full multi-card screenshots when a crop exists.
- cropped vision verification never treats full-screen OCR as proof; blank crop
  OCR routes to review.

Move order:

1. Pure extraction helpers.
2. OCR/rule extraction.
3. layout/crop routing helpers.
4. scanner orchestration seams.
5. ViewModel cleanup.

Current progress:

- `SingleScanRoutingUseCase` owns the first crop-count route decision:
  detector unavailable, zero crops, one crop, or multiple crops.
- `BatchCaptureItemProcessor` owns per-item batch routing for PDF,
  unsupported files, bitmap decode failure, image extraction dispatch, and
  bitmap release.
- `ScannerViewModel` still owns route execution, UI state, persistence, and
  fallback side effects.
- Next safe slice is moving layout-route execution or guarded fallback execution
  into an extraction/domain use case while preserving the same UI states.

Risks:

- changed extraction order,
- full-screen OCR fallback beating crop,
- full-screen OCR being reused as final proof after crop OCR is blank,
- duplicate multi-coupon paths,
- regression in no-code/no-expiry states.

Verification:

- focused `TextExtractor`, `CouponCodeExtractor`, normalizer, layout, and merge
  tests,
- P1 regression test for blank crop OCR routing to review instead of borrowing
  full-screen OCR proof,
- screenshot corpus checks for known failures.

### Room Entity/Domain Split

Goal:

- Room entities are persistence details,
- domain models are pure app concepts,
- repositories map between them.

Move order:

1. Document current schema.
2. Introduce entity/domain types without changing table names.
3. Add mappers and round-trip tests.
4. Update repositories.
5. Add migration/schema tests.
6. Remove temporary aliases.

Risks:

- schema drift,
- data loss,
- default enum/value changes,
- broken dedupe/save behavior,
- migration mismatch.

Verification:

- Room schema diff,
- migration tests,
- repository round-trip tests,
- save/merge regression tests.

### Worker And Work Consolidation

Goal:

- background jobs and enqueue helpers live under `worker`,
- workers stay thin,
- business behavior lives in use cases/repositories.

Move order:

1. Add enqueue helper tests where possible.
2. Move reminder worker from `work` in a dedicated slice.
3. Update Hilt/manifest/receiver imports.
4. Centralize unique work names and constraints.
5. Verify cleanup/reminder behavior.

Risks:

- Hilt WorkerFactory breakage,
- unique work chain changes,
- duplicate or missing reminders,
- Verify button scheduling regressions.

Verification:

- compile,
- worker decision tests,
- manual WorkManager/device check when behavior is user-visible.

### AI And Model Package Consolidation

Goal:

- future `ai/model`, `ai/cleanup`, and `ai/verification` own model management,
  cleanup, and verification adapters,
- capture remains OCR-first.

Current code remains under:

- `extraction/model`,
- `llm`,
- `ml`,
- `model`,
- `runtime`,
- `verification`.

Move order:

1. Define interfaces and target ownership.
2. Move model metadata/catalog code only after tests exist.
3. Move adapters in one Hilt-aware slice.
4. Move cleanup/verification callers.
5. Keep compatibility aliases only temporarily.

Risks:

- Hilt binding failures,
- model path/import failures,
- scanner accidentally becoming model-first,
- Gemma/Qwen role confusion.

Verification:

- model strategy tests,
- model import/settings tests,
- verification worker tests,
- manual model availability check when needed.

### Util Cleanup

Goal:

- reduce legacy `util` catch-all behavior,
- move helpers to owners without changing behavior.

Move order:

1. classify helpers by owner,
2. move pure helpers first,
3. add tests before moving extraction-sensitive utilities,
4. leave adapters/aliases temporarily only when needed.

Risks:

- hidden behavior changes,
- circular dependencies,
- duplicate helper logic,
- lost Android context assumptions.

Verification:

- focused unit tests per helper,
- extraction regression tests for extraction utilities.

### Validation And Scoring Consolidation

Goal:

- trust/review logic is centralized and consistent,
- low-confidence layout, unknown field states, unsupported codes, and
  contradictions cannot direct-save.

Move order:

1. document current validators/scorers,
2. add tests for each trust rule,
3. extract shared policy,
4. remove duplicated worker/merge logic after behavior is covered.

Risks:

- `VISION_VERIFIED` applied too broadly,
- no-code/no-expiry punished as missing,
- unsupported stale code preserved,
- low-confidence layout direct-saved.

Verification:

- confidence scorer tests,
- merge policy tests,
- worker tests for user-requested and automatic verification.

## Risk Register

| Risk | Impact | Guardrail |
| --- | --- | --- |
| Move Room entity wrongly | schema drift or data loss | schema/migration tests before merge |
| Move worker wrongly | Hilt WorkerFactory or unique work breaks | compile plus worker/enqueue tests |
| Move nav fragment wrongly | Safe Args or route breakage | compile plus nav import review |
| Move extraction helper wrongly | silent extraction behavior change | focused regression tests |
| Move model adapter wrongly | model loading or Hilt binding fails | model strategy/import tests |
| Consolidate `util` too fast | circular deps or hidden behavior changes | pure helpers first |
| Change scorer during refactor | false direct-save or false review | scorer test matrix |
| Reuse full-screen OCR as crop proof | false vision verification | blank-crop OCR review test |

## Verification Gates

For every refactor slice:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Extra gates:

- Room move: schema diff, migration tests, repository round-trip tests.
- Worker move: enqueue/WorkManager tests and user-visible scheduling check.
- UI move: compile plus screenshot/manual check for changed screens.
- Extraction move: focused regression tests and screenshot corpus checks.
- Model move: model settings/import tests and verification-worker tests.

## Definition Of Done

Every refactor slice must satisfy:

- behavior unchanged unless explicitly intended,
- imports updated,
- tests pass,
- no Room schema drift unless intentionally migrated,
- no package alias left undocumented,
- no unrelated cleanup mixed in,
- no capture/model authority change,
- no final field extraction on full multi-card screenshots when a crop exists,
- no cropped vision verification proof from full-screen OCR; blank crop OCR must
  remain reviewable.

## Documentation Updates Required

Update this roadmap when:

- a package move completes,
- a target package changes,
- a migration is deferred,
- a new refactor risk appears,
- a verification gate changes.

Update [Static Memory](static-memory.md) when a refactor rule becomes durable.

Update [Dynamic Memory](dynamic-memory.md) when a refactor bug or migration
lesson is discovered during implementation.
