# Knowledge Base Contract

The knowledge base exists to answer one question:

```text
What should the next developer or agent know so they do not repeat old mistakes?
```

It should not be a dumping ground for every observation. It should preserve
rules, decisions, failure lessons, source maps, and verification standards that
materially reduce future mistakes.

## 1. Static Memory

Static memory contains stable rules that should not change often.

It should include:

- product principles,
- current architecture boundaries,
- target architecture boundaries,
- package ownership,
- extraction authority model,
- UI/design rules,
- Room/schema rules,
- WorkManager rules,
- code hygiene rules,
- testing standards,
- required commands.

For CouponTracker, the most important static rule is:

```text
OCR reads exact text.
Gemma decides visual ownership and field labels.
Validator decides trust.
Do not let Gemma invent coupon codes.
```

Static memory belongs in [Static Memory](static-memory.md) and stable topic docs.

## 2. Dynamic Memory

Dynamic memory contains recent lessons that may change as the branch evolves.

It should include:

- dated fixes,
- device/logcat observations,
- commit summaries,
- known current bugs,
- recently installed APK version,
- failed approaches,
- regression tests added,
- open follow-ups.

Example:

```text
2026-06-27: MakeMyTrip coupon showed FLYMART but saved NO_CODE_NEEDED because
background cards had no-code text. Fixed by making no-code non-global and
preferring active-card scoped code extraction.
```

Dynamic memory belongs in [Dynamic Memory](dynamic-memory.md).

## 3. Source Map

The knowledge base must tell readers where important behavior lives.

It should map:

- scanner entrypoints,
- OCR pipeline,
- VLM/Gemma layout pipeline,
- validation/scoring,
- Room entities/DAO,
- workers,
- UI screens,
- settings/model toggles.

This prevents edits in the wrong file or only one lane of a multi-lane flow.

For CouponTracker, always mention that there are multiple active paths:

- scanner/import routing,
- OCR-first fallback,
- multi-coupon layout detection,
- post-save verification worker,
- repository save/merge.

## 4. Decision Log

The knowledge base should explain why major choices were made, not only what
the current code does.

It should record:

- why extraction is crop-first,
- why VLM is not final truth,
- why coupon codes need OCR proof,
- why no-code is a state,
- why cleanup is post-save/background, not capture,
- why reviewable uncertainty is better than direct bad saves.

Good decision notes should be short and causal:

```text
We crop before final field extraction because full-screen wallet OCR mixed
foreground modals with background coupon cards.
```

## 5. Failure Playbook

Every repeated or real-device failure should become a symptom/cause/fix/test
entry.

Format:

```text
Symptom: wrong code from background card.
Cause: full-screen OCR mixed multiple cards.
Fix: active-card crop before final extraction.
Test: add wallet multi-card regression.
```

The playbook should cover:

- wrong store,
- wrong description,
- missing/incorrect code,
- no-code vs visible code,
- previous-card expiry,
- legal boilerplate as description,
- rupee OCR artifacts,
- malformed VLM JSON,
- stale phone build.

## 6. Testing Playbook

The knowledge base should say what to run and when.

It should include:

- required local checks,
- focused test names by area,
- device install/check commands,
- screenshot corpus cases,
- when to inspect logcat,
- when to inspect the device database.

Minimum Android checks:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

When user-visible phone behavior is involved, also verify the installed build:

```bash
/Users/C/Library/Android/sdk/platform-tools/adb devices
/Users/C/Library/Android/sdk/platform-tools/adb install -r <debug-apk>
/Users/C/Library/Android/sdk/platform-tools/adb shell dumpsys package com.example.coupontracker
```

## 7. Code Hygiene Rules

The knowledge base should stop patch-loop behavior.

It should preserve rules such as:

- no brand-specific hacks unless explicitly intended and documented,
- add regression tests for every real failure,
- prefer scoping/routing over keyword patches,
- never silently change Room schema,
- never mark `VISION_VERIFIED` just because Gemma ran,
- do not keep final project knowledge in private `.codex`,
- keep parser/debug details in debug evidence, not raw UI errors,
- keep model cleanup separate from capture.

## CouponTracker Core

For this project, the knowledge base should stay opinionated around:

```text
crop-first
state-aware extraction
OCR exact text
Gemma layout/field labels
validator trust
reviewable uncertainty
small tested fixes
```

Everything else in the docs should support those rules.
