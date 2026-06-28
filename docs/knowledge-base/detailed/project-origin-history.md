# Project Origin And Historical Context

This page preserves early ChatGPT/project discussions. Treat it as product and
decision history, not as the current implementation contract.

Current source code, tests, static memory, and dynamic memory override this page
when they conflict.

## Why This Page Exists

Early project discussions framed CouponTracker as a local Android coupon wallet:

```text
Upload coupon screenshots
extract useful coupon fields
save valid coupons locally
show all coupons or coupons for one app/brand
keep coupons until expiry
```

That product goal is still valid. Some model and architecture details from the
early discussions are now historical because the app has moved toward a
state-aware crop-first Gemma/OCR verification pipeline.

## Original Product Goal

The intended user behavior was:

| User command | Expected result |
| --- | --- |
| `Show` | Show all saved valid coupons |
| `Show {app_name}` | Show coupons for that app or brand |

The core coupon table was described as:

| App Name | Coupon Code | Cashback / Offer | Expiry Date |
| --- | --- | --- | --- |

The durable product direction is:

- offline-first Android app,
- no sign-in required for core extraction,
- local storage for saved coupons,
- expired coupons hidden or clearly marked,
- manual edit path for bad extraction,
- image preview and review state so users can correct uncertain fields.

## Original Field Set

Early discussions converged on useful fields instead of raw OCR dumps:

| Field | Meaning |
| --- | --- |
| Store / Brand | App, merchant, or issuer name |
| Description | What the coupon gives |
| Expiry Date | When the coupon expires |
| Value | Cashback, discount, or offer value |
| Coupon Code | Exact redeem code when present |

Current code additionally persists field states such as `codeState`,
`expiryState`, `layoutState`, and `debugVisionEvidence`.

## Screenshot Dataset Context

The local `Coupons ` folder contains a real screenshot corpus from the early
project. In the current checkout, it contains 41 image files, not 18.

Important dataset patterns:

- multi-card voucher screens,
- top or bottom partial cards,
- repeated coupons across scroll screenshots,
- active and inactive voucher cards,
- black-background wallet/list screens,
- cards with image, store, offer, code, expiry, and CTA text.

Brands and examples mentioned in early discussions included:

- JioHotstar, SonyLIV, Zee5, Spotify Premium, Times Prime,
- PokerBaazi,
- Portronics,
- Skullcandy,
- Mamaearth,
- My11Circle,
- MPL,
- IKEA,
- Uber / Uber One,
- The Man Company,
- Toothsi Aligners,
- Axis Bank Neo Credit Card,
- Flipkart Axis Bank Credit Card,
- Lenskart,
- Beardo.

The lesson still matters: a screenshot is often not one coupon. The extractor
must handle duplicate scroll cards, partial cards, and mixed OCR text.

## Early Technical Diagnosis

The early project correctly identified that coupon extraction is not mainly an
LLM problem. It is:

```text
layout/card detection
+ OCR
+ coupon-card separation
+ field parsing
+ validation
+ dedupe
+ expiry handling
```

Text-only cleanup cannot fix image-level extraction mistakes. If OCR reads the
wrong card, misses a code, or mixes multiple coupons, a text-only model may only
clean the wrong text confidently.

## Qwen Historical Role

The app used Qwen 2.5 1.5B Instruct Q4 as a local text cleanup model.

Historical role:

```text
screenshot -> OCR -> Qwen cleans OCR text
```

Known limitation:

- Qwen is text-only.
- It cannot inspect the screenshot.
- It can hallucinate or rewrite fields when OCR is wrong.

Current direction:

- Qwen is cleanup support, not truth.
- It should not override evidence-backed OCR/rule/vision validation.
- The long-term direction is OCR plus VLM field labeling and deterministic
  validation, with Qwen reduced or removed where it creates ambiguity.

## Vision Model Research History

Early discussions considered:

- Gemma 3n,
- Florence 2 Base,
- lightweight mobile VLMs,
- free vision models,
- whether one model could replace many models.

Historical concerns:

- large model size,
- slow mobile inference,
- battery and thermal cost,
- Android runtime complexity,
- gated downloads or license/authentication requirements,
- Hugging Face 401/403 failures when model access requires license acceptance
  or tokens.

Current correction:

Gemma Vision is already part of the current verification direction. The current
contract is not "VLM later"; it is:

```text
full screenshot -> Gemma layout bounds only
crop active card/modal with padding
crop OCR -> exact text evidence
Gemma crop -> field labels and states
merge + validate -> save or review
```

Gemma may label fields and ownership, but exact coupon codes still require OCR
or visible-text evidence.

## YOLO And Card Detection History

Early plans discussed two-stage YOLO/card detection:

| Stage | Purpose |
| --- | --- |
| Stage 1 | Detect coupon cards or tiles |
| Stage 2 | Detect fields inside coupon cards |

Logs showed cases where detector models were missing, crop detection found zero
coupons, and fallback layout logic had to run.

Current correction:

YOLO is not mandatory as the only solution. A detector can still be useful, but
the current path emphasizes Gemma layout bounds, OCR-targeted fallback crops,
and heuristic segmentation when needed.

## TecMarx Historical Debug Case

An early important case was coupon id 40 / TecMarx.

Observed flow:

```text
image received
OCR-first scan started
crop detector found 0 coupons
fallback layout probe started
OCR extracted TecMarx text
Gemma Vision layout started
VLM layout failed with JSON error
heuristic fallback accepted 1 coupon
OCR extracted coupon from fallback region
coupon saved
```

Saved fields were:

| Field | Value |
| --- | --- |
| Store | TecMarx |
| Coupon Code | `TMPP54KFLPVD` |
| Description | `Get Roar Bluetooth Earbuds @ Rs 299 only on TecMarx website` |

Lesson:

- fallback extraction can work,
- malformed VLM JSON must be safely handled,
- broken parser output should route to review or fallback, not silent bad save.

## Recurring Early Failure Classes

Repeated issues discussed from the start:

| Problem | Meaning |
| --- | --- |
| Store becomes CTA | `REDEEM`, `NOW`, `SAVE`, or similar text picked as brand |
| Expiry missing | Date parser missed visible date or relative expiry |
| Description empty | Offer text was not mapped into description |
| Only first coupon saved | Multi-card detection lost other coupons |
| OCR mixed text | Full-screen OCR combined multiple cards |
| LLM hallucination | Model rewrote or invented fields |
| Timeout fallback | Model unavailable, fallback path used |
| Broken JSON | VLM/LLM output was not parseable |
| Duplicate coupons | Scroll screenshots repeated the same card |

These are still valid regression categories.

## Historical Fix Ideas

Useful historical ideas:

- store stopwords for CTA/action text,
- strict coupon-code regex and exact casing preservation,
- description fallback from offer text,
- card segmentation before field parsing,
- strict JSON schema plus retry/repair/reject handling,
- dedupe by store + code + offer,
- manual review for low confidence,
- hide or mark expired coupons.

Current guardrail:

Prefer evidence, crop ownership, and validation over one-off keyword patches.

## Test Harness Origin

The early discussions correctly called for a screenshot extraction harness:

```text
sample screenshot
expected coupon JSON
run extractor
compare actual vs expected
fail on wrong store/code/offer/expiry/state
```

Minimum corpus categories:

- single full coupon,
- multi-coupon screenshot,
- partial card,
- duplicate scroll screenshot,
- no-code coupon,
- visible code coupon,
- expiry in hours,
- expiry in days,
- no visible expiry,
- malformed VLM JSON,
- low-confidence crop.

This remains a major roadmap item.

## Branch And Commit Reporting Requirement

The project owner asked for clear reporting of:

- branch name,
- commit hash,
- whether changes are local or pushed,
- files changed,
- test status,
- device install/logcat status when relevant.

Do not claim a fix is installed, pushed, or verified on device unless it was
actually checked in the current session.

## Current Contract Overrides

Use these current rules over older historical plans:

- OCR reads exact visible text.
- Gemma Vision handles layout ownership and field labels/states.
- Full-screen Gemma layout must not produce final coupon fields.
- Final field extraction must use active-card/modal crop when available.
- Full-screen OCR must not prove final cropped fields.
- Coupon codes require exact OCR or visible-text evidence.
- `NO_CODE_NEEDED` and `NOT_VISIBLE` are valid states, not failures.
- VLM `expiryState=PRESENT` must produce a real `expiryDate` or require review.
- Validator/scorer decides trusted save vs needs review.

