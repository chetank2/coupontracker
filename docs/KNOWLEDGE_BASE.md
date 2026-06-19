# CouponTracker Knowledge Base

This is the entry point for project knowledge.

The docs folder is organized into two reading levels:

1. **High-level knowledge base**: fast orientation, current architecture,
   product rules, and what to read first.
2. **Detailed knowledge base**: deeper implementation notes, extraction rules,
   model history, refactor docs, store publishing, and archived history.

Use this file instead of scanning the whole `docs/` folder manually.

## Start Here

- [High-level knowledge base](knowledge-base/high-level/README.md)
- [Detailed knowledge base](knowledge-base/detailed/README.md)
- [Full project diary](PROJECT_KNOWLEDGE_DIARY.md)
- [Refactor documentation](refactor/README.md)

## Which Level Should I Read?

Read the high-level knowledge base when you need to understand:

- what the app does,
- the current architecture,
- the current product rules,
- the safest next step,
- why extraction must be crop-first and evidence-backed.

Read the detailed knowledge base when you need to change:

- OCR extraction,
- Qwen/Gemma/MiniCPM behavior,
- Room schema or coupon data model,
- scan/save/verify flows,
- UI packages,
- refactor boundaries,
- store publishing docs,
- training or annotation workflows.

## Current Priority

The current active product focus is the Android coupon wallet and extraction
pipeline on `feature/qwen-multi-coupon-extraction`.

The highest-risk area is coupon extraction correctness:

```text
coupon screenshot -> card/region detection -> OCR/model extraction -> field bundle -> validation -> save
```

Do not treat a model response as trusted until it passes field-specific and
region-specific validation.

## Current Vs Historical Docs

Use current docs first:

- `docs/KNOWLEDGE_BASE.md`
- `docs/PROJECT_KNOWLEDGE_DIARY.md`
- `docs/refactor/README.md`
- `docs/ai_guardrails/*`
- `docs/extraction/*`
- `docs/store/*`

Use archived docs only for history:

- `docs/archive/*`

Archived docs can contain outdated architecture, old branch names, old model
plans, or implementation plans that were superseded by later commits.

## Required Rules For Agents

Before code changes, check:

- [AI editing checklist](ai_guardrails/AI_EDITING_CHECKLIST.md)
- [Coupon extraction rules](ai_guardrails/COUPON_EXTRACTION_RULES.md)
- [Refactor code structure rules](refactor/rules/code-structure.md)
- [Universal extraction solution rules](refactor/rules/universal-extraction-solution-rules.md)
- [Anti-hardcoded rules](refactor/rules/anti-hardcoded-rules.md)

## Verification Commands

Use these checks for Android changes:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Use connected tests only when Android device/emulator setup is available:

```bash
./gradlew connectedAndroidTest
```
