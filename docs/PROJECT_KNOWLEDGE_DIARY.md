# CouponTracker Project Knowledge Diary

Last updated: 2026-06-19

This is the broad project diary for CouponTracker. It is intentionally wider than
the refactor diary in `docs/refactor/knowledge-diary-2026-06-19.md`.

Use this file when a future contributor or agent needs to understand how the app
evolved from the beginning, what went wrong across the main product surfaces, and
which rules must be preserved.

## Current Branch Context

Current branch when this diary was written:

```text
feature/qwen-multi-coupon-extraction
```

Remote:

```text
origin https://github.com/chetank2/coupontracker.git
```

Remote refs were fetched with:

```bash
git fetch --all --prune
```

Important branch note:

- `feature/qwen-multi-coupon-extraction` contains the active extraction, Qwen,
  Gemma, multi-coupon, UI, and refactor work.
- `origin/main` has some publishing/review-document commits that are not fully
  identical to this feature branch.
- Do not assume branch parity. Compare before merging.

## What The App Is

CouponTracker is a coupon recognition and coupon wallet system.

It has three major product surfaces:

1. Android app
   - Scan/import coupon screenshots.
   - Extract store, offer, code, expiry, payment/minimum purchase fields.
   - Save coupons locally.
   - Review, clean, share, delete, and manage coupons.
   - Use OCR, deterministic rules, and local AI models.

2. Python/web training interface
   - Collect coupon samples.
   - Annotate fields.
   - Train/evaluate model assets.
   - Package model outputs for Android.

3. Mobile PWA/offline annotation tool
   - Mobile-friendly annotation.
   - IndexedDB storage.
   - Service worker/offline workflows.

The current Android app is the highest-priority product surface in this branch.

## Timeline From The Beginning

### Phase 1: Coupon Recognition Foundation

The original system was a coupon recognition app backed by classic OCR and
pattern recognition.

Main ideas:

- Use OCR to read coupon screenshots.
- Use regex/rules to identify:
  - merchant/store
  - description/offer
  - expiry
  - redeem code
  - amount/cashback/discount
- Store extracted coupons in Room.
- Provide an Android wallet UI.

Early training docs show a Tesseract/OpenCV-oriented workflow:

- `coupon-training/README.md`
- `coupon_scraper.py`
- `image_processor.py`
- `coupon_annotator.py`
- `coupon_trainer_cli.py`
- `update_app.py`

Important early lesson:

```text
OCR alone is not enough. Field association is the hard problem.
```

### Phase 2: Training Pipeline And Annotation Tools

The project added a training environment for coupon element detection:

- Raw/processed/annotated data folders.
- Tesseract box-file generation.
- Preprocessing and augmentation.
- Web UI for annotation.
- Model evaluation and Android export.

Why this mattered:

- The Android app needed better field detection than regex alone.
- Coupon screenshots vary heavily across apps and brands.
- Indian coupon formats require flexible date, currency, and offer parsing.

What went wrong:

- Training and app runtime could drift.
- Model outputs could be incompatible with Android assets.
- Annotation schemas could diverge from app expectations.

Rule:

```text
Training schema, exported model format, and Android runtime loader must evolve together.
```

### Phase 3: OCR Engines And Fallbacks

The app evolved from basic OCR toward a multi-engine OCR system:

- ML Kit primary OCR.
- Tesseract fallback.
- Custom/model-based OCR experiments.
- OCR spans and bounding boxes.
- OCR fallback reasons.
- OcrCoordinator/OcrEngine abstraction.

Relevant historical commits include:

- `1470f1367 feat(ocr): add OCR fallback predicates and reason enum`
- `604bc0df9 feat(ocr): add OcrCoordinator with MLKit primary and Tesseract fallback`
- `5e02f00f5 refactor(di): bind OcrEngine to OcrCoordinator`

What went wrong:

- OCR could read text but not know which card/region it belonged to.
- OCR confidence could look good while store/code/description were associated
  with the wrong visual coupon.
- Tesseract bounding boxes were not always available.

Rule:

```text
OCR text without coordinates is incomplete evidence for multi-card screenshots.
```

### Phase 4: Schema And Contract Hardening

The app introduced stricter schema contracts for model output:

- `CouponSchemaKeys`
- `CouponJsonContract`
- canonical JSON validation
- schema v2 optional fields
- parser allowlists
- JNI fallback contract tests

Relevant historical commits include:

- `2215c8f02 feat(llm): add CouponSchemaKeys single source of truth for grammar fields`
- `0cca55dca feat(contract): introduce CouponJsonContract with schema validator`
- `f714920cd feat(schema): add schema v2 optional field constants`
- `a7b9832a7 feat(contract): add CouponJsonContractV2 validator`
- `4d04bb37e feat(db): bump to v14 with schema v2 column migration`

What went wrong:

- Model output could contain extra keys or invalid JSON.
- JNI fallback could return placeholder/mock fields.
- Parser recovery could accidentally accept bad structure.

Fix pattern:

- One canonical schema key source.
- Contract tests.
- Explicit parser allowlists.
- Mock/fallback detection.

Rule:

```text
Never let model JSON shape drift silently. Validate it before merge.
```

### Phase 5: Model Strategy Layer

The project added a strategy layer so different models could be registered and
selected by role:

- `ModelMode`
- `ModelRole`
- `CouponExtractionModel`
- `ModelStrategyConfig`
- `ModelSelector`
- replay/hermetic model for benchmarks

Relevant commits include:

- `b840d6615 feat(model): add ModelMode enum covering all planned backends`
- `0459ff283 feat(model): add CouponExtractionModel interface and result`
- `739c951b8 feat(model): add QwenTextCouponModel adapter and CouponJsonContract.enforce`
- `7636ca505 feat(model): add GemmaTextCouponModel adapter`

What went wrong:

- Different model backends had different assumptions.
- Qwen text cleanup, Gemma text, Gemma vision, MiniCPM VLM, and replay models
  needed common contracts.
- Some model outputs were useful for cleanup but unsafe as extraction authority.

Rule:

```text
Model role matters. A cleanup model is not automatically an extraction authority.
```

### Phase 6: MiniCPM, Qwen, Gemma, And VLM Retry

The app explored and integrated multiple local AI paths:

- MiniCPM VLM adapter.
- Qwen text coupon model.
- Qwen VLM adapter.
- Gemma text runtime.
- Gemma Vision runtime.
- VLM retry evaluator and merger.
- Conservative per-field merge rules.

Relevant commits include:

- `fa2c39853 feat(model): add MiniCpmVlmCouponModel adapter and register in DI`
- `1c2154ce8 feat(model): add GemmaVisionCouponModel adapter and runtime`
- `25ff962e7 feat(model): add QwenVlmCouponModel vision adapter`
- `cdfb339b5 feat(retry): add VlmRetryEvaluator and triggers`
- `1f8061489 feat(retry): add conservative VlmMerger with per-field rules`

What went wrong:

- Vision models could mix fields from adjacent cards.
- Text models could clean text but invent or normalize unsupported values.
- Gemma could return readable expiry formats that were not ISO.
- Token/time limits could make Gemma return empty or fail.
- Full-screen crops gave models too much unrelated context.

Fixes:

- Crop-first extraction.
- Model output contract enforcement.
- `ModelExpiryNormalizer` for model-readable dates.
- Longer Gemma timeout and sane token limit.
- OCR-targeted crops for Gemma Vision.
- Preserve protected fields unless model output is evidence-backed.

Rule:

```text
The model can propose. The app must validate.
```

### Phase 7: Multi-Coupon Detection

The app added multi-coupon detection and region-level extraction:

- `CouponRegion`
- `CouponRegionPipeline`
- `CouponDeduplicator`
- `JsonToCouponConverter`
- multi-coupon review screen
- batch pipeline feature flag

Relevant commits include:

- `be0988153 feat(multi): add CouponRegion and MultiCouponLimits`
- `b5fa12266 feat(multi): add CouponDeduplicator by store+code+expiry`
- `8d87a4967 feat(multi): add CouponRegionPipeline orchestrating OCR + model`
- `c47e226bf feat(ui): add multi-coupon review screen`
- `9cba25fec feat(multi): add BatchPipelineFeatureFlag for opt-in pipeline rewire`

What went wrong:

- Multi-card screenshots caused entity association failures.
- Codes are visually distinctive, so models often find the right code but pair it
  with the wrong merchant/offer.
- Batch scanner paths could bypass crop-first routing.

Fixes:

- Card detection before extraction.
- Crop bitmap as the canonical extraction unit.
- Multi-coupon selection/review before save.
- Batch scanner crop-first routing.

Universal rule:

```text
Segment first. Extract second. Validate spatial consistency third.
```

### Phase 8: Mac Extraction Harness And Benchmarks

The project added a Mac-side extraction harness:

- Python harness package.
- Manifest loader.
- Image preprocessing.
- llama.cpp runner.
- field diff module.
- report writers.
- baseline drift detection.
- OCR sidecar input.
- prompt parity.
- Android mirror sync.

Relevant commits include:

- `996286209 feat(extraction): bootstrap Python harness package`
- `b57ed675f feat(extraction): manifest loader with imageSha256 indexing`
- `a6ce468ad refactor(preprocessing): extract pure-JVM ImagePreprocessorCore`
- `383d2f2ef feat(extraction): llama.cpp runner module`
- `7b39c3330 feat(extraction): field diff module with normalization`
- `9b70791c7 feat(extraction): one-command Mac eval entrypoint`
- `bbf2d2ca8 feat(extraction): baseline drift detection and per-field accuracy`

What went wrong:

- Desktop evaluation could drift from Android runtime.
- Sampling params and OCR inputs could differ.
- Prompt changes could appear good locally but fail on-device.

Rules:

```text
Keep Android and Mac evaluation inputs aligned.
Track baseline drift.
Use stable golden samples.
```

### Phase 9: UI Redesign And Wallet Experience

The app went through a visual/product redesign:

- Brand tokens.
- Coupon cards.
- Glass surface.
- Brand buttons/top bars/text fields.
- Wallet screen.
- Detail screen.
- Settings screen.
- Multi-coupon review screen.
- Cleanup actions and status indicators.

Relevant commits include:

- `52bd10d5d docs(vault): redesign spec + implementation plan`
- `6aa73ac37 feat(vault): rewrite BrandStyleGuide.kt`
- `0d75f27af feat(vault): BrandButton three-tier primary/secondary/tertiary`
- `f75518211 Add wallet coupon card components`
- `1a82839e7 Redesign wallet and settings screens`
- `f48500e3c Redesign multi-coupon review screen`
- `bf20000a5 Show clean action and coupon card status`

What went wrong:

- UI routes and screens became large and hard to navigate.
- Cleanup/verification statuses could imply more confidence than the data
  deserved.
- Detail cards could show `VISION_VERIFIED` even when Gemma only ran but did not
  correct core fields.

Fixes:

- Scoped UI packages: `home`, `details`, `review`, `settings`, `modelsettings`.
- Stronger status semantics.
- Verification label rules.

Rule:

```text
Do not show a confident product label unless the data contract supports it.
```

### Phase 10: OCR-Only Capture And Explicit Cleanup

The app moved away from automatic LLM capture paths:

- Upload extraction kept OCR-only.
- Automatic LLM capture paths removed.
- Qwen deferred until explicit clean.
- Saved OCR text used for cleanup.
- Cleanup runs in background.

Relevant commits include:

- `02cc3a1c8 Keep upload extraction OCR-only`
- `c003d90a7 Remove automatic LLM capture paths`
- `813dfce5e Defer Qwen until explicit clean`
- `1565eaa9b Run coupon reader cleanup in background`
- `2cb4e5bf7 Use saved OCR text for coupon cleanup`

Why:

- User needs fast capture.
- Model cleanup can be slower.
- Models can fail; OCR result should remain available.

Rule:

```text
Capture should be resilient. Cleanup can be deferred and reversible.
```

### Phase 11: Recent Refactor And Reliability Hardening

The latest work added:

- `AGENTS.md` guardrails.
- Refactor docs and package/use-case docs.
- UI package moves.
- domain use-case shells.
- extraction/merge and extraction/quality packages.
- `CouponCodeExtractor`.
- `CouponTextBlocks`.
- `ModelCleanupMergePolicy`.
- `OfferTextQuality`.
- `CouponFieldNoise`.
- `ModelExpiryNormalizer`.
- stronger `GenericFieldHeuristics`.
- stronger `StoreCandidateValidator`.
- improved `SpatialFieldConsistencyValidator`.
- improved `VerifyCouponWorker`.

Latest commit:

```text
495b2ce71 Refactor coupon modules and harden extraction
```

## Major Failures We Saw And How We Fixed Them

### Failure: IKEA/Uber Multi-Card Mix

Observed:

- Code from IKEA card was correct.
- Merchant/offer came from Uber card.
- Expiry was missed.

Cause:

- Model saw full screenshot and mixed adjacent card fields.

Fix:

- Crop-first routing.
- Card detection before model extraction.
- Spatial consistency validation.

Permanent rule:

```text
Never trust full-screen extraction when more than one coupon/card may be visible.
```

### Failure: PUMA Expiry And Spatial Warnings

Observed:

- OCR/rules temporarily picked bad fragments like `5TH`.
- Gemma returned readable expiry like `05 May, 2025, 11:59 PM`.
- Spatial validation flagged full-screen anchors as too far apart before final
  merge.

Fix:

- `GenericFieldHeuristics` rejects date-only fragments.
- `ModelExpiryNormalizer` parses readable model dates.
- `SpatialFieldConsistencyValidator` chooses the tightest anchor group.

Permanent rule:

```text
Model-readable dates should be normalized, not rejected just because they are not ISO.
```

### Failure: Hours Coupon

Observed:

- Expiry badge fragments such as `HOURS`, `IN 04 HOURS`, and OCR noise could
  become store/description fields.

Fix:

- `CouponFieldNoise` and `GenericFieldHeuristics` reject expiry badge fragments.
- Relative expiry parsing expanded.

Permanent rule:

```text
Expiry badges are expiry evidence, not store or description evidence.
```

### Failure: Times Coupon

Observed:

- Gemma did not meaningfully improve the result.
- Preserved fields made the row look cleaner than the evidence justified.

Fix direction:

- Verification status must distinguish "model ran" from "model verified".
- Preserved weak fields should keep `needsAttention`.

Permanent rule:

```text
VISION_VERIFIED means core fields were actually vision-supported.
```

### Failure: NAMM/Skullcandy Coupon

Observed from logcat:

- OCR text included wallet/header noise:
  - `NamM`
  - `vouchers`
  - `active`
  - `lifetime`
- A later card had:
  - `Skullcandy`
  - `you won 80% off on Skullcandy`
  - `code: CRSD`
  - `EXPIRES IN 12 DAYS`
- Final row preserved bad store/description but still ended as
  `VISION_VERIFIED`.

Fix:

- Penalize wallet/header store candidates before first coupon signal.
- Reject weak savings descriptions like `you won off`.
- Reject alphanumeric code-shaped store candidates.
- Do not upgrade to `VISION_VERIFIED` when store/description are only preserved.

Permanent rule:

```text
Header/app chrome is context, not coupon data.
```

### Failure: Code-Shaped Store Candidate

Observed:

- `CREDJP70` could become a store candidate in tests.

Fix:

- `StoreCandidateValidator` rejects candidate tokens containing both letters and
  digits.

Permanent rule:

```text
Alphanumeric code-shaped tokens are not merchant names.
```

## Product Rules To Preserve

1. Fast capture first.
2. Cleanup is explicit or background, not blocking.
3. Never lose the original OCR evidence.
4. Do not silently overwrite user-edited fields.
5. Mark uncertain coupons as `needsAttention`.
6. Do not label a coupon verified unless core fields are verified.
7. Multi-card screenshots require card-level segmentation.
8. Manual review is better than a confident wrong save.

## Engineering Rules To Preserve

1. Prefer shared validators over brand-specific patches.
2. Add regression tests for each real device failure.
3. Keep model contracts explicit and tested.
4. Keep OCR evidence available for merge decisions.
5. Do not let model cleanup become a blind source of truth.
6. Prefer small package boundaries:
   - `extraction/rules`
   - `extraction/quality`
   - `extraction/merge`
   - `extraction/validation`
   - `worker`
   - `domain/usecase`
   - scoped `ui/*` packages
7. Always run Gradle checks before commit.

## Current Verification Commands

Use these for this branch:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Device install:

```bash
/Users/C/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug-v1.1.29.apk
```

If adb reports no devices, reconnect/unlock the phone and retry.

## Knowledge Map

Use these docs together:

- `README.md` for high-level product overview.
- `docs/IMPLEMENTATION_STATUS.md` for historical MiniCPM/LLM status.
- `docs/LLM_INTEGRATION.md` for local model integration details.
- `docs/extraction_pipeline.md` for extraction flow.
- `docs/extraction_reliability_program.md` for reliability work.
- `docs/refactor/README.md` for current refactor package/use-case docs.
- `docs/refactor/knowledge-diary-2026-06-19.md` for latest refactor/extraction
  reliability diary.
- `docs/PROJECT_KNOWLEDGE_DIARY.md` for the broad whole-project diary.
- `docs/archive/` for historical implementation, fixes, testing, and release
  records.

## Still Needed

The biggest remaining architecture task is a first-class final bundle gate:

```text
CouponRegionContext -> CouponFieldBundle -> CouponBundleValidator -> save
```

That should enforce:

- all accepted core fields are from one coupon region,
- store is not app/header chrome,
- description is concrete,
- code is supported or absent,
- expiry is parsed from the same region,
- verified labels match evidence.

Until that exists, keep adding focused regression tests for every device failure.
