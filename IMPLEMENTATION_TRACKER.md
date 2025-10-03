# Schema-Driven Architecture Implementation Tracker

**Branch:** `feature/schema-driven-architecture`  
**Started:** October 3, 2025  
**Target Completion:** October 24, 2025 (3 weeks)

## Progress Overview

- [ ] **Phase 1: Foundation** (Week 1)
- [ ] **Phase 2: Code Generation** (Week 2)
- [ ] **Phase 3: Integration** (Week 3)
- [ ] **Phase 4: Testing** (Week 3)

---

## Week 1: Foundation (Oct 7-11, 2025)

### Day 1-2: Schema Definition
- [ ] Create `app/src/main/kotlin/com/example/coupontracker/schema/SchemaDefinition.kt`
  - [ ] Implement `FieldType` sealed class
  - [ ] Implement `FieldMetadata` data class
  - [ ] Implement `SchemaField` data class
  - [ ] Implement `Schema` data class

- [ ] Create `app/src/main/kotlin/com/example/coupontracker/schema/CouponSchema.kt`
  - [ ] Define `SCHEMA` object with all 7 fields
  - [ ] Add rich metadata (description, examples, hints)
  - [ ] Add helper functions (getField, isFieldRequired)

### Day 3: Schema Tests
- [ ] Create `app/src/test/kotlin/com/example/coupontracker/schema/CouponSchemaTest.kt`
  - [ ] Test field count
  - [ ] Test metadata presence
  - [ ] Test cashback structure
  - [ ] Test field access helpers

### Day 4: Review & Refine
- [ ] Code review of schema definition
- [ ] Update examples based on real coupon data
- [ ] Ensure all metadata is comprehensive

---

## Week 2: Code Generation (Oct 14-18, 2025)

### Day 1: Prompt Generator
- [ ] Create `app/src/main/kotlin/com/example/coupontracker/schema/PromptGenerator.kt`
  - [ ] Implement `generateSystemPrompt()`
  - [ ] Implement `generateUserPrompt()`
  - [ ] Implement `generateAssistantPrimer()`
  - [ ] Helper: `generateSchemaJson()`
  - [ ] Helper: `generateRules()`
  - [ ] Helper: `generateExamples()`

- [ ] Create `app/src/test/kotlin/com/example/coupontracker/schema/PromptGeneratorTest.kt`
  - [ ] Test prompt contains all fields
  - [ ] Test prompt contains rules
  - [ ] Test ChatML format
  - [ ] Compare with current manual prompt

### Day 2: GBNF Generator
- [ ] Create `app/src/main/kotlin/com/example/coupontracker/schema/GBNFGenerator.kt`
  - [ ] Implement `generate()` main function
  - [ ] Implement `generateRootRule()`
  - [ ] Implement `generateFieldRules()`
  - [ ] Implement `generateEnumRule()`
  - [ ] Implement `generatePrimitiveRules()`

- [ ] Create `app/src/test/kotlin/com/example/coupontracker/schema/GBNFGeneratorTest.kt`
  - [ ] Test grammar has root rule
  - [ ] Test grammar includes all fields
  - [ ] Test grammar has primitives
  - [ ] Verify GBNF syntax validity

### Day 3: Validator Generator
- [ ] Create `app/src/main/kotlin/com/example/coupontracker/schema/SchemaValidator.kt`
  - [ ] Implement `validate()` main function
  - [ ] Implement `validateField()`
  - [ ] Implement `validateType()`
  - [ ] Handle all FieldType variants

- [ ] Create `app/src/test/kotlin/com/example/coupontracker/schema/SchemaValidatorTest.kt`
  - [ ] Test valid JSON passes
  - [ ] Test invalid types fail
  - [ ] Test unknown keys fail
  - [ ] Test nested object validation (cashback)

### Day 4: Grammar Regeneration Script
- [ ] Create `scripts/regenerate_grammar.kts`
  - [ ] Call GBNFGenerator.generate()
  - [ ] Write to `app/src/main/assets/coupon_schema.gbnf`
  - [ ] Add to git hooks (optional)

- [ ] Test: Run script and verify grammar file
- [ ] Compare generated grammar with current manual grammar

---

## Week 3: Integration & Testing (Oct 21-25, 2025)

### Day 1: Update LocalLlmOcrService
- [ ] Backup current `buildQwenPrompt()` as `buildQwenPromptManual()`
- [ ] Replace with `PromptGenerator.generateSystemPrompt()`
- [ ] Test: Build APK
- [ ] Test: Device extraction with new prompt

### Day 2: Update CouponJsonValidator
- [ ] Backup current validator logic
- [ ] Replace with `SchemaValidator`
- [ ] Update validation result handling
- [ ] Test: Unit tests pass
- [ ] Test: Integration with LLM output

### Day 3: Integration Testing
- [ ] Test complete extraction pipeline
- [ ] Test with 10+ real coupon images
- [ ] Compare accuracy with main branch
- [ ] Fix any regressions

### Day 4: Deploy & Monitor
- [ ] Merge to main (if tests pass)
- [ ] OR keep in feature branch for more testing
- [ ] Monitor device logs
- [ ] Collect metrics on extraction accuracy

---

## Success Checklist

Before merging to main, verify:

- [ ] All unit tests pass (>95% coverage)
- [ ] Integration tests pass
- [ ] Device testing shows same or better accuracy
- [ ] No performance regression (inference time)
- [ ] Code review completed
- [ ] Documentation updated
- [ ] SCHEMA_REFACTOR_PLAN.md tasks completed

---

## Notes & Decisions

### Oct 3, 2025
- Created branch `feature/schema-driven-architecture`
- Starting point: Short-term prompt fix committed to main
- Plan: Implement schema-driven architecture in parallel

### [Add notes as implementation progresses]

---

## Blockers & Issues

None yet. Will track here as they arise.

---

## Future Enhancements (Post-Merge)

- [ ] Dynamic schema updates from server
- [ ] Multi-model support (Receipt, Invoice)
- [ ] Field transformers for post-processing
- [ ] Conditional fields support
