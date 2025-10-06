# Schema-Driven Architecture Implementation Tracker

**Branch:** `feature/schema-driven-architecture`  
**Started:** October 6, 2025  
**Status:** Week 2 in progress

## Progress Overview

- [x] **Phase 1: Foundation** (Week 1) - COMPLETE
- [x] **Phase 2: Code Generation** (Week 2) - COMPLETE
- [ ] **Phase 3: Integration** (Week 3) - PENDING
- [ ] **Phase 4: Testing** (Week 3) - PENDING

---

## Week 1: Foundation ✅ COMPLETE

### Completed Tasks

- [x] Audit existing models (CouponField, CashbackInfo, Coupon) to avoid duplication
- [x] Create `schema/SchemaDefinition.kt` with FieldType, FieldMetadata, SchemaField, Schema
- [x] Create `schema/CouponSchema.kt` with SCHEMA object (7 fields, metadata, helpers)
- [x] Create `schema/CouponSchemaTest.kt` with comprehensive tests

**Commit:** `66657c08f` - Week 1: Schema foundation complete

### Findings

- **CouponField**: Bounding box + detection metadata (different purpose from schema)
- **CashbackType**: Reusable enum for typed cashback (integrated into schema)
- **Coupon entity**: 7 core fields matched to schema definition

---

## Week 2: Code Generation ✅ COMPLETE

### Completed Tasks

- [x] Create `schema/PromptGenerator.kt` with deterministic output formatting
  - System prompt generation
  - User prompt generation
  - Assistant primer
  - Schema JSON representation
  - Token estimation
  - OCR sanitization
  
- [x] Create `schema/PromptGeneratorTest.kt` with comprehensive tests
  - 20+ test cases covering prompt generation
  - Validation against manual prompt structure
  - Deterministic output verification
  
- [x] Create `schema/GBNFGenerator.kt` with stable grammar output
  - Root rule generation
  - Field rules for all types
  - Enum, object, array support
  - Primitive rules (string, number, boolean, null, ws)
  - Grammar validation
  
- [x] Create `schema/GBNFGeneratorTest.kt` with syntax validation
  - 15+ test cases covering grammar generation
  - GBNF syntax validation
  - Deterministic output verification
  
- [x] Create `schema/SchemaValidator.kt` covering all field types
  - JSON validation against schema
  - Type checking for all FieldType variants
  - Nested object validation
  - Enum validation
  - Detailed error reporting
  
- [x] Create `schema/SchemaValidatorTest.kt` with edge cases
  - 19+ test cases covering validation scenarios
  - Valid/invalid JSON handling
  - Type mismatch detection
  - Nested structure validation
  
- [x] Create `scripts/regenerate_grammar.kts` script
  - Kotlin script for grammar generation
  - Validation of generated grammar
  - Output to assets directory

### Notes

All Week 2 generators produce deterministic, stable output suitable for version control and CI/CD.

---

## Week 3: Integration & Testing ✅ MOSTLY COMPLETE

### Day 1: Update LocalLlmOcrService ✅ COMPLETE

- [x] Backup current `buildQwenPrompt()` as `buildQwenPromptManual()`
- [x] Add feature flag for schema-driven prompts (`USE_SCHEMA_PROMPTS`)
- [x] Integrate `PromptGenerator.generateCompletePrompt()`
- [x] Add feature flag for schema-driven validation (`USE_SCHEMA_VALIDATION`)
- [x] Test: Build APK
- [x] Schema imports added
- [x] Feature-flagged integration complete

**Commit:** `9f8ec8be5` - Feature-flagged schema integration in LocalLlmOcrService

### Day 2: Update CouponJsonValidator ✅ COMPLETE

- [x] Backup current validator logic (preserved as else branch)
- [x] Add feature flag for schema-driven validation (`USE_SCHEMA_VALIDATION`)
- [x] Integrate `SchemaValidator`
- [x] Update validation result handling
- [x] Test: APK builds successfully
- [x] Fix method name collision (`getIssues` → `getIssuesList`)

**Commit:** `a073e938d` - Feature-flagged schema integration in CouponJsonValidator  
**Commit:** `c92de50f6` - Fix method name collision

### Day 3: Route All Entry Points

- [ ] `LocalLlmOcrService`: Already integrated (primary path)
- [ ] `ImageProcessor`: Route through schema validator
- [ ] `CouponFormViewModel`: Route through schema validator
- [ ] `SmartCaptureViewModel`: Route through schema validator
- [ ] `BatchScannerViewModel`: Route through schema validator
- [ ] `AddFragment`: Route through schema validator

### Day 4: Integration Testing

- [ ] Capture baseline accuracy (10 coupons, current prompt+validator)
- [ ] Run same coupons through schema-driven path
- [ ] Compare accuracy metrics
- [ ] Fix regressions
- [ ] Test complete extraction pipeline
- [ ] APK build verification
- [ ] Device smoke test
- [ ] Timing check (ensure no perf regression)

---

## Exit Gate Checklist

Before merging to main:

- [ ] All unit tests pass (coverage trend maintained)
- [ ] Integration tests pass
- [ ] Device testing shows same or better accuracy vs baseline
- [ ] No performance regression (inference time)
- [ ] Feature flags documented for rollback
- [ ] Code review completed
- [ ] All 5 entry points using schema-driven validation
- [ ] Baseline comparison documented

---

## Implementation Notes

### Architecture

Schema-driven architecture separates concerns:

1. **Schema layer** (`schema/`): Defines structure, metadata, rules
2. **Generator layer**: Produces prompts, grammars, validators from schema
3. **Integration layer**: Uses generated artifacts in extraction pipeline

### Benefits

- **Single source of truth**: Schema drives everything
- **Consistency**: Prompts, grammars, validators stay in sync
- **Maintainability**: Change schema once, regenerate all artifacts
- **Testability**: Each layer independently testable
- **Extensibility**: Add fields without touching prompt/grammar code

### Feature Flags

Integration uses feature flags for safe rollout:

```kotlin
// Example feature flag usage
val useSchemaPrompt = BuildConfig.FEATURE_SCHEMA_PROMPTS // default: false
val prompt = if (useSchemaPrompt) {
    PromptGenerator.generateCompletePrompt(CouponSchema.SCHEMA, ocrText)
} else {
    buildQwenPromptManual(ocrText) // legacy
}
```

---

## Blockers & Issues

None currently. Week 3 integration tasks ready to begin.

---

## Future Enhancements (Post-Merge)

- [ ] Dynamic schema updates from server
- [ ] Multi-model support (Receipt, Invoice schemas)
- [ ] Field transformers for post-processing
- [ ] Conditional fields support
- [ ] Schema versioning and migration
- [ ] Gradle task for grammar regeneration
- [ ] CI/CD integration for automatic grammar updates

