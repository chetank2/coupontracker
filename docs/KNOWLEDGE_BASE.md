# CouponTracker Knowledge Base

This is the entry point for project knowledge.

The docs folder is organized into two reading levels:

1. **High-level knowledge base**: fast orientation, current architecture,
   product rules, and what to read first.
2. **Detailed knowledge base**: deeper implementation notes, extraction rules,
   model history, refactor docs, store publishing, and archived history.
3. **Memory files**: stable rules and dated lessons that should remain outside
   agent-private folders.

Use this file instead of scanning the whole `docs/` folder manually.

## Start Here

- [High-level knowledge base](knowledge-base/high-level/README.md)
- [Detailed knowledge base](knowledge-base/detailed/README.md)
- [Knowledge base contract](knowledge-base/knowledge-base-contract.md)
- [Knowledge base roadmap](knowledge-base/roadmap.md)
- [Refactor roadmap](knowledge-base/refactor-roadmap.md)
- [Static memory](knowledge-base/static-memory.md)
- [Dynamic memory](knowledge-base/dynamic-memory.md)
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

Read [Global AI Engineering Principles](knowledge-base/detailed/global-ai-engineering-principles.md)
when you need the country-agnostic, model-agnostic standard for how a coupon
extraction app should work beyond this specific repository.

Read [Knowledge Base Contract](knowledge-base/knowledge-base-contract.md) when
you need to decide what belongs in the knowledge base and where it should go.

Read [Knowledge Base Roadmap](knowledge-base/roadmap.md) for the documentation
backlog and missing source maps, contracts, playbooks, and testing matrices.

Read [Refactor Roadmap](knowledge-base/refactor-roadmap.md) before moving code
between packages or changing architectural ownership.

Read [Static Memory](knowledge-base/static-memory.md) for stable product,
design, development, testing, and code-hygiene rules.

Read [Dynamic Memory](knowledge-base/dynamic-memory.md) for recent commits,
device observations, regression fixes, and branch-specific lessons.

Read [Project Origin and Historical Context](knowledge-base/detailed/project-origin-history.md)
for early ChatGPT/project discussions, original product goals, screenshot
dataset context, and historical Qwen/Gemma/YOLO decisions. Treat that page as
history; current source code, tests, static memory, and dynamic memory override
it when they conflict.

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
