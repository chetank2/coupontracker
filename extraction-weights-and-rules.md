# Extraction Weights and Rules

## 1. Overview & File Purpose
This document is the **source of truth** for CouponTracker's on-device extraction pipeline configuration. It covers the complete flow from regional detection through OCR/LLM processing, fusion, validation, scoring, and reminder scheduling. Engineering teams should treat the defaults herein as implementation-ready, while QA can map acceptance tests to validation suites.

---

## 2. Global Toggles & Strategy Gating

| Flag / Setting | Default | Range | Rationale | Tuning Notes | Related Tests |
| -------------- | ------- | ----- | --------- | ------------ | ------------- |
| `strategy.default` | `OCR_FIRST` | {`LEGACY`,`OCR_FIRST`,`LLM_FIRST`,`HYBRID`} | Start with deterministic OCR to minimize cold-start issues while LLM assets load. | Switch to `LLM_FIRST` only after validating LLM performance parity across golden set. | Strategy downgrade integration tests; log inspection for startup sessions. |
| `strategy.advanced_strategies_enabled` | `true` | Boolean | Enables hybrid/multi-pass strategies for premium builds. | Disable only for minimal builds with tight memory constraints. | Regression suite on low-end device SKU. |
| `runtime_guards.require_mlc_files` | `true` | Boolean | Prevents LLM invocation without mandatory MLC artifacts. | Keep `false` only in unit tests that mock LLM. | File guard tests in CI. |
| `runtime_guards.files` | `[gguf_path, mlc_chat_config, tokenizer_json, projector_optional]` | Superset of required files | Guarantees completeness of runtime assets. | Expand list when adding new optional adapters. | Missing-file downgrade tests. |
| `downgrade_behavior.if_llm_unavailable` | `OCR_FIRST` | {`OCR_FIRST`,`LEGACY`} | Ensures graceful fallback when LLM not ready. | If OCR degraded, consider `LEGACY` with old fusion tables. | LLM-offline smoke suite. |
| `downgrade_behavior.log_downgrade_reason` | `true` | Boolean | QA needs explicit logging for guard-triggered downgrades. | Keep on for all QA/dev builds. | Log assertions during downgrade scenarios. |

### Configuration Snippet
```yaml
strategy:
  default: OCR_FIRST
  advanced_strategies_enabled: true
runtime_guards:
  require_mlc_files: true
  files: [gguf_path, mlc_chat_config, tokenizer_json, projector_optional]
downgrade_behavior:
  if_llm_unavailable: OCR_FIRST
  log_downgrade_reason: true
```

**Acceptance Tests**
- Logs must say `MLCEngineReal` when LLM runs; never claim LLM ran if guards fail.
- When files missing → strategy auto-downgrades to `OCR_FIRST` with reason.

---

## 3. Per-Field Fusion & Confidence Model

### 3.1 Sources & Base Weights

| Source | Default Weight | Range | Rationale | Tuning Notes | Related Tests |
| ------ | -------------- | ----- | --------- | ------------ | ------------- |
| LLM structured JSON | 0.65 | 0.10–1.00 | Balanced to reward LLM precision without overpowering OCR. | Increase toward 0.75 if LLM accuracy ≥ 97% on golden set. | LLM regression suite; schema validation tests. |
| OCR + learned pattern | 0.55 | 0.10–1.00 | High confidence when learned pattern matches confirm. | Adjust ±0.05 based on new OCR training runs. | OCR learner A/B tests. |
| OCR + regex | 0.45 | 0.10–1.00 | Deterministic but brittle across fonts. | Reduce when false positives observed; prefer pattern model upgrades. | Regex-only replay tests. |
| Heuristic fallback | 0.30 | 0.10–1.00 | Provides safety net while remaining lower priority. | Never exceed LLM/OCR weights to avoid overfitting heuristics. | Heuristic-only smoke suite. |
| User manual edit | 1.00 | Fixed | Manual edits are ground truth. | Non-tunable. | Manual override validation tests. |

### 3.2 Candidate Score Formula

```
candidate_score = base_weight * quality_factor * provenance_multiplier
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| `quality_factor` | 0.85 (per candidate) | 0.60–1.00 | Models source-specific confidence (OCR conf, LLM log-probs). | Use percentile calibration from latest OCR/LLM evaluations. | Per-source calibration tests. |
| `provenance_multiplier` | 1.15 (agreement) / 0.85 (weak context) | 0.75–1.25 | Boosts multi-signal agreement; penalizes isolated hints. | Expand to 1.20 if tri-source agreement >98%. | Multi-signal fusion tests. |

### 3.3 Field Confidence Merge

```
field_confidence = 1 - Π(1 - candidate_score_i)
```

| Metric | Default | Range | Rationale | Tuning Notes | Related Tests |
| ------ | ------- | ----- | --------- | ------------ | ------------- |
| `field_confidence` saturation threshold | 0.90 (soft cap for UI highlight) | 0.80–0.95 | Diminishing returns to avoid overconfidence. | Align with QA thresholds before UI release. | UI highlight regression. |

### 3.4 Record Confidence & Attention Rules

```
record_confidence = mean(present_field_confidences)
needs_attention = (any required_field missing) OR (record_confidence < 0.5)
required_fields = [storeName, redeemCode OR cashback/discount OR description]
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| `record_confidence` attention cutoff | 0.50 | 0.40–0.60 | Balance between false alarms and missed issues. | Raise to 0.55 if QA volume manageable. | End-to-end attention flags suite. |

**Acceptance Tests**
- Manual edits set `field_confidence = 1.0` and block overwrite.
- Two medium candidates agreeing > one strong conflicting candidate.

---

## 4. Store/App Name Resolution

### 4.1 Dictionaries & Aliases
```yaml
brand_dictionary:
  myntra: [myntra]
  mamaearth: [mamaearth]
  boat: [boAt, boat, bo at]
  ixigo: [ixigo]
  abhibus: [abhibus, abhi bus]
  newme: [newme, new me]
  aha: [aha, aha ott, aha telugu]
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Alias match window | Exact match on normalized tokens | Extendable | Covers known brand variations. | Update quarterly with brand additions. | Alias resolution test suite. |
| Unknown fallback label | `Unknown` | Any string | Signals QA to review unresolved brands. | Keep consistent for analytics dashboards. | Unknown brand audit tests. |

### 4.2 Stopwords (Hard Block)
```
NOW, SAVE, DISCOUNT, OFFER, OFFERS, CODE, APPLY, REDEEM, BUY, SHOP, TAP,
CLICK, LOGIN, SIGNUP, DEAL, SALE, GET, CLAIM, GRAB
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Stopword list size | 18 terms | Extendable | Prevents CTA terms from becoming store names. | Add more CTA phrases as observed in QA. | Stopword leak tests. |

### 4.3 Heuristic Context Rules
- If no brand match and LLM/OCR fail, scan **±8 tokens** around anchors: `use`, `code`, `on`, `at`, `from`, `by`.
- Prefer **ProperCase token** not in stopwords/dictionary of CTAs.
- If still unknown → `storeName = "Unknown"` and `needsAttention = true`.

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Context window size | 8 tokens | 6–12 | Keeps heuristic targeted without noise. | Increase for long-form descriptions. | Context extraction tests. |

**Acceptance Tests**
- “Get … REDEEM NOW” never yields `Redeem` as store.
- “₹200 off at KiranaMart” → KiranaMart detected.

---

## 5. Coupon Code Detection

### 5.1 Regex & Filters
```
Base regex: \b[A-Z0-9][A-Z0-9-]{3,}\b
Reject if:
  - Looks like date (dd-mm, yyyy, etc.)
  - Looks like phone (10 digits) or order id prefixes (#, ORD, OID with digits)
  - Entropy < 2.2 bits/char (e.g., AAAA-1111)
Normalize: uppercase, collapse multiple dashes
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Minimum length | 4 characters | 4–12 | Avoids capturing short noise tokens. | Increase to 5 if noise persists. | Regex precision tests. |
| Entropy cutoff | 2.2 bits/char | 1.8–2.5 | Filters repeating/obvious dummy codes. | Recalibrate after entropy histogram review. | Entropy filter tests. |

### 5.2 Context Boosters
- Phrases: `use`, `apply`, `coupon`, `code`, `redeem code`.
- Boost candidate score by multiplier `1.10` when booster present.

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Context multiplier | 1.10 | 1.05–1.25 | Rewards contextual hints without dominating. | Raise slightly if OCR noise high. | Contextual scoring tests. |

**Acceptance Tests**
- `MYNTRA20` accepted; `31-12-2025` rejected; `ORD-88231` rejected.

---

## 6. Expiry Date Parsing (IST-first)

### 6.1 Supported Formats
- `dd MMM yyyy` (e.g., 31 Dec 2025)
- `dd/MM/yy`
- `dd-MM-yyyy`
- `Valid till …`
- `Expires …`
- Optional time defaults to `23:59 IST` if missing.

### 6.2 Parser Priorities
1. Deterministic patterns
2. Natural language fallback with reason tag `heuristic_date`

### 6.3 Timezone Handling
```
display_tz: Asia/Kolkata
store_utc: true
assume_time_if_missing: 23:59 IST
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Default expiry time | 23:59 IST | 21:00–23:59 IST | Ensures end-of-day expiry without undercounting validity. | Align with partner SLAs if earlier cutoffs required. | Timezone conversion tests. |
| Fallback parser confidence | 0.60 | 0.50–0.70 | Lower confidence due to heuristic parsing. | Adjust if NLU improvements roll out. | Heuristic parser QA. |

**Acceptance Tests**
- “Valid till 31-05-2025, 11:59 PM” → correct UTC.
- No date → `expiry = null`; `status = "No Expiry Shown"`.

---

## 7. Cashback / Discount Normalization

### 7.1 Types & Canonical Form

| Pattern Example | Type | Canonical | Rationale | Tuning Notes | Related Tests |
| --------------- | ---- | --------- | --------- | ------------ | ------------- |
| Flat 20% OFF | PERCENTAGE | value=20, qualifier=FLAT | Clear percentage with flat qualifier. | Ensure uppercase qualifiers for analytics. | Percentage parsing tests. |
| Up to 60% OFF | PERCENTAGE | value=60, qualifier=UP_TO | Captures upper-bound deals. | Validate that UI displays "Up to" label. | Upper-bound normalization tests. |
| ₹250 off | AMOUNT | value=250, currency=₹ | Numeric currency aware parsing. | Support multi-currency by detecting symbol. | Currency parsing tests. |
| 100% cashback | CASHBACK | value=100 | Distinguish cashback semantics. | Ensure cashback flagged separately. | Cashback classification tests. |
| ₹200 + ₹50 cashback | COMPOUND | parts=[{₹200 off},{₹50 cashback}] | Supports stacked benefits. | Keep order as encountered. | Compound parsing QA. |

### 7.2 Detection Parameters

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Currency symbols | [₹, $, €, £] | Extendable | Cover primary partner geos. | Add SGD, AED when expanding region. | Currency detection tests. |
| Compound split threshold | 2 benefits | 2–4 | Limit complexity for UI readability. | Increase only when UI redesign ready. | Compound offer tests. |
| Qualifier extraction list | [`Flat`,`Up to`,`Min purchase`,`With card`,`App only`] | Extendable | Standardizes metadata for filters. | Sync with analytics naming. | Qualifier extraction regression. |

**Acceptance Tests**
- “₹200 + ₹50 cashback” → two parts; displayed cleanly.
- USD/EUR/GBP symbols preserved; not coerced to ₹.

---

## 8. Description Selection

- Choose first meaningful sentence with offer semantics.
- Drop T&C and boilerplate using regex cues (e.g., `Terms`, `Conditions`, `T&C`, `*` bullet leads).
- Fallback only if all else fails; tag as `heuristic_description`.

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Boilerplate regex list size | 10 patterns | Extendable | Keeps descriptions user-friendly. | Review quarterly with QA feedback. | Description cleanliness tests. |
| Sentence length bounds | 6–180 chars | 5–200 | Avoids truncation or verbose paragraphs. | Adjust for localized languages if needed. | Sentence selection QA. |

**Acceptance Tests**
- Never shows “Coupon offer” as default filler.
- Meaningful, concise one-liner chosen.

---

## 9. Heuristic Fallback Layer (Participation Rules)

### 9.1 Execution Criteria
- Runs only if field remains blank after LLM + pattern + OCR passes.
- Tags outputs as `heuristic_*`.

### 9.2 Helper Methods & Parameters

| Helper | Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| ------ | --------- | ------- | ----- | --------- | ------------ | ------------- |
| `detectCompoundAmount` | Min token confidence | 0.70 | 0.60–0.80 | Ensures reliable token recognition. | Raise if OCR noise high. | Compound amount heuristic tests. |
| | Currency map | {₹:`INR`, $:`USD`, €:`EUR`, £:`GBP`} | Extendable | Maintains currency fidelity. | Add more as we expand geos. | Currency heuristic QA. |
| `detectCouponCode` | Context window | 10 tokens | 8–14 | Wider window improves context capture. | Align with Section 5 context multiplier. | Code heuristic tests. |
| `extractStoreFromContext` | Stopword list | Section 4 stopwords | Extendable | Prevents CTA misclassification. | Sync with Section 4 updates. | Store heuristic tests. |
| `extractMeaningfulSnippet` | Boilerplate filters | Section 8 regex list | Extendable | Reuses description sanitation. | Add localized patterns. | Snippet heuristic QA. |

**Acceptance Tests**
- Heuristic never overwrites a higher-confidence field.
- Logs show which heuristic filled which field.

---

## 10. De-dupe & Upsert

```
entity_key = (normalized_storeName, normalized_redeemCode)
on conflict: update timestamps + merge fields if new_confidence > old_confidence
manual_edits_protected: true
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Normalization | lowercase, trim, collapse spaces | Extendable | Ensures consistent dedupe keys. | Add accent folding when entering new locales. | Dedupe regression tests. |
| Confidence merge rule | Replace if new > old | Binary decision | Prevents regression from stale data. | Consider hysteresis if flapping observed. | Upsert smoke tests. |

**Acceptance Tests**
- Re-ingest same coupon updates single record.
- Manual store/code edits never overwritten by re-extract.

---

## 11. Status, Badges, and “Show” Contract

### 11.1 Status Values
- `Active`
- `ExpiringSoon(<72h)`
- `Expired`
- `NeedsAttention`
- `NoExpiryShown`

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| ExpiringSoon threshold | 72 hours | 48–96 | Aligns with marketing reminders and user expectations. | Adjust for partner SLA changes. | Status calculation tests. |

### 11.2 Table Contract
- Columns: **App Name | Coupon Code | Cashback/Discount | Expiry Date**
- Sort: Expiry ascending (nulls last), then App ascending.

**Acceptance Tests**
- `Show` → all active by default; expired greyed/hidden toggle.
- `Show {app}` → case-insensitive filter, alias supported.

---

## 12. Reminders (IST)

```yaml
reminders:
  schedule:
    lead_times_hours: [168, 24]
    run_at_local_hour: 9
  assume_time_if_missing: "23:59"
  rescan_daily_ist: true
```

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Lead times | [168, 24] hours | 24–240 | Provide 7-day and 24-hour nudges. | Add 3-hour ping for flash sales if UX approves. | Reminder timing tests. |
| Run hour | 09:00 IST | 07:00–11:00 | Matches user engagement window. | Shift for A/B tests on open rates. | Notification scheduling QA. |
| Daily rescan | true | Boolean | Keeps reminders in sync with new data. | Disable only for lab builds. | Daily rescan integration. |

**Acceptance Tests**
- Voucher with 10 days → 7d + 24h notifications; with 6 days → only 24h.

---

## 13. Telemetry & Logs (Dev Builds)

- Persist/Process/Classify lifecycle events.
- Strategy chosen & downgrade reason.
- Per-field candidate list + chosen source + confidence.
- LLM warmup/inference success/failure (explicit missing file names).

| Parameter | Default | Range | Rationale | Tuning Notes | Related Tests |
| --------- | ------- | ----- | --------- | ------------ | ------------- |
| Log retention | 7 days | 3–14 | Fits QA triage cycles while managing storage. | Increase for major release validation. | Telemetry retention tests. |
| Telemetry verbosity | `debug` in dev builds | {`info`,`debug`} | Need fine detail for QA; restrict in prod. | Drop to `info` in beta once stable. | Log format regression. |

**Acceptance Tests**
- Golden dataset replay yields deterministic logs per image.

---

## 14. Config Examples (Copy-Paste)

### 14.1 YAML
```yaml
weights:
  llm_json: 0.65
  ocr_learned: 0.55
  ocr_regex: 0.45
  heuristic: 0.30
  manual_edit: 1.00
thresholds:
  record_attention_cutoff: 0.50
  code_entropy_min: 2.2
  percent_max_value: 95
ist:
  display_tz: "Asia/Kolkata"
  default_expiry_time: "23:59"
fallbacks:
  enable_heuristics: true
stopwords:
  store_fallback: ["NOW","SAVE","DISCOUNT","OFFER","OFFERS","CODE","APPLY","REDEEM","BUY","SHOP","TAP","CLICK","LOGIN","SIGNUP","DEAL","SALE","GET","CLAIM","GRAB"]
regex:
  coupon_code: "\\b[A-Z0-9][A-Z0-9-]{3,}\\b"
```

### 14.2 JSON
```json
{
  "weights": {
    "llm_json": 0.65,
    "ocr_learned": 0.55,
    "ocr_regex": 0.45,
    "heuristic": 0.30,
    "manual_edit": 1.0
  },
  "thresholds": {
    "record_attention_cutoff": 0.5,
    "code_entropy_min": 2.2,
    "percent_max_value": 95
  },
  "ist": {
    "display_tz": "Asia/Kolkata",
    "default_expiry_time": "23:59"
  },
  "fallbacks": { "enable_heuristics": true },
  "stopwords": {
    "store_fallback": ["NOW","SAVE","DISCOUNT","OFFER","OFFERS","CODE","APPLY","REDEEM","BUY","SHOP","TAP","CLICK","LOGIN","SIGNUP","DEAL","SALE","GET","CLAIM","GRAB"]
  },
  "regex": {
    "coupon_code": "\\b[A-Z0-9][A-Z0-9-]{3,}\\b"
  }
}
```

---

## 15. Golden Dataset Spec (for QA)

### 15.1 Sample Entry
```json
{
  "image_id": "aha_20250531.png",
  "expect": {
    "storeName": "aha",
    "redeemCode": "ahappe20",
    "discount": {"type":"PERCENTAGE","value":20,"qualifier":"FLAT"},
    "expiryDateLocal": "2025-05-31T23:59:00+05:30",
    "status": "Active",
    "confidence_min": 0.7
  }
}
```

### 15.2 Batch Acceptance
- ≥ 95% fields correct across the suite.
- No store fallback matches stopwords.
- All expiries normalized to UTC internally.

---

## 16. Appendix: GBNF/Regex Snippets

### 16.1 Minimal GBNF for LLM JSON
```
root ::= "{" ws fields ws "}"
fields ::= field (ws "," ws field)*
field ::= "\"storeName\"" ws ":" ws string
        | "\"redeemCode\"" ws ":" ws string
        | "\"discount\"" ws ":" ws discount
        | "\"expiry\"" ws ":" ws string
string ::= "\"" chars "\""
chars ::= (char)*
char ::= [^"\\] | escape
escape ::= "\\" ["\\/bfnrt]
discount ::= "{" ws discount_fields ws "}"
discount_fields ::= discount_field (ws "," ws discount_field)*
discount_field ::= "\"type\"" ws ":" ws string
                | "\"value\"" ws ":" ws number
                | "\"qualifier\"" ws ":" ws string
number ::= [0-9]+ ("." [0-9]+)?
ws ::= [ \t\n\r]*
```

### 16.2 Regex Library
- Coupon codes: `\b[A-Z0-9][A-Z0-9-]{3,}\b`
- Dates: `\b(0?[1-9]|[12][0-9]|3[01])[-/](0?[1-9]|1[0-2])[-/](\d{2}|\d{4})\b`
- Month name dates: `\b(0?[1-9]|[12][0-9]|3[01])\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\s+\d{2,4}\b`
- Time mentions: `\b(1[0-2]|0?[1-9]):[0-5][0-9]\s?(AM|PM)\b`
- Boilerplate/T&C cues: `(?i)(terms|conditions|t\&c|validity|disclaimer|\*conditions apply)`

### 16.3 Currency Map & Detection Hints

| Symbol | Currency | Notes |
| ------ | -------- | ----- |
| ₹ | INR | Primary market |
| $ | USD | US partner offers |
| € | EUR | EU partners |
| £ | GBP | UK partners |

Detection: map Unicode symbols before OCR normalization; maintain symbol during canonicalization.

---

**Document Acceptance Tests (Meta)**
- [ ] Engineering config generator validates against defaults in this document.
- [ ] QA checklist references each acceptance test herein.

