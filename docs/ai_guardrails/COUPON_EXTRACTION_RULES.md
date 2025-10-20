# Coupon Extraction Guardrail Rules

The following rules codify the non-negotiable guardrails for coupon extraction. Each rule is backed by an incident or deep-dive analysis that previously broke production, so new contributions must honor the rationale before shipping changes.

| # | Guardrail | Rationale | Incident link |
|---|-----------|-----------|---------------|
| 1 | Preserve the OCR store name unless corroborated by trusted heuristics. | Swapping BOAT with McDonald showed how hallucinated brands destroy user trust. | [Extraction Failure RCA](../../EXTRACTION_FAILURE_ANALYSIS.md) |
| 2 | Emit the coupon code exactly as captured; never normalize or invent characters. | Replacing `BTXS5T13LI9V5` with `VONTIME` left customers with unusable codes. | [Extraction Failure RCA](../../EXTRACTION_FAILURE_ANALYSIS.md) |
| 3 | Reject store names that fail brand sanity checks rather than accepting garbage. | The "Pastm Patm" incident was solved only after enforcing brand validators. | [All 4 Discrepancies Fixed](../../ALL_4_DISCREPANCIES_FIXED.md) |
| 4 | Provide fallback paths when a detector misses so fields do not silently disappear. | A single failed pass previously cascaded to "Unknown Store" with empty fields. | [Cred Voucher Root Cause](../../EXTRACTION_ROOT_CAUSE_ANALYSIS.md) |
| 5 | Tune confidence thresholds so that valid detections are never discarded. | Over-aggressive `MIN_EXTRACTION_CONFIDENCE` thresholds produced blank outputs. | [Cred Voucher Root Cause](../../EXTRACTION_ROOT_CAUSE_ANALYSIS.md) |
| 6 | Always return an expiry date when the screenshot shows one. | Users complained that missing expiries defeated reminder notifications. | [Expiry Date Critical Fix](../../FIX_EXPIRY_DATE_CRITICAL_2025-10-11.md) |
| 7 | Do not rewrite or reinterpret explicit dates. | An LLM rewriting `05 May` to `May 16` created false deadlines. | [Expiry Date Critical Fix](../../FIX_EXPIRY_DATE_CRITICAL_2025-10-11.md) |
| 8 | Keep expiry data out of fallback fields like description. | Burying expiry details in `offerText` hid deadlines from the UI. | [Required Fields Fix](../../FIX_REQUIRED_FIELDS_KAPIVA_2025-10-11.md) |
| 9 | Block responses that omit mandatory fields such as `redeemCode` and `expiryDate`. | The KAPIVA outage surfaced when JSON skipped both fields entirely. | [Required Fields Fix](../../FIX_REQUIRED_FIELDS_KAPIVA_2025-10-11.md) |
| 10 | Anchor relative date math to the screenshot capture timestamp. | Calculating "in 13 days" from the current clock inflated deadlines by weeks. | [Screenshot Timestamp Bug](../../FIX_SCREENSHOT_TIMESTAMP_2025-10-11.md) |
| 11 | Halt extraction when the timestamp pipeline fails instead of defaulting to now. | Dropping EXIF data silently caused cascading expiry miscalculations. | [Screenshot Timestamp Bug](../../FIX_SCREENSHOT_TIMESTAMP_2025-10-11.md) |
| 12 | Treat UI ratings, badges, and metadata as non-monetary noise. | The Zepto cafe coupon mis-read a 4.38★ rating as 4% cashback. | [Rating Confusion Fix](../../FIX_RATING_CONFUSION_2025-10-11.md) |
| 13 | Ignore spurious numbers when the offer text already conveys the benefit. | OCR noise like "7100" yielded absurd 710% discounts. | [Extraction Simplification Fix](../../FIX_EXTRACTION_SIMPLIFICATION_2025-10-11.md) |
| 14 | Prefer rich descriptions over brittle amount inference. | Shifting focus to description solved repeated amount hallucinations. | [Extraction Simplification Fix](../../FIX_EXTRACTION_SIMPLIFICATION_2025-10-11.md) |
| 15 | Ban ISO 8601 timestamps in LLM output unless explicitly required. | `May-31-2025T23:59Z` failed downstream parsers and hid expiries. | [ISO Timestamp Fix](../../FIX_ISO_TIMESTAMP_AMOUNT_HIDDEN_2025-10-11.md) |
| 16 | Re-validate coupon codes for missing prefix/suffix characters. | Losing the leading "O" in `OTTPHONEBUFF` rendered the code invalid. | [ISO Timestamp Fix](../../FIX_ISO_TIMESTAMP_AMOUNT_HIDDEN_2025-10-11.md) |
| 17 | Honor product decisions to hide unreliable fields such as amount. | Showing a 36% discount after owners asked to hide amounts regressed UX. | [ISO Timestamp Fix](../../FIX_ISO_TIMESTAMP_AMOUNT_HIDDEN_2025-10-11.md) |
| 18 | Enforce strict JSON output and reject garbage tokens. | Temperature drift once produced comma spam that bypassed structured parsing. | [LLM Garbage Output Fix](../../LLM_GARBAGE_OUTPUT_FIX.md) |
| 19 | Reset inference state between requests so cached tokens do not corrupt results. | A missing KV cache reset crashed second-pass extractions. | [LLM Garbage Output Fix](../../LLM_GARBAGE_OUTPUT_FIX.md) |
| 20 | Keep decoding settings deterministic enough for reproducible JSON. | High temperature and zero repetition penalty triggered runaway text. | [LLM Garbage Output Fix](../../LLM_GARBAGE_OUTPUT_FIX.md) |
| 21 | Audit extraction logs for anomalies before promoting builds. | Release logs highlighted the fallback heuristics that masked bad data. | [Log Analysis & Fixes](../../LOG_ANALYSIS_AND_FIXES.md) |
| 22 | Treat "No expiry date pattern matched" as a release blocker. | Earlier deployments ignored this log and shipped blank expiries. | [Log Analysis & Fixes](../../LOG_ANALYSIS_AND_FIXES.md) |
| 23 | Consolidate heuristics into the shared field framework instead of ad-hoc patches. | One-off fixes kept regressing until the reliability program centralized control. | [Extraction Reliability Program](../extraction_reliability_program.md) |
| 24 | Require strategy registry updates and owner sign-off for every new heuristic. | Unreviewed changes previously broke merchant-specific flows. | [Extraction Reliability Program](../extraction_reliability_program.md) |
| 25 | Capture every incident into the regression corpus before marking it resolved. | Without golden tests, the same failure modes kept reappearing weekly. | [Extraction Reliability Program](../extraction_reliability_program.md) |

