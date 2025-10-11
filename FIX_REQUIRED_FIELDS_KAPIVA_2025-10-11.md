# CRITICAL FIX: Required Fields - redeemCode & expiryDate - October 11, 2025

## 🚨 **USER FEEDBACK (KAPIVA Coupon)**

**Screenshot shows:**
```
Code: KAPW1M3LAfAhSe
Expires on 31 May, 2025, 11:59 PM
```

**UI shows:**
- Coupon Code: **NULL** ❌
- Expiry Date: **[EMPTY]** ❌

**User:** "Expiry date is not visible still"

---

## 🔍 **Root Cause Analysis**

### **From Logcat:**

**LLM Response:**
```json
{
  "storeName": "KAPIVA",
  "description": "Get Upto 40% Off* on Kapiva's 'Strength and Stamina Range'",
  "cashback": {"type":"percent","valueNum":-1,"currency":""},
  "offerText": "About Kapiva\nExpires: May,25|31",
  "minOrderAmount": "null"
}
```

**Problems:**
1. ❌ **NO `redeemCode` field!** → UI shows "NULL"
2. ❌ **NO `expiryDate` field!** → UI shows [EMPTY]
3. ❌ **Expiry in wrong field** (`offerText` instead of `expiryDate`)
4. ❌ **Mangled date format** (`"31 May, 2025"` → `"May,25|31"`)
5. ❌ **Garbage after JSON** (Arabic text, HTML) → Grammar enforcement failing

---

### **Why This Happened:**

**Schema Definition:**
```kotlin
// Field 5: Redeem Code
SchemaField(
    name = "redeemCode",
    type = FieldType.StringType,
    required = false,  ← LLM CAN SKIP THIS!
    ...
)

// Field 6: Expiry Date
SchemaField(
    name = "expiryDate",
    type = FieldType.StringType,
    required = false,  ← LLM CAN SKIP THIS!
    ...
)
```

**Because `required = false`, the LLM:**
1. Thought these fields were optional
2. Skipped them entirely
3. Put expiry date in wrong field (`offerText`)
4. Mangled the date format

---

## ⚠️ **Impact on App's Purpose**

### **Without Coupon Code:**
- ❌ User can't copy the code
- ❌ User can't redeem the coupon
- ❌ **Coupon is USELESS!**

### **Without Expiry Date:**
- ❌ No expiry reminders
- ❌ No "Expiring Soon" tab
- ❌ Users miss coupons
- ❌ **App loses its PRIMARY PURPOSE!**

**User's insight:**
> "The main aim of this app is that users shouldn't miss the coupons to use. If we are NOT fetching the expiry date then what is the use of app."

---

## 🛠️ **Fixes Implemented**

### **Fix 1: Make Fields Required in Schema**

**File:** `app/src/main/kotlin/com/example/coupontracker/schema/CouponSchema.kt`

**Before:**
```kotlin
// Field 5: Redeem Code
SchemaField(
    name = "redeemCode",
    type = FieldType.StringType,
    required = false,  ← Optional!
```

**After:**
```kotlin
// Field 5: Redeem Code
SchemaField(
    name = "redeemCode",
    type = FieldType.StringType,
    required = true,  // CRITICAL: Coupon code is the whole point!
```

**Before:**
```kotlin
// Field 6: Expiry Date
SchemaField(
    name = "expiryDate",
    type = FieldType.StringType,
    required = false,  ← Optional!
```

**After:**
```kotlin
// Field 6: Expiry Date
SchemaField(
    name = "expiryDate",
    type = FieldType.StringType,
    required = true,  // 🚨 CRITICAL: Most important field for app reminders!
```

**Result:**
- Schema validator will **REJECT** any JSON missing these fields
- LLM **MUST** include both keys

---

### **Fix 2: Add MANDATORY FIELDS Section to Prompt**

**File:** `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`

**Before:**
```kotlin
You are a JSON extractor. Extract coupon data and output ONLY valid JSON.

CRITICAL RULES:
1. ONLY extract data that EXISTS in the OCR text
...
```

**After:**
```kotlin
You are a JSON extractor. Extract coupon data and output ONLY valid JSON.

🚨 MANDATORY FIELDS (MUST ALWAYS INCLUDE):
1. "redeemCode" - THE coupon code (search for "Code:" in OCR)
2. "expiryDate" - THE expiry date (search for "Expires on" in OCR)
3. "storeName" - Store/brand name
4. "description" - Offer description

⚠️ CRITICAL: All 7 JSON keys MUST be present! Never skip redeemCode or expiryDate!

CRITICAL RULES:
1. ONLY extract data that EXISTS in the OCR text
...
```

**Key Addition:**
- Explicit list of MANDATORY fields at the very top
- Emphasizes that these fields are non-negotiable

---

### **Fix 3: Strengthen redeemCode Instructions**

**Before:**
```kotlin
redeemCode:
- Search for: "Code:", "Coupon:", or standalone alphanumeric codes
- Strip prefixes: "Code: SAVE50" → "SAVE50"
- If NO code in OCR, use null
- DO NOT invent codes
```

**After:**
```kotlin
redeemCode:
🚨 CRITICAL - MUST ALWAYS INCLUDE THIS KEY!
- Search for: "Code:", "Coupon:", or standalone alphanumeric codes
- Strip prefixes: "Code: KAPW1M3LAfAhSe" → "KAPW1M3LAfAhSe"
- Examples: "SAVE50", "BTXS5T13LI9V5", "KAPW1M3LAfAhSe"
- If NO code in OCR, use null (BUT key must be present!)
- DO NOT invent codes
- NEVER output "NULL" as a string - use null instead
```

**Key Improvements:**
- ✅ "MUST ALWAYS INCLUDE THIS KEY!"
- ✅ Actual example from KAPIVA coupon
- ✅ Explicit: "NEVER output 'NULL' as a string"
- ✅ Clarified: Key must be present even if value is null

---

### **Fix 4: Strengthen expiryDate Instructions**

**Before:**
```kotlin
expiryDate:
🚨 CRITICAL - MOST IMPORTANT FOR APP REMINDERS!
⚠️ Extract EXACTLY what you see in OCR. DO NOT change the date!

STEP 1: Find expiry text in OCR
...
STEP 2: Extract ONLY day, month, year (remove time)
- Input: "Expires on 05 May, 2025, 11:59 PM"
- Output: "05 May 2025"
...
```

**After:**
```kotlin
expiryDate:
🚨 CRITICAL - MOST IMPORTANT FOR APP REMINDERS! MUST ALWAYS INCLUDE THIS KEY!
⚠️ Extract EXACTLY what you see in OCR. DO NOT change the date!

STEP 1: Find expiry text in OCR
...
STEP 2: Extract ONLY day, month, year (remove time)
- Input: "Expires on 31 May, 2025, 11:59 PM"
- Output: "31 May 2025"
...
STEP 3: Remove ALL extra text
- ❌ WRONG: "May,25|31" (mangled format!)  ← Exact bug from KAPIVA!
- ❌ WRONG: Put it in "offerText" field
- ✅ CORRECT: "31 May 2025" in "expiryDate" field

🚨 CRITICAL RULES:
1. MUST include "expiryDate" key (even if null)!
2. DO NOT change the date numbers (31 May → May,25|31 is WRONG!)
3. DO NOT put expiry in "offerText" - it goes in "expiryDate"!
...
```

**Key Improvements:**
- ✅ "MUST ALWAYS INCLUDE THIS KEY!"
- ✅ Exact wrong format from bug ("May,25|31") in ❌ WRONG list
- ✅ Explicit: "DO NOT put expiry in 'offerText'"
- ✅ Updated example to match KAPIVA ("31 May, 2025")

---

## 📊 **Before vs After**

### **Before Fix (KAPIVA Coupon):**

**OCR Input:**
```
Code: KAPW1M3LAfAhSe
Expires on 31 May, 2025, 11:59 PM
```

**LLM Output:**
```json
{
  "storeName": "KAPIVA",
  "description": "Get Upto 40% Off...",
  [NO redeemCode!],
  [NO expiryDate!],
  "offerText": "About Kapiva\nExpires: May,25|31"  ← Wrong field + mangled!
}
```

**UI Display:**
- Store: `KAPIVA` ✅
- Description: `Get Upto 40% Off...` ✅
- **Coupon Code: `NULL`** ❌
- **Expiry Date: [EMPTY]** ❌

**Result:**
- ❌ User can't redeem coupon (no code)
- ❌ No reminders (no expiry date)
- ❌ App's primary purpose defeated

---

### **After Fix (Expected):**

**OCR Input:**
```
Code: KAPW1M3LAfAhSe
Expires on 31 May, 2025, 11:59 PM
```

**Expected LLM Output:**
```json
{
  "storeName": "KAPIVA",
  "description": "Get Upto 40% Off on Kapiva's Strength and Stamina Range",
  "redeemCode": "KAPW1M3LAfAhSe",  ← Present!
  "expiryDate": "31 May 2025",  ← Correct field + correct format!
  "cashback": null,
  "offerText": null,
  "minOrderAmount": null
}
```

**UI Display:**
- Store: `KAPIVA` ✅
- Description: `Get Upto 40% Off...` ✅
- **Coupon Code: `KAPW1M3LAfAhSe`** ✅
- **Expiry Date: `31 May 2025`** ✅

**Result:**
- ✅ User can copy and redeem code
- ✅ Reminders enabled (expiry date present)
- ✅ App's primary purpose restored!

---

## 🧪 **Validation**

**Schema Validator Will:**
1. ✅ **REJECT** JSON missing `redeemCode` key
2. ✅ **REJECT** JSON missing `expiryDate` key
3. ✅ **ACCEPT** JSON with `redeemCode: null` (key present, value null)
4. ✅ **ACCEPT** JSON with `expiryDate: null` (key present, value null)

**This Forces LLM To:**
- Always include both keys
- Put expiry in correct field (`expiryDate` not `offerText`)
- Not skip critical fields

---

## ✅ **Summary**

### **Root Cause:**
- Schema marked `redeemCode` and `expiryDate` as `required = false`
- LLM thought they were optional and skipped them

### **Impact:**
- **Without coupon code:** Coupon is useless (can't redeem!)
- **Without expiry date:** No reminders (app's primary purpose defeated!)

### **Fixes:**
1. ✅ Schema: Changed `required = false` → `required = true` for both fields
2. ✅ Prompt: Added "MANDATORY FIELDS" section at top
3. ✅ Prompt: Emphasized "MUST ALWAYS INCLUDE THIS KEY!" for both fields
4. ✅ Prompt: Added exact wrong format from bug to ❌ WRONG examples
5. ✅ Prompt: Clarified field placement ("expiryDate" not "offerText")

### **Files Modified:**
| File | Change | Lines |
|------|--------|-------|
| `CouponSchema.kt` | Made redeemCode and expiryDate required | +2 -2 |
| `LocalLlmOcrService.kt` | Enhanced prompt with MANDATORY FIELDS section | +28 -16 |

**Total:** 2 files changed, 30 insertions(+), 18 deletions(-)

---

## 🎯 **Expected Results (Next Test)**

**Upload KAPIVA coupon again:**

| Field | Before | After |
|-------|--------|-------|
| Store | `KAPIVA` ✅ | `KAPIVA` ✅ |
| Description | `Get Upto 40% Off...` ✅ | `Get Upto 40% Off...` ✅ |
| **Code** | **`NULL`** ❌ | **`KAPW1M3LAfAhSe`** ✅ |
| **Expiry** | **[EMPTY]** ❌ | **`31 May 2025`** ✅ |

**With both fields present:**
- ✅ User can copy and redeem code
- ✅ Reminders work (expiry date present)
- ✅ "Expiring Soon" tab works
- ✅ App fulfills its PRIMARY PURPOSE!

---

## 📁 **Commit Details**

**Commit:** `37ea76c95`  
**Branch:** `feature/phase1-mvp-core`  
**Build Status:** ✅ Compiled successfully (42s)  
**Pushed:** ✅

---

## 🚀 **Ready to Test!**

**Install the new APK and verify KAPIVA coupon:**
1. ✅ **Coupon Code: `KAPW1M3LAfAhSe`** (not "NULL")
2. ✅ **Expiry Date: `31 May 2025`** (not [EMPTY])
3. ✅ **Reminders enabled** → App's primary purpose restored!

**All fixes committed and pushed!** Test the KAPIVA coupon again. 🎯

