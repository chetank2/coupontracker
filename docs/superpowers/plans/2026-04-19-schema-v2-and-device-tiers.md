# Schema v2 and Device-Tier Runtime (Phases 8 + 9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the canonical coupon schema with the next tranche of fields (`redeemCodes[]`, `primaryRedeemCode`, `category`, `storeUrl`, `paymentMethod`, `minimumPurchase`, `maximumDiscount`, `offerType`), and introduce device-tier runtime selection that picks extraction strategies based on RAM / thermal / model-asset availability.

**Architecture:** Schema v2 introduces additive fields — v1 payloads remain valid because the new fields are all optional. `CouponJsonContract` version-tags its outputs; `CouponSchemaKeys` grows; `SchemaValidator` gains v2 rules; the parser copies v1 values through unchanged and populates v2 fields when the LLM produces them. Device tier is decided at app startup by `DeviceCapabilityProbe` (RAM, thermal state, battery saver, model-asset presence) and applied via `DeviceTierPolicy` which writes role→mode choices into `ModelStrategyConfig`. The result: low-end devices avoid VLM retry entirely; mid-tier devices keep Tesseract fallback; high-end devices enable VLM retry.

**Tech Stack:** Kotlin 1.9, Hilt 2.48, `android.app.ActivityManager`, `android.os.PowerManager`, JUnit 4, MockK.

---

## Pre-flight

- Plans 1–6 landed.
- `CouponSchemaKeys`, `CouponJsonContract`, `SchemaValidator`, `ModelStrategyConfig`, `ModelSelector`, `CouponRegionPipeline`, `VlmRetryEvaluator` all exist.
- `CouponSchema.kt` already defines a data class — inspect it before extending.

## File Structure

### Files to create

- `app/src/main/kotlin/com/example/coupontracker/schema/CouponSchemaV2.kt` — new-field name constants and offerType enum.
- `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContractV2.kt` — validator for the v2 fields.
- `app/src/test/java/com/example/coupontracker/contract/CouponJsonContractV2Test.kt`
- `app/src/main/kotlin/com/example/coupontracker/runtime/DeviceCapability.kt` — data class.
- `app/src/main/kotlin/com/example/coupontracker/runtime/DeviceCapabilityProbe.kt`
- `app/src/main/kotlin/com/example/coupontracker/runtime/DeviceTier.kt` — enum.
- `app/src/main/kotlin/com/example/coupontracker/runtime/DeviceTierPolicy.kt` — applies tier → strategy.
- `app/src/test/java/com/example/coupontracker/runtime/DeviceCapabilityProbeTest.kt`
- `app/src/test/java/com/example/coupontracker/runtime/DeviceTierPolicyTest.kt`
- `app/src/main/kotlin/com/example/coupontracker/data/local/migration/SchemaV1ToV2Migration.kt` — Room migration if a DB column changes; see Task 4.

### Files to modify

- `app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt` — add v2 field-name constants.
- `app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContract.kt` — include v2 keys in `RECOGNIZED_KEYS`; expose a v2 validator.
- `app/src/main/kotlin/com/example/coupontracker/schema/CouponSchema.kt` — extend the schema definition if parser consumes it as a structured type.
- `app/src/main/kotlin/com/example/coupontracker/prompt/PromptBuilder.kt` — add v2 field instructions.
- `app/src/main/kotlin/com/example/coupontracker/CouponTrackerApplication.kt` — call `DeviceTierPolicy.apply(...)` at startup.

---

## Task 1: Extend `CouponSchemaKeys` with v2 fields

- [ ] **Step 1: Append v2 constants**

At the end of the existing `CouponSchemaKeys` object (inside the braces, after `val ALLOWED_SET`), add:

```kotlin
    // --- Schema v2 (additive) ---
    const val REDEEM_CODES = "redeemCodes"
    const val PRIMARY_REDEEM_CODE = "primaryRedeemCode"
    const val CATEGORY = "category"
    const val STORE_URL = "storeUrl"
    const val PAYMENT_METHOD = "paymentMethod"
    const val MINIMUM_PURCHASE = "minimumPurchase"
    const val MAXIMUM_DISCOUNT = "maximumDiscount"
    const val OFFER_TYPE = "offerType"

    val V2_OPTIONAL_KEYS: Set<String> = setOf(
        REDEEM_CODES,
        PRIMARY_REDEEM_CODE,
        CATEGORY,
        STORE_URL,
        PAYMENT_METHOD,
        MINIMUM_PURCHASE,
        MAXIMUM_DISCOUNT,
        OFFER_TYPE
    )

    val ALLOWED_SET_V2: Set<String> = ALLOWED_SET + V2_OPTIONAL_KEYS
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt
git commit -m "feat(schema): add schema v2 optional field constants"
```

---

## Task 2: Add `CouponJsonContractV2`

**Files:** Create `CouponJsonContractV2.kt` + test.

- [ ] **Step 1: Create the v2 validator**

```kotlin
package com.example.coupontracker.contract

import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Schema-v2 contract. Additive: v1-compliant payloads remain valid here.
 * Extra v2 fields are optional; if present, they must have the correct type
 * and, for `offerType`, one of the allowed enum values.
 */
object CouponJsonContractV2 {

    val ALLOWED_OFFER_TYPES: Set<String> =
        setOf("cashback", "discount", "freebie", "points", "unknown")

    val RECOGNIZED_KEYS: Set<String> = CouponJsonContract.RECOGNIZED_KEYS +
        CouponSchemaKeys.V2_OPTIONAL_KEYS

    fun validate(jsonText: String): CouponJsonContract.ContractReport {
        val obj = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return CouponJsonContract.ContractReport(
                valid = false,
                missingKeys = CouponJsonContract.REQUIRED_KEYS,
                unknownKeys = emptySet(),
                structuralErrors = listOf("parse: ${e.message ?: "invalid JSON"}")
            )
        }

        val v1Report = CouponJsonContract.validate(
            obj.let { filtered ->
                val copy = JSONObject(filtered.toString())
                CouponSchemaKeys.V2_OPTIONAL_KEYS.forEach { copy.remove(it) }
                copy
            }
        )

        val errors = v1Report.structuralErrors.toMutableList()

        if (obj.has(CouponSchemaKeys.REDEEM_CODES) &&
            obj.optJSONArray(CouponSchemaKeys.REDEEM_CODES) == null) {
            errors += "${CouponSchemaKeys.REDEEM_CODES} must be a JSON array"
        }
        if (obj.has(CouponSchemaKeys.OFFER_TYPE)) {
            val value = obj.optString(CouponSchemaKeys.OFFER_TYPE)
            if (value !in ALLOWED_OFFER_TYPES) {
                errors += "${CouponSchemaKeys.OFFER_TYPE} must be one of $ALLOWED_OFFER_TYPES"
            }
        }
        listOf(
            CouponSchemaKeys.PRIMARY_REDEEM_CODE,
            CouponSchemaKeys.CATEGORY,
            CouponSchemaKeys.STORE_URL,
            CouponSchemaKeys.PAYMENT_METHOD,
            CouponSchemaKeys.MINIMUM_PURCHASE,
            CouponSchemaKeys.MAXIMUM_DISCOUNT
        ).forEach { key ->
            if (obj.has(key) && obj.opt(key) !is String) {
                errors += "$key must be a string when present"
            }
        }

        val unknownV2 = obj.keys().asSequence().toSet() - RECOGNIZED_KEYS
        return CouponJsonContract.ContractReport(
            valid = v1Report.missingKeys.isEmpty() && unknownV2.isEmpty() && errors.isEmpty(),
            missingKeys = v1Report.missingKeys,
            unknownKeys = unknownV2,
            structuralErrors = errors
        )
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
package com.example.coupontracker.contract

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CouponJsonContractV2Test {

    private val v1 = """{"storeName":"AJIO","description":"x","redeemCode":"SAVE50","expiryDate":"2026-06-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false}"""

    @Test
    fun `v1 payload remains valid under v2 contract`() {
        assertTrue(CouponJsonContractV2.validate(v1).valid)
    }

    @Test
    fun `v2 payload with valid optional fields passes`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"offerType\":\"discount\",\"category\":\"fashion\",\"redeemCodes\":[\"SAVE50\"]")
        assertTrue(CouponJsonContractV2.validate(v2).valid)
    }

    @Test
    fun `invalid offerType flagged`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"offerType\":\"weird\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains("offerType") })
    }

    @Test
    fun `redeemCodes must be array`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"redeemCodes\":\"SAVE50\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.structuralErrors.any { it.contains("redeemCodes") })
    }

    @Test
    fun `unknown key still flagged`() {
        val v2 = v1.replace("\"needsAttention\":false",
            "\"needsAttention\":false,\"status\":\"fallback\"")
        val report = CouponJsonContractV2.validate(v2)
        assertFalse(report.valid)
        assertTrue(report.unknownKeys.contains("status"))
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.contract.CouponJsonContractV2Test"
```
Expected: PASS (5 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/contract/CouponJsonContractV2.kt \
        app/src/test/java/com/example/coupontracker/contract/CouponJsonContractV2Test.kt
git commit -m "feat(contract): add CouponJsonContractV2 validator"
```

---

## Task 3: Propagate v2 fields through parser and prompt

**Files:** Modify `LocalLlmOcrService.kt` and `PromptBuilder.kt`.

- [ ] **Step 1: Update the prompt**

Read `PromptBuilder.kt`. Where the current prompt lists the seven canonical fields (search for `storeNameSource`), append one line documenting the new optional fields:

```
Optionally include: redeemCodes (array of strings), primaryRedeemCode,
category, storeUrl, paymentMethod, minimumPurchase, maximumDiscount,
offerType (one of cashback|discount|freebie|points|unknown).
Always include the seven required v1 fields.
```

- [ ] **Step 2: Update `LocalLlmOcrService.parseLlmResponseToCouponInfo`**

The existing function copies v1 fields from the JSON into `CouponInfo`. Extend it: after the v1 field copy, read optional v2 fields from the JSON and populate corresponding `CouponInfo` fields if they exist. Look at `CouponInfo` (probably in `app/src/main/kotlin/com/example/coupontracker/data/model/`) to see which of the v2 fields already have columns (likely `minimumPurchase`, `maximumDiscount`, `paymentMethod`, `category` exist already — the other new ones may need DB migration, see Task 4).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/prompt/PromptBuilder.kt \
        app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt
git commit -m "feat(llm): populate schema v2 fields from LLM output"
```

---

## Task 4: Room migration for new columns (if needed)

- [ ] **Step 1: Inspect `CouponInfo` entity**

Find the Room entity (search for `@Entity` in `app/src/main/kotlin/com/example/coupontracker/data/local/`). Compare its columns to the v2 field set. If `redeemCodes`, `primaryRedeemCode`, `storeUrl`, `offerType` are missing columns, write a migration; skip this task if they are all present.

- [ ] **Step 2: Write the migration**

```kotlin
package com.example.coupontracker.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val SCHEMA_V1_TO_V2: Migration = object : Migration(/*from*/ X, /*to*/ X + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE coupons ADD COLUMN redeem_codes TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN primary_redeem_code TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN store_url TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE coupons ADD COLUMN offer_type TEXT DEFAULT NULL")
    }
}
```

Replace `X` with the current `@Database(version = X)` and bump the database version. Register the migration in `DatabaseModule.kt`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/data/local/
git commit -m "feat(db): schema v2 migration for new coupon columns"
```

---

## Task 5: `DeviceCapabilityProbe`

**Files:** Create value class + probe + test.

- [ ] **Step 1: Create value class**

```kotlin
package com.example.coupontracker.runtime

data class DeviceCapability(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowRamDevice: Boolean,
    val isBatterySaver: Boolean,
    val thermalStatus: Int, // android.os.PowerManager.THERMAL_STATUS_*
    val qwenModelPresent: Boolean,
    val gemmaTextModelPresent: Boolean,
    val mmprojPresent: Boolean,
    val nativeLibraryLoaded: Boolean
)
```

- [ ] **Step 2: Create the probe**

```kotlin
package com.example.coupontracker.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.coupontracker.llm.ModelAssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assets: ModelAssetManager
) {

    fun probe(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            pm.currentThermalStatus else 0
        return DeviceCapability(
            totalRamMb = mem.totalMem / (1024 * 1024),
            availableRamMb = mem.availMem / (1024 * 1024),
            isLowRamDevice = am.isLowRamDevice,
            isBatterySaver = pm.isPowerSaveMode,
            thermalStatus = thermal,
            qwenModelPresent = assets.isQwenPresent(),
            gemmaTextModelPresent = assets.isGemmaTextPresent(),
            mmprojPresent = assets.isMmprojPresent(),
            nativeLibraryLoaded = runCatching {
                System.loadLibrary("mlc_llm_jni"); true
            }.getOrDefault(false)
        )
    }
}
```

If `ModelAssetManager` does not yet expose `isQwenPresent/isGemmaTextPresent/isMmprojPresent`, add them as simple file-existence checks mirroring the existing pattern.

- [ ] **Step 3: Unit-test the probe with a mocked context**

Skip deep testing since the probe mostly reads system services; instead test the tier policy (next task) which consumes a `DeviceCapability` value object directly.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/runtime/DeviceCapability.kt \
        app/src/main/kotlin/com/example/coupontracker/runtime/DeviceCapabilityProbe.kt \
        app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt
git commit -m "feat(runtime): add DeviceCapabilityProbe"
```

(Drop `ModelAssetManager.kt` from staging if no changes needed.)

---

## Task 6: `DeviceTier` + `DeviceTierPolicy`

**Files:** Create enum + policy + test.

- [ ] **Step 1: Create `DeviceTier.kt`**

```kotlin
package com.example.coupontracker.runtime

enum class DeviceTier {
    LOW_END,   // ≤3 GB RAM, lowRamDevice true, severe thermal, or battery saver
    MID,       // default
    HIGH_END,  // >6 GB RAM, models present, not thermally throttled
    DEVELOPER  // overridden for benchmarks; VLM primary
}
```

- [ ] **Step 2: Write the policy test**

```kotlin
package com.example.coupontracker.runtime

import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelStrategyConfig
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class DeviceTierPolicyTest {

    private fun cap(
        ram: Long = 4096, lowRam: Boolean = false, bat: Boolean = false, thermal: Int = 0,
        qwen: Boolean = true, gemma: Boolean = false, mmproj: Boolean = false, native: Boolean = true
    ) = DeviceCapability(ram, ram / 2, lowRam, bat, thermal, qwen, gemma, mmproj, native)

    @Test
    fun `LOW_END disables low-confidence retry`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 2048, lowRam = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }

    @Test
    fun `HIGH_END enables VLM_QWEN retry when mmproj present`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, mmproj = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.VLM_QWEN) }
    }

    @Test
    fun `HIGH_END without mmproj stays on TEXT_QWEN retry`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, mmproj = false), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }

    @Test
    fun `battery saver forces LOW_END regardless of RAM`() {
        val config = mockk<ModelStrategyConfig>(relaxed = true)
        DeviceTierPolicy.apply(cap(ram = 8192, bat = true), config)
        verify { config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, ModelMode.TEXT_QWEN) }
    }
}
```

- [ ] **Step 3: Implement `DeviceTierPolicy`**

```kotlin
package com.example.coupontracker.runtime

import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelStrategyConfig
import android.os.PowerManager

object DeviceTierPolicy {

    fun tierFor(cap: DeviceCapability): DeviceTier = when {
        cap.isBatterySaver -> DeviceTier.LOW_END
        cap.isLowRamDevice || cap.totalRamMb <= 3072 -> DeviceTier.LOW_END
        cap.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> DeviceTier.LOW_END
        cap.totalRamMb >= 6144 && cap.nativeLibraryLoaded -> DeviceTier.HIGH_END
        else -> DeviceTier.MID
    }

    fun apply(cap: DeviceCapability, config: ModelStrategyConfig) {
        val tier = tierFor(cap)

        val defaultMode = if (cap.qwenModelPresent) ModelMode.TEXT_QWEN else ModelMode.TEXT_QWEN
        val retryMode = when (tier) {
            DeviceTier.LOW_END, DeviceTier.MID -> ModelMode.TEXT_QWEN
            DeviceTier.HIGH_END -> when {
                cap.mmprojPresent -> ModelMode.VLM_QWEN
                cap.gemmaTextModelPresent -> ModelMode.TEXT_GEMMA
                else -> ModelMode.TEXT_QWEN
            }
            DeviceTier.DEVELOPER -> ModelMode.VLM_QWEN
        }
        val experimentMode = if (cap.gemmaTextModelPresent && tier != DeviceTier.LOW_END)
            ModelMode.TEXT_GEMMA else ModelMode.TEXT_QWEN

        config.setModeFor(ModelRole.DEFAULT, defaultMode)
        config.setModeFor(ModelRole.LOW_CONFIDENCE_RETRY, retryMode)
        config.setModeFor(ModelRole.EXPERIMENT, experimentMode)
        // BENCHMARK role intentionally left alone.
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.runtime.DeviceTierPolicyTest"
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/runtime/DeviceTier.kt \
        app/src/main/kotlin/com/example/coupontracker/runtime/DeviceTierPolicy.kt \
        app/src/test/java/com/example/coupontracker/runtime/DeviceTierPolicyTest.kt
git commit -m "feat(runtime): add DeviceTier and DeviceTierPolicy"
```

---

## Task 7: Apply tier at app startup

**Files:** Modify `app/src/main/kotlin/com/example/coupontracker/CouponTrackerApplication.kt`.

- [ ] **Step 1: Inject the probe + config**

Add constructor-injected (via `@Inject lateinit var`) `DeviceCapabilityProbe` and `ModelStrategyConfig`.

In `onCreate()`, after the existing initialisation, add:

```kotlin
        val capability = deviceCapabilityProbe.probe()
        DeviceTierPolicy.apply(capability, modelStrategyConfig)
        Log.i(TAG, "Device tier applied: ${DeviceTierPolicy.tierFor(capability)} capability=$capability")
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/CouponTrackerApplication.kt
git commit -m "feat(app): apply device tier on startup"
```

---

## Task 8: Documentation + decision log

**Files:** Create `docs/extraction/device_tiers.md`.

- [ ] **Step 1: Write the doc**

```markdown
# Device tiers

## Tier definitions

| tier | gate | DEFAULT mode | LOW_CONFIDENCE_RETRY mode |
|---|---|---|---|
| LOW_END | RAM ≤ 3GB, `lowRamDevice`, battery saver, thermal ≥ SEVERE | TEXT_QWEN | TEXT_QWEN (no retry) |
| MID | fallthrough | TEXT_QWEN | TEXT_QWEN (no retry) |
| HIGH_END | RAM ≥ 6GB & native lib loaded | TEXT_QWEN | VLM_QWEN if mmproj else TEXT_GEMMA if present else TEXT_QWEN |
| DEVELOPER | manual only (debug builds) | anything | VLM_QWEN |

## Runtime capability probed

- `totalRamMb`, `availableRamMb` — from `ActivityManager.getMemoryInfo`
- `isLowRamDevice` — from `ActivityManager`
- `isBatterySaver` — from `PowerManager`
- `thermalStatus` — from `PowerManager.currentThermalStatus` (API 29+)
- `qwenModelPresent`, `gemmaTextModelPresent`, `mmprojPresent` — file presence in `filesDir`
- `nativeLibraryLoaded` — `System.loadLibrary("mlc_llm_jni")` succeeded

## Promotion / demotion

The policy re-runs on every app start. Users can switch battery saver on
mid-session — the next session detects it and drops to LOW_END. Changing
device tier invalidates the cached `ModelSelector` adapter choice only
indirectly (the selector reads `ModelStrategyConfig` on every `select(...)`
call, so no cache to invalidate).

## Testing a specific tier

To simulate LOW_END on a HIGH_END device, set battery saver mode manually
in Android settings and restart the app. The startup log line should read
`Device tier applied: LOW_END`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/extraction/device_tiers.md
git commit -m "docs(extraction): document device-tier policy"
```

---

## Self-Review

1. **Spec coverage:** Schema v2 additive keys → Tasks 1–2. Parser + prompt propagation → Task 3. DB migration → Task 4. Device-tier probe → Task 5. Tier policy → Task 6. Startup wiring → Task 7. Documentation → Task 8.
2. **Placeholder scan:** Task 4's `X` placeholder is explicitly labelled "replace with current DB version" — it is not a code placeholder, it is an instruction. Task 3 and Task 5 reference inspecting existing code first; concrete follow-through is specified. No TODOs in committed code.
3. **Type consistency:** `CouponJsonContract`, `CouponJsonContractV2`, `CouponSchemaKeys.V2_OPTIONAL_KEYS`, `DeviceCapability`, `DeviceTier`, `DeviceTierPolicy`, `ModelStrategyConfig`, `ModelMode`, `ModelRole` all consistent across tasks.
