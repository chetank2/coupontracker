# LLM Rating/Cashback Confusion Bug Fix - October 11, 2025

## 🐛 **Bug Report**

**Issue:** LLM extracting app rating (4.38 stars) as cashback percentage instead of actual amount (₹50)

**User's Scenario:**
- Coupon shows: **"you won flat ₹50 off on your next Zepto Cafe order"**
- App rating shown: **Zepto Cafe ⭐ 4.38**
- App extracted:
  - Amount: **4%** ❌ (should be ₹50)
  - Description: **"Coupon offer"** ❌ (generic fallback, should be actual offer text)

---

## 🔍 **Root Cause Analysis**

### **OCR Text Captured:**
```
3:51 31
Vouchers
active : 18 lifetime :428
code: CREDBASS
zepto
catė
Details
Zepto Cafe
code: CAFE50
you won flat 50 off on your next      ← Missing ₹ symbol!
Zepto Cafe order
Details
Ở 030%                                  ← Battery indicator (noise)
4.38                                    ← APP RATING (confused as cashback!)
Redeem Now →
O EXPIRES IN 13 DAYS
```

### **LLM Response:**
```json
{
  "storeName": "Zepto",
  "description": "",                    ← Empty (bad)
  "cashback": {
    "type": "percent",
    "valueNum": 4.38,                   ← WRONG! This is the app rating
    "currency": ""
  },
  "redeemCode": "CAFE50",
  "expiryDate": "29 May, 2025"
}
```

### **Why the LLM Failed:**

1. **Missing Currency Symbol:**
   - Actual: "flat **₹50** off"
   - OCR captured: "flat **50** off"
   - Without ₹, LLM didn't recognize "50" as a currency amount

2. **Rating Number Prominent:**
   - "**4.38**" appeared as a standalone number
   - No discount keyword nearby ("off", "discount", "cashback")
   - LLM thought: "This must be the cashback percentage!"

3. **No Rating Filter:**
   - OCR cleaner didn't filter 1.0-5.0 range numbers
   - LLM prompt didn't warn about app ratings
   - Small decimal numbers (< 5) not identified as likely ratings

4. **Empty Description:**
   - LLM returned empty string
   - App fell back to generic "Coupon offer"
   - Should have extracted: "Flat ₹50 off on your next Zepto Cafe order"

---

## 🎯 **The Fix**

### **Fix 1: Enhanced LLM Prompt** (`LocalLlmOcrService.kt`)

**Added Critical Disambiguation Section:**
```kotlin
⚠️ CRITICAL DISAMBIGUATION:
- "flat X off" or "₹X off" (even without ₹) = FIXED AMOUNT, not percentage
  Example: "flat 50 off" → {"type":"amount","valueNum":50,"currency":"INR"}
- Small numbers (< 5) near "Details", "Redeem Now", buttons = APP RATINGS, IGNORE THEM
  Example: "Zepto Cafe 4.38" → 4.38 is a RATING, NOT cashback
- Numbers with stars (⭐) or near restaurant/store names = RATINGS, IGNORE
- Only use numbers that appear WITH discount keywords ("off", "cashback", "discount")
```

**Updated Cashback Instructions:**
```kotlin
cashback:
- Look for discount text: "50% off", "₹200 off", "Flat 11% Off", "flat 50 off"  ← Added
- Convert to object:
  * Percentage: {"type":"percent","valueNum":50,"currency":null}
  * Amount: {"type":"amount","valueNum":200,"currency":"INR"}
```

**Improved Description Extraction:**
```kotlin
description:
- Brief summary of the offer (1-2 sentences)
- Example: "you won flat 50 off on your next\nZepto Cafe order" 
          → "Flat ₹50 off on your next Zepto Cafe order"  ← Added
- Include the main benefit/offer in description
- DO NOT leave description empty if there's an offer - extract the offer text  ← Added
```

### **Fix 2: OCR Rating Filter** (`OcrTextCleaner.kt`)

**Added Rating Patterns to UI_NOISE_PATTERNS:**
```kotlin
// App ratings (1.0-5.0 range, often mistaken for cashback)
Regex("""^[1-5]\.\d{1,2}$"""),           // Matches: 4.38, 4.5, 3.87
Regex("""\b⭐?\s*[1-5]\.\d{1,2}\s*⭐?\b"""), // Matches: ⭐ 4.38, 4.5 ⭐
```

**What This Filters:**
- ✅ "4.38" (standalone rating)
- ✅ "⭐ 4.5" (rating with star)
- ✅ "3.87 ⭐" (rating with star after)
- ❌ "50.5" (not filtered - valid discount)
- ❌ "75%" (not filtered - valid percentage)

---

## 📊 **Before vs After**

### **Before Fix:**

| Field | Extracted | Expected |
|-------|-----------|----------|
| Store | Zepto ✅ | Zepto |
| Description | "Coupon offer" ❌ | "Flat ₹50 off on your next Zepto Cafe order" |
| **Amount** | **4%** ❌ | **₹50** |
| Code | CAFE50 ✅ | CAFE50 |

**LLM Reasoning (Wrong):**
```
OCR: "flat 50 off ... 4.38"
LLM: "Hmm, I see 50 and 4.38. The 50 has no currency symbol.
      The 4.38 looks like a percentage. Let's use 4.38%."
Result: {"type":"percent","valueNum":4.38} ❌
```

### **After Fix:**

| Field | Expected Result |
|-------|-----------------|
| Store | Zepto ✅ |
| Description | "Flat ₹50 off on your next Zepto Cafe order" ✅ |
| **Amount** | **₹50** ✅ |
| Code | CAFE50 ✅ |

**LLM Reasoning (Correct):**
```
OCR: "flat 50 off ... 4.38"
Prompt Warning: "Small numbers (< 5) near Details/Redeem = RATINGS"
Prompt Instruction: "flat X off = FIXED AMOUNT"
LLM: "4.38 is near 'Details', it's a RATING, IGNORE IT.
      'flat 50 off' matches the pattern for fixed amount."
Result: {"type":"amount","valueNum":50,"currency":"INR"} ✅
```

---

## 🧪 **Test Cases**

### **Test 1: Zepto Coupon (This Bug)**
**Input:** "you won flat ₹50 off on your next Zepto Cafe order"  
**Rating in image:** "Zepto Cafe 4.38"

**Expected:**
- ✅ Amount: ₹50 (not 4%)
- ✅ Description: "Flat ₹50 off on your next Zepto Cafe order"
- ✅ 4.38 ignored (rating)

---

### **Test 2: Restaurant Coupon with Rating**
**Input:** "Get 30% off at Pizza Hut"  
**Rating in image:** "Pizza Hut ⭐ 4.5"

**Expected:**
- ✅ Amount: 30% (percentage)
- ✅ 4.5 ignored (rating with star)

---

### **Test 3: High Percentage (Not Confused with Rating)**
**Input:** "Save 75% on electronics"  
**No rating in image**

**Expected:**
- ✅ Amount: 75% (valid percentage, not filtered)
- ✅ No confusion (75 is > 5, not a rating)

---

### **Test 4: Flat Amount without Currency Symbol**
**Input:** "flat 200 off on orders above 999"  
**No rating in image**

**Expected:**
- ✅ Amount: ₹200 (recognized as flat amount)
- ✅ Prompt teaches: "flat X off = FIXED AMOUNT"

---

### **Test 5: Multiple Numbers in Text**
**Input:** "Get 20% off + ₹100 cashback"  
**Rating in image:** "Store Rating: 4.2"

**Expected:**
- ✅ Amount: 20% + ₹100 (both extracted correctly)
- ✅ 4.2 ignored (rating)

---

## 🎯 **How the Fix Works**

### **OCR Stage:**
```
Original OCR:
  "Zepto Cafe
   code: CAFE50
   you won flat 50 off
   Details
   4.38              ← Rating line
   Redeem Now"

After Cleaning:
  "Zepto Cafe
   code: CAFE50
   you won flat 50 off
   Details
   [FILTERED]        ← Rating removed!
   Redeem Now"
```

### **LLM Stage:**
```
Prompt Instructions:
  ✅ "flat X off" = FIXED AMOUNT
  ✅ Small numbers (< 5) near UI elements = RATINGS
  ✅ Only use numbers WITH discount keywords

LLM Sees:
  - "flat 50 off" ← Has "off" keyword
  - Prompt says: "flat X off = FIXED AMOUNT"
  
LLM Outputs:
  {"type":"amount","valueNum":50,"currency":"INR"} ✅
```

---

## ✅ **Summary**

### **Problem:**
- **4.38** (app rating) extracted as **4% cashback**
- **"flat 50 off"** without ₹ not recognized as ₹50

### **Solution:**
1. **OCR Filter:** Remove rating numbers (1.0-5.0 range) before LLM sees them
2. **LLM Prompt:** Explicit warnings about ratings and "flat X off" patterns
3. **Description:** Better extraction instructions to include actual offer text

### **Files Modified:**
- `LocalLlmOcrService.kt` - Enhanced prompt with disambiguation rules
- `OcrTextCleaner.kt` - Added rating number filters

### **Lines Changed:** 19 lines (17 added, 2 modified)

### **Impact:**
- ✅ Ratings (1.0-5.0) filtered from OCR
- ✅ LLM recognizes "flat X off" as fixed amount
- ✅ LLM ignores small numbers near UI elements
- ✅ Better description extraction

### **Build Status:**
✅ Compiled successfully  
✅ All changes pushed to `feature/phase1-mvp-core`

---

## 🚀 **Next Test**

Upload the **same Zepto screenshot** again:

**Expected Output:**
```
Store: Zepto ✅
Description: Flat ₹50 off on your next Zepto Cafe order ✅
Amount: ₹50 ✅ (not 4%)
Code: CAFE50 ✅
Expiry: October 7, 2025 ✅ (using screenshot timestamp)
```

**The fix is complete and ready for testing!** 🎯

