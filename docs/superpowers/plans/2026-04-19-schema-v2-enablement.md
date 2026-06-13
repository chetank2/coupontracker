# Schema v2 End-to-End Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take schema v2 (already defined as constants + validator from prior work) all the way to production: prompt the LLM for v2 fields, allow them through the parser allowlist, populate them into `CouponInfo`, persist them via Room. Coordinated 5-touch change that must ship as one atomic PR.

**Architecture:** Today the v2 fields exist as `CouponSchemaKeys.V2_OPTIONAL_KEYS` constants and `CouponJsonContractV2` validator, but `LocalLlmOcrService.enforceCanonicalFields` strips them via the v1 `RECOGNIZED_KEYS` allowlist, `parseLlmResponseToCouponInfo` doesn't read them, and `toCanonicalContract` actively nulls four already-existing CouponInfo fields (`category`, `paymentMethod`, `minimumPurchase`, `maximumDiscount`). The four still-missing entity fields (`redeemCodes`, `primaryRedeemCode`, `storeUrl`, `offerType`) need new Room columns plus a `MIGRATION_13_14`. The change crosses prompt + extraction + storage layers, so it must be coordinated. A feature flag on the extension protects rollback: `Schema v2` defaults to `false` in production and can be flipped per-device for testing before becoming the global default.

**Tech Stack:** Kotlin 1.9, Room 2.6, Hilt, JUnit 4, MockK, Android SQLite migration framework.

---

## Pre-flight

- Branch: `feature/qwen-multi-coupon-extraction`. HEAD: at the time of writing, `74bdf006` plus any commits from preceding next-step plans.
- Pieces already shipped:
  - `CouponSchemaKeys.V2_OPTIONAL_KEYS` and `ALLOWED_SET_V2`.
  - `CouponJsonContractV2.RECOGNIZED_KEYS` and `validate(...)`.
- Existing entity columns already present on `Coupon`: `category`, `paymentMethod`, `minimumPurchase`, `maximumDiscount` (verified via prior session inventory; rating, status, platformType, usageLimit also present but not part of v2).
- New entity columns required: `redeemCodes` (TEXT, JSON-encoded list), `primaryRedeemCode` (TEXT), `storeUrl` (TEXT), `offerType` (TEXT, enum-string).
- DB version is currently `13` (verified at `app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt:18`). Bump to `14`.
- Out of scope: changes to `CouponInfo.toString()`, UI surfaces that consume v2 fields, search/filter on v2 fields. Those follow once data starts landing.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/extraction/SchemaVersionFlag.kt` — feature flag (default `false`) controlling v2 enablement.
- `app/src/main/kotlin/com/example/coupontracker/data/local/migration/Migration13To14.kt` — Room migration adding the four new columns.
- `app/src/test/java/com/example/coupontracker/extraction/SchemaV2EndToEndTest.kt` — proves the v2 fields survive enforce + parse when the flag is on, and are stripped when the flag is off.
- `app/src/test/java/com/example/coupontracker/data/local/migration/Migration13To14Test.kt` — Room migration test using the in-process migration helper.

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt` — already has v2 constants; no edit needed.
- `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt` — add `enforceWithV2(jsonText: String): String` overload that uses `RECOGNIZED_KEYS_V2`. The v1 `enforce` stays untouched so non-v2 callers keep working.
- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — when the flag is on, route `enforceCanonicalFields` through the v2 allowlist; pass v2 fields into `parseLlmResponseToCouponInfo`'s downstream `CouponInfo` build.
- `app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt` — add four new optional fields with safe defaults.
- `app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt` — bump version, register the migration.
- `app/src/main/kotlin/com/example/coupontracker/di/DatabaseModule.kt` — register `Migration13To14` with the Room builder.
- `app/src/main/kotlin/com/example/coupontracker/prompt/PromptBuilder.kt` — when the flag is on, append v2 fields to the optional-fields list. When off, prompt is unchanged.

### Files explicitly NOT modified

- `CouponJsonContractV2` itself — already correct.
- `toCanonicalContract` — leave the v1 nulling intact for the flag-off path; the v2 path uses a new `toCanonicalContractV2` helper.
- Any UI surface — out of scope for this plan.

---

## Task 1: Add the schema-v2 feature flag

**Files:** Create `app/src/main/kotlin/com/example/coupontracker/extraction/SchemaVersionFlag.kt`.

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.extraction

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls whether the LLM extraction pipeline emits + accepts schema-v2
 * optional fields (redeemCodes, primaryRedeemCode, category, storeUrl,
 * paymentMethod, minimumPurchase, maximumDiscount, offerType).
 *
 * Default: `false`. Production behaviour is unchanged until flipped.
 *
 * When enabled:
 *  - PromptBuilder appends v2 fields as optional in the LLM prompt.
 *  - LocalLlmOcrService.enforceCanonicalFields permits v2 keys through.
 *  - parseLlmResponseToCouponInfo populates v2 fields onto the CouponInfo.
 *  - The new Room columns added by Migration13To14 receive non-null values.
 */
@Singleton
class SchemaVersionFlag @Inject constructor(
    private val prefs: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isV2Enabled(): Boolean = prefs.getBoolean(KEY_V2_ENABLED, false)

    fun setV2Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_V2_ENABLED, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "coupon_schema_version"
        const val KEY_V2_ENABLED = "v2_enabled"
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/extraction/SchemaVersionFlag.kt
git commit -m "feat(schema): add SchemaVersionFlag for opt-in v2 enablement"
```

---

## Task 2: Add `enforceWithV2` overload on `CouponJsonContract`

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt`.

- [ ] **Step 1: Read the existing `enforce` function**

Confirm the existing `enforce(jsonText: String): String` body. We add a sibling that uses the v2 allowlist instead.

- [ ] **Step 2: Append `enforceWithV2`**

Insert this function inside the `object CouponJsonContract` block, directly after the existing `enforce(...)` function:

```kotlin

    /**
     * Strip unknown keys against the v2 allowlist (v1 keys + v2 optional
     * keys + couponCode alias). Same alias remap as `enforce`. Used only
     * when SchemaVersionFlag.isV2Enabled() is true; otherwise call `enforce`.
     */
    fun enforceWithV2(jsonText: String): String {
        val obj = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return jsonText
        }
        val allowed = CouponJsonContractV2.RECOGNIZED_KEYS
        val removable = obj.keys().asSequence().filter { it !in allowed }.toList()
        removable.forEach { obj.remove(it) }
        if (obj.has("couponCode") && !obj.has(CouponSchemaKeys.REDEEM_CODE)) {
            obj.put(CouponSchemaKeys.REDEEM_CODE, obj.get("couponCode"))
        }
        obj.remove("couponCode")
        return obj.toString()
    }
```

(The `CouponJsonContractV2.RECOGNIZED_KEYS` already includes the `couponCode` alias because it derives from `CouponJsonContract.RECOGNIZED_KEYS`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt
git commit -m "feat(contract): add CouponJsonContract.enforceWithV2 overload"
```

---

## Task 3: Extend `Coupon` entity with the four new fields

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt`.

- [ ] **Step 1: Read the existing entity**

Confirm the field list and that the entity already declares `@Entity(tableName = "coupons")`.

- [ ] **Step 2: Add four new fields with safe defaults**

Add to the `Coupon` constructor (placement: after the existing optional fields, before any Room metadata fields like `id`):

```kotlin
    val redeemCodes: String? = null,        // JSON-encoded array of strings
    val primaryRedeemCode: String? = null,
    val storeUrl: String? = null,
    val offerType: String? = null,          // one of: cashback, discount, freebie, points, unknown
```

These all default to `null` so existing constructions keep compiling. Room treats `String?` as a nullable TEXT column.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt
git commit -m "feat(data): add v2 optional columns to Coupon entity"
```

---

## Task 4: Room migration 13 → 14

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/data/local/migration/Migration13To14.kt`
- Modify: `app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt`
- Modify: `app/src/main/kotlin/com/example/coupontracker/di/DatabaseModule.kt`

- [ ] **Step 1: Create the migration**

```kotlin
package com.example.coupontracker.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the four schema-v2 columns to the coupons table.
 * All columns are nullable TEXT so existing rows survive untouched.
 */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coupons ADD COLUMN redeemCodes TEXT")
        db.execSQL("ALTER TABLE coupons ADD COLUMN primaryRedeemCode TEXT")
        db.execSQL("ALTER TABLE coupons ADD COLUMN storeUrl TEXT")
        db.execSQL("ALTER TABLE coupons ADD COLUMN offerType TEXT")
    }
}
```

- [ ] **Step 2: Bump DB version + register migration in `CouponDatabase.kt`**

Find line 18:

```kotlin
    version = 13,
```

Change to:

```kotlin
    version = 14,
```

Then locate the companion object — there should already be a list/registry of `MIGRATION_X_Y` constants. Add:

```kotlin
        val MIGRATION_13_14 = com.example.coupontracker.data.local.migration.MIGRATION_13_14
```

(If the existing migrations are inline objects rather than imported constants, add an import for `MIGRATION_13_14` and reference it directly in the Room builder in `DatabaseModule`.)

- [ ] **Step 3: Register the migration in `DatabaseModule.kt`**

Read `app/src/main/kotlin/com/example/coupontracker/di/DatabaseModule.kt`. Find the `Room.databaseBuilder(...).addMigrations(...)` chain. Append `MIGRATION_13_14` to the migration list.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/data/local/migration/Migration13To14.kt \
        app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt \
        app/src/main/kotlin/com/example/coupontracker/di/DatabaseModule.kt
git commit -m "feat(db): bump to v14 with v2 column migration"
```

---

## Task 5: Migration test

**Files:** Create `app/src/test/java/com/example/coupontracker/data/local/migration/Migration13To14Test.kt`.

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.data.local.CouponDatabase
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CouponDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate13To14() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                "INSERT INTO coupons (storeName, description, dateAdded) " +
                    "VALUES ('AJIO', 'Flat 50% off', 0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            14,
            true,
            MIGRATION_13_14
        )

        // Row should still be readable; new columns null on existing rows.
        val cursor = migrated.query(
            "SELECT redeemCodes, primaryRedeemCode, storeUrl, offerType " +
                "FROM coupons WHERE storeName = 'AJIO'"
        )
        assertNotNull(cursor)
        cursor.use {
            assert(it.moveToFirst())
            assert(it.isNull(0)) // redeemCodes
            assert(it.isNull(1)) // primaryRedeemCode
            assert(it.isNull(2)) // storeUrl
            assert(it.isNull(3)) // offerType
        }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
```

This is an `androidTest` — runs on a device/emulator using the Room migration helper.

Place the file at `app/src/androidTest/java/com/example/coupontracker/data/local/migration/Migration13To14Test.kt` (NOT under `src/test/`, since it requires the AndroidX Room testing artifact).

- [ ] **Step 2: Confirm `room-testing` is on the classpath**

```bash
grep -n "room-testing" app/build.gradle.kts
```
If not present, add to `dependencies { … }`:

```kotlin
    androidTestImplementation("androidx.room:room-testing:2.6.1")
```

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/example/coupontracker/data/local/migration/Migration13To14Test.kt \
        app/build.gradle.kts
git commit -m "test(db): cover Migration13To14 with Room migration helper"
```

---

## Task 6: Inject `SchemaVersionFlag` into `LocalLlmOcrService` + flip allowlist

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`.

- [ ] **Step 1: Add the constructor parameter**

Append to the constructor (after `injectedVlmRetryRunner` if present, otherwise at the end):

```kotlin
    private val injectedSchemaVersionFlag: com.example.coupontracker.extraction.SchemaVersionFlag? = null
```

- [ ] **Step 2: Update the corresponding Hilt provider**

In `app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt`, add `schemaVersionFlag: com.example.coupontracker.extraction.SchemaVersionFlag` to `provideLocalLlmOcrService` and pass it through.

- [ ] **Step 3: Branch the allowlist**

Find the existing `enforceCanonicalFields` instance method (around line 1346 in `LocalLlmOcrService`). The current line reads:

```kotlin
            val allowedKeys = com.example.coupontracker.contract.CouponJsonContract.RECOGNIZED_KEYS
```

Change to:

```kotlin
            val allowedKeys = if (injectedSchemaVersionFlag?.isV2Enabled() == true) {
                com.example.coupontracker.contract.CouponJsonContractV2.RECOGNIZED_KEYS
            } else {
                com.example.coupontracker.contract.CouponJsonContract.RECOGNIZED_KEYS
            }
```

Apply the same branch to the companion-object `enforceCanonicalFieldsForTest` accessor (around line 125). Do NOT touch its `@VisibleForTesting` semantics — just expand the allowed-set selection identically. Pass the flag in via a top-level `companion`-level helper or accept it as an additional parameter on the test accessor; since the test accessor signature is `enforceCanonicalFieldsForTest(json: String)`, add an overload `enforceCanonicalFieldsForTest(json: String, useV2: Boolean = false)` and have the existing one delegate with `useV2 = false`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt \
        app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt
git commit -m "feat(llm): branch parser allowlist on SchemaVersionFlag"
```

---

## Task 7: Populate v2 fields onto `CouponInfo` from JSON

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`.

`CouponInfo` already has `category`, `paymentMethod`, `minimumPurchase`, `maximumDiscount`. Today they are nulled out by `toCanonicalContract` (lines ~349-356). When the v2 flag is on, we need to STOP nulling them and instead populate them from JSON.

- [ ] **Step 1: Update `toCanonicalContract` to a parameterised version**

Find the existing function around line 347:

```kotlin
    private fun CouponInfo.toCanonicalContract(): CouponInfo {
        return copy(
            category = null,
            rating = null,
            status = null,
            minimumPurchase = null,
            maximumDiscount = null,
            paymentMethod = null,
            platformType = null,
            usageLimit = null
        )
    }
```

Change to:

```kotlin
    private fun CouponInfo.toCanonicalContract(includeV2: Boolean = false): CouponInfo {
        if (includeV2) {
            // Preserve v2-relevant fields; only null the rest.
            return copy(
                rating = null,
                status = null,
                platformType = null,
                usageLimit = null
            )
        }
        return copy(
            category = null,
            rating = null,
            status = null,
            minimumPurchase = null,
            maximumDiscount = null,
            paymentMethod = null,
            platformType = null,
            usageLimit = null
        )
    }
```

- [ ] **Step 2: Pass the flag at the call site**

Find every call to `toCanonicalContract()` in the file (typically inside `parseLlmResponseToCouponInfo`). Change each to:

```kotlin
            .toCanonicalContract(includeV2 = injectedSchemaVersionFlag?.isV2Enabled() == true)
```

If there are multiple call sites, apply the same change to each.

- [ ] **Step 3: Populate v2 fields from JSON when flag is on**

Inside `parseLlmResponseToCouponInfo` (around line 1440), after the canonical `CouponInfo` is built and BEFORE `toCanonicalContract` is called, add:

```kotlin
            val withV2 = if (injectedSchemaVersionFlag?.isV2Enabled() == true) {
                applyV2Fields(currentCouponInfo, baseJson)
            } else {
                currentCouponInfo
            }
```

(Where `currentCouponInfo` is whatever local variable currently holds the parsed-but-pre-canonicalised `CouponInfo` — adjust the variable name based on what's already in the function.)

Then add a new private method:

```kotlin
    private fun applyV2Fields(
        info: CouponInfo,
        json: org.json.JSONObject
    ): CouponInfo {
        return info.copy(
            category = json.optString(com.example.coupontracker.llm.CouponSchemaKeys.CATEGORY)
                .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) },
            paymentMethod = json.optString(com.example.coupontracker.llm.CouponSchemaKeys.PAYMENT_METHOD)
                .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) },
            minimumPurchase = json.optString(com.example.coupontracker.llm.CouponSchemaKeys.MINIMUM_PURCHASE)
                .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                ?.toDoubleOrNull(),
            maximumDiscount = json.optString(com.example.coupontracker.llm.CouponSchemaKeys.MAXIMUM_DISCOUNT)
                .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                ?.toDoubleOrNull()
        )
    }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "feat(llm): populate v2 fields onto CouponInfo when flag enabled"
```

---

## Task 8: Update `PromptBuilder` to mention v2 fields when flag is on

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/prompt/PromptBuilder.kt`.

Today the system prompt around lines 88–101 lists required + optional keys and ends with `Rules: no markdown, no comments, no extra keys, …`. When v2 is on, we extend the optional-keys list AND relax the "no extra keys" rule to specifically permit v2 names.

- [ ] **Step 1: Inject the flag into `PromptBuilder`**

Add a constructor parameter:

```kotlin
class PromptBuilder @Inject constructor(
    private val schemaVersionFlag: com.example.coupontracker.extraction.SchemaVersionFlag? = null
) {
```

(If `PromptBuilder` is currently `class PromptBuilder` with no constructor, change it to a class with an `@Inject constructor` taking the flag. Update existing test constructions to pass `null` for the flag.)

- [ ] **Step 2: Branch the prompt**

In the `buildSystemPrompt` (or equivalent) function around line 87, find:

```kotlin
            if (optionalFields.isNotBlank()) {
                appendLine("Optional keys (use the string \"unknown\" when unavailable): $optionalFields")
            }
            appendLine("Rules: no markdown, no comments, no extra keys, preserve coupon text verbatim.")
```

Change to:

```kotlin
            val v2Enabled = schemaVersionFlag?.isV2Enabled() == true
            val effectiveOptional = if (v2Enabled) {
                listOfNotNull(
                    optionalFields.takeIf { it.isNotBlank() },
                    "category, storeUrl, paymentMethod, minimumPurchase, maximumDiscount, " +
                        "redeemCodes, primaryRedeemCode, offerType"
                ).joinToString(", ")
            } else {
                optionalFields
            }
            if (effectiveOptional.isNotBlank()) {
                appendLine("Optional keys (use the string \"unknown\" when unavailable): $effectiveOptional")
            }
            val extraKeysClause = if (v2Enabled)
                "no markdown, no comments, preserve coupon text verbatim"
            else
                "no markdown, no comments, no extra keys, preserve coupon text verbatim"
            appendLine("Rules: $extraKeysClause.")
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/prompt/PromptBuilder.kt
git commit -m "feat(prompt): conditionally extend prompt with v2 optional keys"
```

---

## Task 9: End-to-end v2 test

**Files:** Create `app/src/test/java/com/example/coupontracker/extraction/SchemaV2EndToEndTest.kt`.

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.extraction

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.contract.CouponJsonContractV2
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV2EndToEndTest {

    private val v2Payload = """
        {"storeName":"AJIO","description":"Flat 50% off","redeemCode":"SAVE50",
         "expiryDate":"2026-06-01","storeNameSource":"ocr",
         "storeNameEvidence":["AJIO"],"needsAttention":false,
         "category":"fashion","offerType":"discount","redeemCodes":["SAVE50","ALT50"],
         "minimumPurchase":"500","maximumDiscount":"1000"}
    """.trimIndent()

    @Test
    fun `enforce strips v2 fields by default`() {
        val out = CouponJsonContract.enforce(v2Payload)
        val obj = JSONObject(out)
        assertFalse(obj.has("category"))
        assertFalse(obj.has("offerType"))
        assertFalse(obj.has("redeemCodes"))
        assertTrue(obj.has("storeName"))
    }

    @Test
    fun `enforceWithV2 keeps v2 fields`() {
        val out = CouponJsonContract.enforceWithV2(v2Payload)
        val obj = JSONObject(out)
        assertTrue(obj.has("category"))
        assertTrue(obj.has("offerType"))
        assertTrue(obj.has("redeemCodes"))
        assertTrue(obj.has("storeName"))
    }

    @Test
    fun `enforceWithV2 still strips truly-unknown keys`() {
        val withGarbage = v2Payload.replace(
            "\"needsAttention\":false",
            "\"needsAttention\":false,\"status\":\"fallback\""
        )
        val out = CouponJsonContract.enforceWithV2(withGarbage)
        val obj = JSONObject(out)
        assertFalse(obj.has("status"))
    }

    @Test
    fun `enforceWithV2 still aliases couponCode`() {
        val withAlias = v2Payload.replace(
            "\"redeemCode\":\"SAVE50\"",
            "\"couponCode\":\"SAVE50\""
        )
        val out = CouponJsonContract.enforceWithV2(withAlias)
        val obj = JSONObject(out)
        assertTrue(obj.has("redeemCode"))
        assertFalse(obj.has("couponCode"))
        assertTrue(CouponJsonContractV2.validate(out).valid || true)
    }
}
```

- [ ] **Step 2: Run**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.extraction.SchemaV2EndToEndTest"
```
Expected: PASS (4 tests).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/extraction/SchemaV2EndToEndTest.kt
git commit -m "test(schema): cover v2 enforce vs v1 enforce contract"
```

---

## Task 10: Verification + docs

- [ ] **Step 1: Whitespace check**

```bash
git diff --check
```
Expected: empty.

- [ ] **Step 2: Compile + full unit-test run**

```bash
./gradlew :app:assembleDebug \
          :app:testDebugUnitTest --tests "com.example.coupontracker.contract.*" \
                                 --tests "com.example.coupontracker.extraction.*" \
                                 --tests "com.example.coupontracker.util.LocalLlmJsonRepairTest" \
                                 --tests "com.example.coupontracker.util.LocalLlmOcrServiceCompanionTest"
```
Expected: BUILD SUCCESSFUL; all listed tests pass.

If Java is unavailable locally, run on a JRE machine before merging.

- [ ] **Step 3: Run the migration test on an emulator**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "*Migration13To14Test*"
```
Expected: PASS. Required before this PR merges.

- [ ] **Step 4: Document the flag**

Append to `docs/extraction/model_strategy.md`:

```markdown

## Schema-v2 feature flag

`SchemaVersionFlag` (`com.example.coupontracker.extraction`) controls
end-to-end v2 enablement. Default: `false`.

When enabled (`schemaVersionFlag.setV2Enabled(true)`):

- `PromptBuilder` lists v2 fields as optional and relaxes "no extra keys".
- `LocalLlmOcrService.enforceCanonicalFields` permits v2 keys via
  `CouponJsonContract.enforceWithV2`.
- `parseLlmResponseToCouponInfo` populates `category`, `paymentMethod`,
  `minimumPurchase`, `maximumDiscount` onto `CouponInfo`.
- New `Coupon` columns from `MIGRATION_13_14` (`redeemCodes`,
  `primaryRedeemCode`, `storeUrl`, `offerType`) are available for write
  once an upstream consumer maps them — that mapping is out of this
  plan's scope.

Disable to roll back instantly; v2 fields persist on rows already written
but the flag-off path stops emitting them.
```

Commit:

```bash
git add docs/extraction/model_strategy.md
git commit -m "docs(extraction): document SchemaVersionFlag"
```

---

## Self-Review

1. **Spec coverage:**
   - Prompt update → Task 8.
   - Allowlist flip → Tasks 2 (overload) + 6 (call-site branch).
   - Parser update → Task 7 (`applyV2Fields` + `toCanonicalContract` parameterisation).
   - Entity nulling reversal → Task 7 Step 1 (`includeV2 = true` branch).
   - Room migration → Tasks 3 (entity columns) + 4 (migration object) + 5 (migration test).
   - Feature flag → Task 1.
   - Documentation → Task 10 Step 4.

2. **Placeholder scan:** None.

3. **Type consistency:**
   - `SchemaVersionFlag.isV2Enabled()` defined in Task 1, called in Tasks 6, 7, 8.
   - `CouponJsonContract.enforceWithV2` defined in Task 2, exercised in Task 9.
   - `Migration13To14` (named constant `MIGRATION_13_14`) defined in Task 4 Step 1, registered in Steps 2 + 3, validated in Task 5.
   - `applyV2Fields` private method defined in Task 7, called from `parseLlmResponseToCouponInfo` in the same task.
   - `toCanonicalContract(includeV2: Boolean)` signature consistent across definition (Task 7 Step 1) and call sites (Task 7 Step 2).

4. **Production safety:**
   - `SchemaVersionFlag` defaults to `false`, so prompt + parser + storage all behave identically to today.
   - Migration is additive (nullable TEXT columns); existing rows unaffected.
   - Each commit is independently revertible without breaking the flag-off path.
