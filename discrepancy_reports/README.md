# Codex Debug Discrepancy Dataset

This directory captures curated discrepancy cases drawn from recent screenshot audits. Each entry is prepared for direct ingestion into Codex retraining workflows and mirrors the structured format requested during the review.

## File Overview

- `codex_debug_dataset.json` - Primary dataset containing ground truth annotations, current extraction outputs, and labelled discrepancies for every reviewed screenshot.

## Data Guidelines

Each record in `codex_debug_dataset.json` includes:

- `image_id`: Identifier of the audited screenshot.
- `ground_truth`: Expected values for `store_name`, `coupon_code`, `description`, and `expiry_date` (ISO 8601 when available, or contextual markers such as `relative:6_days`).
- `extracted`: Values produced by the current production pipeline.
- `discrepancies`: Normalized taxonomy keys (snake_case) corresponding to the universal discrepancy categories.

Use this dataset to reproduce failures, craft prompt regression tests, and validate post-processing fixes across the coupon extraction stack.

## Extraction Heuristic Scope

The corrective changes in `app/src/main/kotlin/com/example/coupontracker/extraction/StructuredFieldExtractor.kt` keep the
store-name logic fully heuristic-driven: there are no whitelists of merchants, only filters for common OCR noise and
payment overlays so the same routines apply to any brand the OCR surfaces. Expiry normalization likewise converts any
absolute date that matches flexible regex patterns into ISO-8601 output and interprets relative phrases like "expires in
6 days" against the capture timestamp, so no brand-specific wiring is required. Description assembly happens in
`app/src/main/kotlin/com/example/coupontracker/util/TextExtractor.kt`, where multi-line promotional sentences are merged
purely based on text structure instead of enumerating known merchants or offers. Collectively, the pipeline remains
brand-agnostic and runs end to end without manual curation per store.
