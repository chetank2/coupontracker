# OfferText Deprecation Plan

## Objective
Fully eliminate the deprecated `offerText` field from the codebase, data layer, machine learning pipeline, and documentation so that coupon descriptions rely exclusively on the canonical `description` field.

## Current Usage Inventory
- **Data model**: `Coupon` data class still exposes an `offerText` property marked as deprecated for migrations.【F:app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt†L22-L66】
- **Database schema**: Room migrations create and persist the `offerText` column and related SQL transitions.【F:app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt†L137-L153】【F:app/schemas/com.example.coupontracker.data.local.CouponDatabase/7.json†L9-L79】
- **ViewModels**: `ScannerViewModel` and `BatchScannerViewModel` null out the field but still include it when mapping coupon results.【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L470-L1778】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt†L468-L967】
- **Extraction services**: Progressive and Universal extraction stages explicitly set `offerText = null`, and `LocalLlmOcrService` strips the key from LLM responses.【F:app/src/main/kotlin/com/example/coupontracker/extraction/ProgressiveExtractionService.kt†L400-L420】【F:app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt†L305-L324】【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L1080-L1086】
- **Documentation & tests**: Several markdown reports and tests mention `offerText` scenarios, perpetuating legacy expectations.【F:PROGRESSIVE_EXTRACTION_COMPLETE.md†L220-L235】【F:FIX_REQUIRED_FIELDS_KAPIVA_2025-10-11.md†L29-L352】【F:app/src/test/java/com/example/coupontracker/extraction/StructuredFieldExtractorTest.kt†L1-L120】

## Decommission Strategy

### 1. Data Model & Serialization
1. Remove the `offerText` property from `Coupon` and any DTO/mapper classes.
2. Update companion convenience methods (e.g., cashback display helpers) to rely solely on `description` and other canonical fields.
3. Adjust serialization/deserialization logic so JSON payloads ignore unknown `offerText` keys rather than expecting them.

### 2. Database Layer
1. Introduce a new Room migration that drops the `offerText` column from the `coupons` table.
2. Update DAO queries and entity definitions to remove the column and regenerate Room schema snapshots.
3. Provide a one-time data backfill step (if necessary) to copy non-null `offerText` values into `description` before dropping the column.
4. Validate migrations by running the existing instrumentation/unit tests that exercise Room migrations.

### 3. ViewModels & Business Logic
1. Remove references to `offerText` when constructing or normalizing coupon objects in `ScannerViewModel`, `BatchScannerViewModel`, and related use cases.
2. Confirm downstream UI uses `description` for display, ensuring no regressions in coupon preview or detail screens.
3. Add regression tests verifying that offers displaying stacked amounts (e.g., ₹100 + ₹70 cashback) use the correct canonical fields and no longer infer values from the deprecated key.

### 4. Extraction & ML Pipeline
1. Update extraction services (`ProgressiveExtractionService`, `UniversalExtractionService`, `LocalLlmOcrService`) to stop mutating or sanitizing `offerText` keys and instead reinforce `description` normalization.
2. Adjust schema expectations in the ML/LLM prompts so generated JSON excludes the `offerText` field entirely.
3. Enforce the canonical extraction contract: only `storeName`, `couponCode`, and `expiryDate` are emitted as structured fields, with all remaining coupon text passed through verbatim as the `description`. When only an "expiring in X days" string is present, compute `expiryDate` from the screenshot metadata timestamp plus `X` days.
4. Re-run affected pipeline tests and validation suites to confirm accurate extraction without concatenating cashback components.

### 5. API & Integration Contracts
1. Communicate the removal to any external consumers (mobile, backend services) and provide a migration window.
2. Update API contracts, OpenAPI specs, or protobuf definitions to remove `offerText` references.
3. Ensure incoming payloads containing `offerText` are either rejected with clear errors or automatically mapped to `description` during the transition period.

### 6. Documentation & Training Data
1. Purge `offerText` mentions from markdown documentation, developer guides, and troubleshooting logs.
2. Update training data, prompt templates, and synthetic datasets to rely on `description` and `cashback` fields instead.
3. Add explicit guidance in contributor docs clarifying the canonical fields for offer messaging.

### 7. Verification & Monitoring
1. Implement automated tests that fail if `offerText` appears in models, schemas, or serialized JSON.
2. Instrument runtime logging/analytics to detect legacy payloads still sending `offerText` so teams can remediate sources.
3. After deployment, monitor user-reported discrepancies (like ₹100 + ₹70 stacking) to confirm the issue no longer reproduces, and add automated alerting if the extraction layer outputs structured fields beyond the approved set or mutates the raw description text.

### 8. Deployment Checklist
1. Roll out the database migration with proper backups and fallbacks.
2. Deploy application updates (mobile/web/backend) that no longer reference the field.
3. Coordinate feature flag or version gating if older clients must remain compatible during rollout.
4. Post-deployment, validate with regression tests and sample screenshots that coupons render a single, correct description string.

## Expected Outcome
Once all steps are completed, the `offerText` field will no longer exist in storage, code, or documentation. Offer descriptions will rely on the normalized `description` field, preventing accidental concatenation of cashback components and ensuring consistent display across the application.
