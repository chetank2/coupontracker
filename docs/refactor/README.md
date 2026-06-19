# Refactor Documentation Index

This folder is the agent-facing plan for the layered Android refactor. Keep
implementation slices small, behavior-preserving, and verified with the required
Gradle checks from `AGENTS.md`.

## Rules

- [Code structure](rules/code-structure.md)
- [Rules and regulations](rules/rules-and-regulations.md)
- [Product rules](rules/product-rules.md)
- [Design rules](rules/design-rules.md)
- [Design system rules](rules/design-system-rules.md)
- [Universal extraction solution rules](rules/universal-extraction-solution-rules.md)
- [Anti-hardcoded rules](rules/anti-hardcoded-rules.md)

## Use Cases

- [ExtractCoupon](usecases/extract-coupon.md)
- [CleanCoupon](usecases/clean-coupon.md)
- [SaveCoupon](usecases/save-coupon.md)
- [DeleteCoupon](usecases/delete-coupon.md)
- [ShareCoupon](usecases/share-coupon.md)

## Packages

- [Details UI](packages/details-ui.md)
- [Review UI](packages/review-ui.md)
- [Settings UI](packages/settings-ui.md)
- [Model Settings UI](packages/model-settings-ui.md)
- [Home UI](packages/home-ui.md)
- [Scanner UI](packages/scanner-ui.md)
- [AI model](packages/ai-model.md)
- [OCR](packages/ocr.md)
- [Crop](packages/crop.md)
- [Pipeline](packages/pipeline.md)
- [Data entity/domain split](packages/data-entity-domain-split.md)
- [Preferences](packages/preferences.md)
- [Worker](packages/worker.md)

## Knowledge Diary

- [2026-06-19 Refactor and extraction reliability diary](knowledge-diary-2026-06-19.md)

## Current Implementation Status

Implemented slices:

- Root and Android package guardrails in `AGENTS.md` and `app/AGENTS.md`.
- Use-case shell classes under `domain/usecase`.
- Merge/dedup compatibility move under `extraction/merge`.
- UI package moves for details, review, settings, model settings, and home.
- Preferences implementation move to `data/preferences` with `util` typealias
  compatibility for existing callers.

Pending slices:

- Scanner/capture package split.
- Data entity/domain split.
- OCR/crop/pipeline package cleanup.
- AI model package cleanup.
- Worker package cleanup and scheduling boundary review.
