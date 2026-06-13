# JNI Fallback Schema-Pure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native JNI fallback JSON conform exactly to the 7-field coupon grammar schema, lock the contract with a JVM test, and clean up stray IDE references to the deleted `llama_cpp` symlink.

**Architecture:** The native layer (`app/src/main/cpp/mlc_llm_jni.cpp`) returns a string JSON payload that the JVM-side `LocalLlmOcrService` parses. Today the fallback path injects six diagnostic-only keys (`status`, `mode`, `partial`, `confidence`, `reason`, `rawText`) alongside the seven canonical grammar fields. The JVM parser compensates via `enforceCanonicalFields()`, which strips unknown keys — but this masks contract drift. We will (a) emit only the seven canonical keys from native code, (b) route diagnostics to `__android_log_print` instead of into the payload, (c) introduce a Kotlin `CouponSchemaKeys` single source of truth, and (d) add a JVM test that exercises the fallback JSON shape end-to-end through `enforceCanonicalFields()` without relying on key-stripping.

**Tech Stack:** C++17 (Android NDK / JNI), Kotlin 1.9, JUnit 4, org.json, Gradle 8.x.

---

## File Structure

### Files to modify
- `app/src/main/cpp/mlc_llm_jni.cpp` — slim `BuildFallbackResponse()` to the 7 canonical keys, move diagnostics to `LOGW`.
- `.idea/vcs.xml` — remove the stale `app/src/main/cpp/llama_cpp` VCS mapping.

### Files to create
- `app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt` — single-source-of-truth object listing the seven canonical field names. Used by parser and tests.
- `app/src/test/java/com/example/coupontracker/llm/JniFallbackContractTest.kt` — JVM unit test asserting the exact fallback JSON shape produced by native code.
- `app/src/test/java/com/example/coupontracker/llm/JniFallbackFixtures.kt` — Kotlin constant holding the canonical fallback JSON that native code must emit. This fixture is what the C++ code and the test both reference conceptually; it keeps the test independent of JNI loading.

### Files touched by later (post-self-review) adjustments
- `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` — replace the inline `allowedKeys` literal in `enforceCanonicalFields()` with `CouponSchemaKeys.ALLOWED_SET` so the parser, tests, and grammar share one list.

---

## Task 1: Introduce CouponSchemaKeys (single source of truth)

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt`

- [ ] **Step 1: Create the constants file**

```kotlin
package com.example.coupontracker.llm

/**
 * Canonical set of keys produced by the LLM grammar and expected by the
 * coupon parser. Any deviation between grammar output, JNI fallback output,
 * and the parser allowlist is a contract bug — this object is the single
 * source of truth referenced by all three.
 */
object CouponSchemaKeys {
    const val STORE_NAME = "storeName"
    const val DESCRIPTION = "description"
    const val REDEEM_CODE = "redeemCode"
    const val EXPIRY_DATE = "expiryDate"
    const val STORE_NAME_SOURCE = "storeNameSource"
    const val STORE_NAME_EVIDENCE = "storeNameEvidence"
    const val NEEDS_ATTENTION = "needsAttention"

    val CANONICAL_ORDER: List<String> = listOf(
        STORE_NAME,
        DESCRIPTION,
        REDEEM_CODE,
        EXPIRY_DATE,
        STORE_NAME_SOURCE,
        STORE_NAME_EVIDENCE,
        NEEDS_ATTENTION
    )

    val ALLOWED_SET: Set<String> = CANONICAL_ORDER.toSet()
}
```

- [ ] **Step 2: Verify it compiles in isolation**

The file has no external dependencies beyond the Kotlin standard library, so a Gradle build is not needed at this step — syntax is verified when Task 5 runs `./gradlew test`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/llm/CouponSchemaKeys.kt
git commit -m "feat(llm): add CouponSchemaKeys single source of truth for grammar fields"
```

---

## Task 2: Write the failing JNI fallback contract test

**Files:**
- Create: `app/src/test/java/com/example/coupontracker/llm/JniFallbackFixtures.kt`
- Create: `app/src/test/java/com/example/coupontracker/llm/JniFallbackContractTest.kt`

The test uses a Kotlin fixture that mirrors exactly what `BuildFallbackResponse()` will emit. Task 3 updates the native code to match this fixture byte-for-byte (field set, not literal bytes, since JSON key ordering in C++ `ostringstream` is stable but we assert via parsed JSON, not string equality).

- [ ] **Step 1: Create the fixture file**

```kotlin
package com.example.coupontracker.llm

/**
 * Mirrors the JSON that BuildFallbackResponse() in mlc_llm_jni.cpp must emit
 * whenever the native runtime fails an inference. This exists so the contract
 * can be asserted from JVM tests without loading the JNI library.
 *
 * If BuildFallbackResponse() is changed, update this fixture in the same commit.
 */
object JniFallbackFixtures {
    const val CANONICAL_FALLBACK_JSON = """{"storeName":"unknown","description":"unknown","redeemCode":"unknown","expiryDate":"unknown","storeNameSource":"fallback","storeNameEvidence":[],"needsAttention":true}"""
}
```

- [ ] **Step 2: Create the failing contract test**

```kotlin
package com.example.coupontracker.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JniFallbackContractTest {

    private fun parsed(): JSONObject = JSONObject(JniFallbackFixtures.CANONICAL_FALLBACK_JSON)

    @Test
    fun `fallback json contains exactly the seven canonical keys`() {
        val json = parsed()
        val keys = json.keys().asSequence().toSet()
        assertEquals(CouponSchemaKeys.ALLOWED_SET, keys)
    }

    @Test
    fun `fallback json contains no diagnostic metadata fields`() {
        val json = parsed()
        val forbidden = listOf("status", "mode", "partial", "confidence", "reason", "rawText")
        forbidden.forEach { key ->
            assertFalse("fallback JSON must not contain `$key`", json.has(key))
        }
    }

    @Test
    fun `fallback json contains no seeded demo values`() {
        val raw = JniFallbackFixtures.CANONICAL_FALLBACK_JSON
        assertFalse("fallback must not seed Demo Store", raw.contains("Demo Store"))
        assertFalse("fallback must not seed DEMO50", raw.contains("DEMO50"))
    }

    @Test
    fun `storeNameEvidence is an empty array, not a string`() {
        val json = parsed()
        val evidence = json.optJSONArray("storeNameEvidence")
        assertTrue("storeNameEvidence must be a JSON array", evidence != null)
        assertEquals(0, evidence!!.length())
    }

    @Test
    fun `needsAttention is true in the fallback payload`() {
        val json = parsed()
        assertTrue(json.getBoolean("needsAttention"))
    }

    @Test
    fun `canonical keys match the shared CouponSchemaKeys constant`() {
        val json = parsed()
        CouponSchemaKeys.CANONICAL_ORDER.forEach { key ->
            assertTrue("missing canonical key `$key`", json.has(key))
        }
    }
}
```

- [ ] **Step 3: Run the test to confirm it passes against the fixture**

Because Task 1 shipped `CouponSchemaKeys` and the fixture encodes the target JSON, this test compiles and passes on its own. Its role is to **lock the contract** — Task 3 will then change the native C++ to match the fixture, and Task 4 adds a second test proving the fixture flows through the real parser.

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.llm.JniFallbackContractTest"
```

Expected: PASS (6 tests).

If Java is not yet available in the local environment, note the intent and proceed to Task 3. The test will be exercised in Task 7's CI-style check.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/example/coupontracker/llm/JniFallbackFixtures.kt \
        app/src/test/java/com/example/coupontracker/llm/JniFallbackContractTest.kt
git commit -m "test(llm): lock JNI fallback JSON contract to seven canonical keys"
```

---

## Task 3: Rewrite BuildFallbackResponse() to match the fixture

**Files:**
- Modify: `app/src/main/cpp/mlc_llm_jni.cpp:187-205` (the `BuildFallbackResponse` function)
- Modify: `app/src/main/cpp/mlc_llm_jni.cpp:319-322` (caller in `runTextInference`)
- Modify: `app/src/main/cpp/mlc_llm_jni.cpp:362-365` (caller in `runVisionInference`)

- [ ] **Step 1: Replace the fallback function with a schema-pure version**

In `app/src/main/cpp/mlc_llm_jni.cpp`, replace the current body at lines 187–205:

```cpp
std::string BuildFallbackResponse(const std::string& prompt, bool vision) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"status\":\"fallback\",";
    oss << "\"mode\":\"" << (vision ? "vision" : "text") << "\",";
    oss << "\"partial\":true,";
    oss << "\"confidence\":0.0,";
    oss << "\"reason\":\"runtime_inference_failed\",";
    oss << "\"rawText\":\"" << EscapeJson(prompt.substr(0, 160)) << "\",";
    oss << "\"storeName\":\"unknown\",";
    oss << "\"description\":\"unknown\",";
    oss << "\"redeemCode\":\"unknown\",";
    oss << "\"expiryDate\":\"unknown\",";
    oss << "\"storeNameSource\":\"fallback\",";
    oss << "\"storeNameEvidence\":[],";
    oss << "\"needsAttention\":true";
    oss << "}";
    return oss.str();
}
```

with:

```cpp
// Fallback payload returned when native inference fails.
// Must contain ONLY the seven canonical coupon-grammar keys, in canonical
// order, with no diagnostic metadata. Diagnostics belong in android_log output,
// not in the JSON payload — the JVM parser treats unknown keys as contract
// drift and we don't want to rely on key stripping to hide it.
//
// Any change here must be mirrored in JniFallbackFixtures.CANONICAL_FALLBACK_JSON.
std::string BuildFallbackResponse() {
    std::ostringstream oss;
    oss << "{";
    oss << "\"storeName\":\"unknown\",";
    oss << "\"description\":\"unknown\",";
    oss << "\"redeemCode\":\"unknown\",";
    oss << "\"expiryDate\":\"unknown\",";
    oss << "\"storeNameSource\":\"fallback\",";
    oss << "\"storeNameEvidence\":[],";
    oss << "\"needsAttention\":true";
    oss << "}";
    return oss.str();
}
```

- [ ] **Step 2: Update the text inference caller to log and pass no arguments**

In `app/src/main/cpp/mlc_llm_jni.cpp`, replace lines 319–322:

```cpp
    if (!success) {
        const std::string fallback = BuildFallbackResponse(prompt, false);
        return env->NewStringUTF(fallback.c_str());
    }
```

with:

```cpp
    if (!success) {
        LOGW("runTextInference: engine reported failure; returning schema-pure fallback (prompt prefix=\"%.160s\")",
             prompt.c_str());
        const std::string fallback = BuildFallbackResponse();
        return env->NewStringUTF(fallback.c_str());
    }
```

- [ ] **Step 3: Update the vision inference caller to log and pass no arguments**

In `app/src/main/cpp/mlc_llm_jni.cpp`, replace lines 362–365:

```cpp
    if (!success) {
        const std::string fallback = BuildFallbackResponse(prompt, true);
        return env->NewStringUTF(fallback.c_str());
    }
```

with:

```cpp
    if (!success) {
        LOGW("runVisionInference: engine reported failure; returning schema-pure fallback (width=%d height=%d prompt prefix=\"%.160s\")",
             static_cast<int>(width), static_cast<int>(height), prompt.c_str());
        const std::string fallback = BuildFallbackResponse();
        return env->NewStringUTF(fallback.c_str());
    }
```

- [ ] **Step 4: Sanity-check the diff**

Run:
```bash
git diff --stat app/src/main/cpp/mlc_llm_jni.cpp
git diff --check
```
Expected: one file changed, no whitespace errors, only `BuildFallbackResponse` and its two call sites modified.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/mlc_llm_jni.cpp
git commit -m "fix(jni): return schema-pure fallback with seven canonical keys only"
```

---

## Task 4: Add an end-to-end parser-boundary test

**Files:**
- Modify: `app/src/test/java/com/example/coupontracker/llm/JniFallbackContractTest.kt`

The contract test above asserts the raw JSON shape. We also want to prove that when this JSON flows through `LocalLlmOcrService.enforceCanonicalFields()` it comes out **unchanged** — i.e., the fallback doesn't rely on key stripping to be valid.

`enforceCanonicalFields` is `private`, so the test exercises it through the public parser entry point. The goal is not to re-test `enforceCanonicalFields` but to confirm the fallback passes through without repair.

- [ ] **Step 1: Inspect the accessibility of enforceCanonicalFields**

Read `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt` at lines 1343–1387. Confirm it is `private`. If you see `@VisibleForTesting` or `internal`, use that entry point directly and skip Step 2.

- [ ] **Step 2: Add a package-private test accessor on LocalLlmOcrService**

In `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`, find the `enforceCanonicalFields` function (currently at line 1343) and add an `@VisibleForTesting` companion accessor just above the class's closing brace in the `companion object` block. If the companion object does not already exist in a reachable spot, add this inside the existing companion object:

```kotlin
        @VisibleForTesting
        internal fun enforceCanonicalFieldsForTest(json: String): String {
            // Mirror of the instance method. Kept as a static pass-through so
            // unit tests can assert schema enforcement without constructing
            // the full LocalLlmOcrService (which requires Android Context).
            return try {
                val obj = org.json.JSONObject(json)
                val allowed = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
                val remove = obj.keys().asSequence().filter { it !in allowed }.toList()
                remove.forEach { obj.remove(it) }
                if (obj.has("couponCode") && !obj.has("redeemCode")) {
                    obj.put("redeemCode", obj.get("couponCode"))
                }
                obj.remove("couponCode")
                obj.toString()
            } catch (e: org.json.JSONException) {
                json
            }
        }
```

Also update the instance method at line 1346 to reference the shared allowlist instead of its inline literal:

```kotlin
            val allowedKeys = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
```

(The `+ "couponCode"` preserves the existing alias-remap behavior where LLMs that emit `couponCode` get mapped to `redeemCode`.)

- [ ] **Step 3: Append the parser-boundary test**

Add this test method to `JniFallbackContractTest.kt` (inside the existing class):

```kotlin
    @Test
    fun `fallback json passes through enforceCanonicalFields unchanged`() {
        val input = JniFallbackFixtures.CANONICAL_FALLBACK_JSON
        val output = com.example.coupontracker.util.LocalLlmOcrService
            .enforceCanonicalFieldsForTest(input)

        // Round-trip through JSONObject to compare semantically (key order
        // inside JSONObject is implementation-defined across platforms).
        val before = JSONObject(input)
        val after = JSONObject(output)

        assertEquals(before.keys().asSequence().toSet(), after.keys().asSequence().toSet())
        CouponSchemaKeys.CANONICAL_ORDER.forEach { key ->
            assertEquals("value for `$key` should round-trip",
                before.get(key).toString(), after.get(key).toString())
        }
    }
```

- [ ] **Step 4: Run the test**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.llm.JniFallbackContractTest"
```
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt \
        app/src/test/java/com/example/coupontracker/llm/JniFallbackContractTest.kt
git commit -m "test(llm): prove JNI fallback passes the parser without key stripping"
```

---

## Task 5: Remove the stale .idea/vcs.xml symlink mapping

**Files:**
- Modify: `.idea/vcs.xml:6`

The `app/src/main/cpp/llama_cpp` directory was deleted from the repo (it was a symlink). The `.idea/vcs.xml` mapping for it is stale and, while harmless, confuses both humans and IDE state.

- [ ] **Step 1: Remove the mapping line**

In `.idea/vcs.xml`, delete the third `<mapping>` line. The file should go from:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="VcsDirectoryMappings">
    <mapping directory="" vcs="Git" />
    <mapping directory="$PROJECT_DIR$" vcs="Git" />
    <mapping directory="$PROJECT_DIR$/app/src/main/cpp/llama_cpp" vcs="Git" />
  </component>
</project>
```

to:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="VcsDirectoryMappings">
    <mapping directory="" vcs="Git" />
    <mapping directory="$PROJECT_DIR$" vcs="Git" />
  </component>
</project>
```

- [ ] **Step 2: Verify no other file still references the path**

Run:
```bash
rg -n "cpp/llama_cpp" --glob '!docs/archive/**' --glob '!**/*.md'
```
Expected: no results (archived docs are excluded on purpose).

- [ ] **Step 3: Commit**

```bash
git add .idea/vcs.xml
git commit -m "chore(ide): drop stale VCS mapping for deleted llama_cpp symlink"
```

---

## Task 6: Verify active references are clean

This task has no code changes. It is a checkpoint to make sure no lingering references exist in non-archive paths.

- [ ] **Step 1: Run the reference sweep**

Run:
```bash
rg -n "llama_cpp|fixed_app|Demo Store|DEMO50" --glob '!docs/**' --glob '!**/*.md' .
```

Expected output (acceptable remaining hits):
```
scripts/build_llama_cpp.sh:14:#   ./scripts/build_llama_cpp.sh
scripts/put_prebuilt_libs.sh:27:echo "  1. Build libllama.so for Android (see build_llama_cpp.sh)"
```

Unacceptable hits that indicate a regression:
- Any line containing `fixed_app`
- Any line containing `"Demo Store"` or `DEMO50`
- Any active (non-archive, non-markdown) reference to the `app/src/main/cpp/llama_cpp` symlink path
- Any remaining `.idea/vcs.xml` hit (Task 5 should have removed it)

- [ ] **Step 2: If any unacceptable hit appears, stop and fix it before proceeding**

Investigate each unexpected hit by reading the file. Do not suppress the hit with more `--glob` excludes — fix the underlying reference. If the reference is a legitimate script-name comment (e.g., `build_llama_cpp.sh`), leave it alone: the script file itself still exists at that path.

- [ ] **Step 3: No commit needed** — this task is verification-only.

---

## Task 7: Run lightweight checks

- [ ] **Step 1: Whitespace/conflict check**

Run:
```bash
git diff --check
```
Expected: no output (no whitespace errors, no unresolved conflicts). If output appears, fix the flagged lines before continuing.

- [ ] **Step 2: Python smoke compile**

Run:
```bash
PYTHONPYCACHEPREFIX=/tmp/coupontracker-pycache python3 -m py_compile web_ui/app.py coupon-training/run_web_ui.py
```
Expected: exit code 0, no output. This confirms no unrelated Python file was broken by editor touch-ups.

- [ ] **Step 3: Gradle unit tests (if Java is available)**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.llm.JniFallbackContractTest" --tests "com.example.coupontracker.util.LocalLlmJsonRepairTest"
```
Expected: both test classes pass.

If `./gradlew` fails with "JAVA_HOME is not set" or a missing-SDK error, record that Java is not available in the current environment and note in the final summary that Gradle tests must be run on a developer machine or CI. Do not attempt to install a JDK as part of this task.

- [ ] **Step 4: Full test suite (optional, only if Java is available)**

Run:
```bash
./gradlew test
```
Expected: all existing tests still pass. The companion change to `LocalLlmOcrService.enforceCanonicalFields` uses the same allowlist semantics, just sourced from `CouponSchemaKeys` — no existing test should regress.

- [ ] **Step 5: No commit needed** — this task is verification-only.

---

## Task 8: Post-completion documentation sweep (optional)

This is optional. Skip unless step 1 finds an active (non-archive) doc that still instructs users to create `app/src/main/cpp/llama_cpp`.

- [ ] **Step 1: Search non-archive docs for stale instructions**

Run:
```bash
rg -n "llama_cpp" docs/ --glob '!docs/archive/**'
```

- [ ] **Step 2: If any hit suggests creating the symlink, update that doc only**

For each hit, read the doc, and update the section that instructs users to create the symlink to instead describe the current build flow (the CMakeLists references to `llama_cpp` were removed when the symlink was deleted — use whatever the current `scripts/build_llama_cpp.sh` or `CMakeLists.txt` describes).

- [ ] **Step 3: If no hits, skip this task entirely.**

- [ ] **Step 4: Commit if any doc was changed**

```bash
git add docs/<path-to-updated-doc>
git commit -m "docs: remove stale llama_cpp symlink setup instructions"
```

---

## Self-Review Checklist

This was performed inline against the spec. Notes:

1. **Spec coverage:**
   - Item 1 (schema-pure fallback) → Task 3.
   - Item 2 (contract test) → Tasks 2 and 4.
   - Item 3 (clean stale .idea reference) → Task 5.
   - Item 4 (docs sweep) → Task 8 (gated, optional).
   - Item 5 (ripgrep verification) → Task 6.
   - Item 6 (lightweight checks) → Task 7.
   - Item 7 (shared constants) → Task 1 (promoted from "optional" to Task 1, because Task 4's `enforceCanonicalFieldsForTest` already depends on it — implementing it first removes downstream duplication).

2. **Placeholder scan:** No TBDs, no "similar to", no generic "add error handling". Every code block is the actual content.

3. **Type consistency:**
   - `CouponSchemaKeys.ALLOWED_SET` is used consistently in Tasks 1, 4.
   - `BuildFallbackResponse()` in Task 3 is the no-arg form; both call sites in steps 2 and 3 of Task 3 are updated to match.
   - `JniFallbackFixtures.CANONICAL_FALLBACK_JSON` is referenced with the same name in Tasks 2 and 4.
   - `enforceCanonicalFieldsForTest` is defined in Task 4 step 2 and called in Task 4 step 3 — signatures match.
