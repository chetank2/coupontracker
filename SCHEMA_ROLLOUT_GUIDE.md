# Schema-Driven Architecture Rollout Guide

**Status:** Ready for testing  
**Branch:** `feature/schema-driven-architecture`  
**Last Updated:** October 6, 2025

## Quick Start

The schema-driven architecture is **fully implemented** with feature flags for safe rollout.

### Enable Schema Features

To enable schema-driven prompts and validation, update these flags:

**File:** `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`

```kotlin
// Change these from false to true
private const val USE_SCHEMA_PROMPTS = true      // Enable schema-driven prompts
private const val USE_SCHEMA_VALIDATION = true   // Enable schema-driven validation
```

**File:** `app/src/main/kotlin/com/example/coupontracker/util/CouponJsonValidator.kt`

```kotlin
// Change this from false to true
private const val USE_SCHEMA_VALIDATION = true   // Enable schema-driven validation
```

### Build & Test

```bash
# Build APK
./gradlew :app:assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk

# Monitor logs
adb logcat -s LocalLlmOcrService:* SchemaValidator:* CouponJsonValidator:*
```

---

## Architecture Overview

### Schema Layer (`schema/`)

**Files:**
- `SchemaDefinition.kt` - Core types (FieldType, FieldMetadata, SchemaField, Schema)
- `CouponSchema.kt` - 7-field coupon extraction schema with metadata
- `PromptGenerator.kt` - Generates prompts from schema
- `GBNFGenerator.kt` - Generates GBNF grammars from schema
- `SchemaValidator.kt` - Validates JSON against schema

**Tests:**
- `CouponSchemaTest.kt` - 18 tests, schema structure validation
- `PromptGeneratorTest.kt` - 18 tests, prompt generation validation
- `GBNFGeneratorTest.kt` - 15 tests, grammar generation validation
- `SchemaValidatorTest.kt` - 19 tests, JSON validation

---

## Integration Points

### 1. LocalLlmOcrService

**Prompt Generation:**

```kotlin
// Legacy (USE_SCHEMA_PROMPTS = false)
buildQwenPromptManual(ocrText)

// Schema-driven (USE_SCHEMA_PROMPTS = true)
PromptGenerator.generateCompletePrompt(CouponSchema.SCHEMA, ocrText)
```

**Validation:**

```kotlin
// Legacy (USE_SCHEMA_VALIDATION = false)
CouponJsonValidator.parseStrict(json)

// Schema-driven (USE_SCHEMA_VALIDATION = true)
SchemaValidator.validate(json, CouponSchema.SCHEMA)
```

### 2. CouponJsonValidator

Wrapper with feature flag:

```kotlin
fun parseStrict(jsonString: String): JSONObject? {
    if (USE_SCHEMA_VALIDATION) {
        // Use SchemaValidator
    } else {
        // Use legacy validation
    }
}
```

**Used by:**
- `LocalLlmOcrService` (primary extraction path)
- Other entry points that call `CouponJsonValidator.parseStrict()`

---

## Testing Strategy

### Phase 1: Prompt Comparison (Schema OFF)

1. **Capture baseline with legacy prompts:**
   - Set `USE_SCHEMA_PROMPTS = false`
   - Extract 10 test coupons
   - Record accuracy metrics

2. **Compare manual vs generated prompts:**
   - Read `buildQwenPromptManual()` output
   - Call `PromptGenerator.generateCompletePrompt()`
   - Verify structural equivalence

### Phase 2: Schema Prompt Testing (Schema ON)

1. **Enable schema prompts:**
   - Set `USE_SCHEMA_PROMPTS = true`
   - Set `USE_SCHEMA_VALIDATION = false` (validation still legacy)

2. **Test same 10 coupons:**
   - Compare extraction accuracy vs baseline
   - Check for regressions

3. **Verify logs:**
   - Look for `PromptGenerator` calls
   - Confirm prompt structure

### Phase 3: Full Schema Validation (Both ON)

1. **Enable schema validation:**
   - Set `USE_SCHEMA_PROMPTS = true`
   - Set `USE_SCHEMA_VALIDATION = true`

2. **Test same 10 coupons:**
   - Compare extraction accuracy vs baseline
   - Check validation error messages

3. **Verify logs:**
   - Look for `SchemaValidator` calls
   - Confirm validation passes

### Phase 4: Edge Cases

Test with:
- Missing fields (null values)
- Invalid JSON
- Wrong types (string instead of number)
- Unknown keys
- Nested cashback structure
- Enum values (percent, amount, text)

---

## Rollback Plan

If issues arise, rollback is instant:

1. **Disable feature flags:**
   ```kotlin
   private const val USE_SCHEMA_PROMPTS = false
   private const val USE_SCHEMA_VALIDATION = false
   ```

2. **Rebuild APK:**
   ```bash
   ./gradlew :app:assembleDebug
   ```

3. **Reinstall:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

No code changes needed—just flag toggles.

---

## Benefits of Schema-Driven Architecture

### Before (Manual)

❌ Prompt and grammar defined in separate places  
❌ Changes require updating multiple files  
❌ Validator logic hardcoded  
❌ No single source of truth  
❌ Difficult to add/remove fields  

### After (Schema-Driven)

✅ Schema is single source of truth  
✅ Prompt auto-generated from schema  
✅ Grammar auto-generated from schema  
✅ Validator auto-generated from schema  
✅ Add field → regenerate all artifacts  
✅ Consistent metadata across all layers  

### Example: Adding a New Field

**Before:**
1. Update `LocalLlmOcrService.buildQwenPrompt()`
2. Update `coupon_schema.gbnf` manually
3. Update `CouponJsonValidator.ALLOWED_KEYS`
4. Update `CouponJsonValidator.validateFieldConstraints()`
5. Update extraction parsing logic
6. Update tests

**After:**
1. Add field to `CouponSchema.SCHEMA`
2. Regenerate with `USE_SCHEMA_PROMPTS = true`
3. Done

---

## Current Entry Points

### Using Schema (when flags enabled):

1. **LocalLlmOcrService** ✅ (primary LLM path)
2. **CouponJsonValidator** ✅ (wrapper for all callers)

### Indirect via CouponJsonValidator:

3. **ImageProcessor** → calls `CouponJsonValidator.parseStrict()`
4. **CouponFormViewModel** → calls `CouponJsonValidator.parseStrict()`
5. **SmartCaptureViewModel** → calls `CouponJsonValidator.parseStrict()`
6. **BatchScannerViewModel** → calls `CouponJsonValidator.parseStrict()`
7. **AddFragment** → calls `CouponJsonValidator.parseStrict()`

**Status:** All entry points will use schema validation when `CouponJsonValidator.USE_SCHEMA_VALIDATION = true`.

---

## Next Steps

### For Testing:

1. Enable feature flags (prompts + validation)
2. Build APK
3. Test on device with real coupons
4. Compare accuracy vs baseline (legacy)
5. Monitor inference timing
6. Check for regressions

### For Production:

1. Complete device testing
2. Validate accuracy ≥ baseline
3. Confirm no performance regression
4. Update feature flags to `true` by default
5. Merge `feature/schema-driven-architecture` → `main`
6. Deploy

### Future Enhancements:

- Add Gradle task for grammar regeneration
- Dynamic schema updates from server
- Multi-model support (receipt, invoice)
- Field transformers for post-processing
- Conditional field support
- Schema versioning

---

## Troubleshooting

### Issue: Schema validation fails

**Check:**
- JSON structure matches schema field names
- Required fields present (storeName, description)
- Types correct (string, number, object)
- Enum values valid (percent, amount, text)

**Logs:**
```
SchemaValidator: Schema validation failed: [Missing required field: storeName]
```

### Issue: Prompt too long

**Fix:**
- Reduce `OCR_SNIPPET_MAX_CHARS` (currently 2000)
- Shorten field hints/examples in `CouponSchema.kt`
- Check token estimate: `PromptGenerator.estimateTokenCount(prompt)`

### Issue: Build fails

**Check:**
- Kotlin property name collisions (use unique method names)
- Imports correct (`com.example.coupontracker.schema.*`)
- No cyclic dependencies

---

## Contact & Support

Implementation complete. Ready for device testing.

For issues or questions about the schema architecture, refer to:
- `SCHEMA_IMPLEMENTATION_TRACKER.md` - detailed progress log
- `schema/` package - all schema-driven code
- Test files - usage examples

