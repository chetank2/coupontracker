# Detailed Knowledge Base

This level maps the existing documentation into implementation-oriented topics.

Use it when you need to modify code or investigate a behavior.

## Topic Index

- [Extraction and OCR](extraction-and-ocr.md)
- [Models and Local AI](models-and-ai.md)
- [Android App Architecture](android-app.md)
- [Refactor and Code Rules](refactor-and-rules.md)
- [Training and Annotation](training-and-annotation.md)
- [Store, Privacy, and Publishing](store-and-publishing.md)
- [Git, Branches, and Maintenance](git-and-branches.md)
- [Global AI Engineering Principles](global-ai-engineering-principles.md)
- [Historical Archive](historical-archive.md)

## How To Use This Level

1. Start with the topic index that matches the code area.
2. Read current docs before archived docs.
3. Treat archived docs as historical evidence, not current truth.
4. If you learn something from device logs or a production failure, add it to:
   - [Project Knowledge Diary](../../PROJECT_KNOWLEDGE_DIARY.md), or
   - a focused topic doc in this detailed layer.

## Current Source Of Truth

When docs conflict, prefer this order:

1. Current source code and tests.
2. `docs/PROJECT_KNOWLEDGE_DIARY.md`.
3. `docs/knowledge-base/high-level/README.md`.
4. Current topic docs under `docs/knowledge-base/detailed/`.
5. Current docs under `docs/refactor`, `docs/extraction`, `docs/ai_guardrails`.
6. Archived docs under `docs/archive`.

## Required Verification

For Android code changes:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```
