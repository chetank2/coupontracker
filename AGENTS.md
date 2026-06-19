# CouponTracker Agent Guide

This repository is being refactored toward a layered Android architecture. Keep
changes small, behavior-preserving, and verified after each package move.

## Architecture

Target shape:

```text
app/src/main/kotlin/com/example/coupontracker/
  ui/            Screens, fragments, UI state, navigation, viewmodels
  domain/        Use cases and future pure domain models/repository contracts
  data/          Room, repositories, mappers, preferences
  extraction/    Crop, OCR, rules, merge, pipeline, validation
  ai/            Future model management, cleanup, and verification adapters
  worker/        WorkManager jobs and enqueue helpers
```

## Non-Negotiables

- Preserve crop-first extraction. Never send a multi-card screenshot directly
  into field extraction when a coupon-card crop is available.
- Do not move Room entities or DAOs without verifying schema/table names and
  migrations.
- Do not move legacy fragments without updating `nav_graph.xml` and Safe Args
  imports.
- Keep ViewModels thin. New business behavior belongs in `domain/usecase` or
  extraction pipeline classes.
- Keep AI cleanup separate from capture. Capture is OCR-first; model cleanup is
  explicit/background verification.

## Required Checks

Use Java 17 for Gradle:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Also run:

```bash
git diff --check
```

