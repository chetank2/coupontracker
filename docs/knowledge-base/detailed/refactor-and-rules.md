# Refactor And Code Rules Knowledge

Use this page before moving files, splitting classes, adding packages, or
changing architecture boundaries.

## Current Principle

Refactor for clearer ownership, not just different folders.

Each moved file should make one boundary easier to understand:

- UI,
- domain/use case,
- data,
- extraction,
- model runtime,
- validation,
- worker.

## Current Docs

- [Refactor README](../../refactor/README.md)
- [Refactor knowledge diary](../../refactor/knowledge-diary-2026-06-19.md)
- [Code structure rules](../../refactor/rules/code-structure.md)
- [Rules and regulations](../../refactor/rules/rules-and-regulations.md)
- [Product rules](../../refactor/rules/product-rules.md)
- [Design rules](../../refactor/rules/design-rules.md)
- [Design system rules](../../refactor/rules/design-system-rules.md)
- [Universal extraction solution rules](../../refactor/rules/universal-extraction-solution-rules.md)
- [Anti-hardcoded rules](../../refactor/rules/anti-hardcoded-rules.md)
- [AI editing checklist](../../ai_guardrails/AI_EDITING_CHECKLIST.md)

## Package Docs

- [AI model](../../refactor/packages/ai-model.md)
- [Crop](../../refactor/packages/crop.md)
- [Data/entity/domain split](../../refactor/packages/data-entity-domain-split.md)
- [OCR](../../refactor/packages/ocr.md)
- [Pipeline](../../refactor/packages/pipeline.md)
- [Preferences](../../refactor/packages/preferences.md)
- [Worker](../../refactor/packages/worker.md)

## Use Case Docs

- [Extract coupon](../../refactor/usecases/extract-coupon.md)
- [Clean coupon](../../refactor/usecases/clean-coupon.md)
- [Save coupon](../../refactor/usecases/save-coupon.md)
- [Delete coupon](../../refactor/usecases/delete-coupon.md)
- [Share coupon](../../refactor/usecases/share-coupon.md)

## Refactor Rules

### Keep Behavior Stable

Before moving code, identify:

- current inputs,
- current outputs,
- side effects,
- logs relied on for debugging,
- tests that cover it.

After moving code, run the same checks.

### Avoid Catch-All Files

Do not create files that mix:

- UI state,
- OCR extraction,
- model runtime,
- database writes,
- navigation,
- logging policy.

Split by reason to change.

### No Hardcoded Brand Fixes

Observed brand failures should become generic rules:

- token quality,
- label proximity,
- region membership,
- evidence strength,
- field type validation.

Do not add brand-specific exceptions unless there is a documented product rule.

### Preserve Logs That Debug Device Failures

Extraction and model paths need logs for:

- strategy used,
- crop/region decision,
- OCR text summary,
- model runtime status,
- validation failures,
- merge decisions.

Logs should explain why a field was accepted, rejected, or marked uncertain.

## Verification

For docs-only refactors:

```bash
git diff --check
```

For Android refactors:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```
