# Extraction Plan Review

## Overall Assessment
The proposed plan is comprehensive and aligns with the problems observed in the provided coupon examples. It introduces a deterministic-first pipeline with clear fallbacks, which should improve repeatability and reduce reliance on the LLM. The emphasis on regionization, validation, and persistence addresses the primary failure modes we encountered: noisy OCR inputs, misidentified stores, and lost multi-coupon cards.

## Key Strengths
1. **Layered Architecture** – Breaking responsibilities into classifier, regionizer, OCR, deterministic extractor, composer, validator, and persister should make the system easier to test and reason about. Each stage has explicit outputs and failure handling.
2. **Deterministic-first Extraction** – Prioritizing regex/heuristics before invoking the LLM lowers latency and cost while preserving control over field validation. The LLM fallback is tightly scoped and still flows through validation, preventing hallucinated descriptions.
3. **Regionization Focus** – The global crop plus mode-specific rules directly target the noise that caused previous misclassification (e.g., map overlays, CTA buttons). This should significantly improve OCR quality.
4. **Validator & Normalizer** – The plan introduces concrete guardrails for store names, description content, and glyph fixes. The scoring mechanism gives us a quantitative signal for manual review triage.
5. **Persistence Guarantees** – Enforcing multi-coupon persistence and deduplication protects against the October 15th regression where secondary coupons were dropped.
6. **Testing Strategy** – A golden dataset derived from real failures, paired with unit tests per layout type, will make regressions much easier to detect.

## Risks & Open Questions
1. **Classifier Availability** – The plan assumes a reliable layout classifier. We need clarity on whether we already have training data or if heuristic detection will bootstrap this stage.
2. **Regionizer Robustness** – The fixed percentage crops may not generalize to all screenshot aspect ratios or device resolutions. We should plan for configuration or auto-tuning based on detected DPI/metadata.
3. **Regex Coverage** – While the regex set covers the sample layouts, we should document a process for iteratively expanding it (e.g., logging unmatched offers) to prevent silent misses.
4. **Store Canonicalization** – The curated list is essential but requires maintenance. We should couple it with telemetry to surface unknown stores that users frequently correct.
5. **LLM Dependency** – Even though the fallback is constrained, we should define latency/error budgets and caching strategies if the offline model is unavailable.
6. **Telemetry Scope** – The counters are well-chosen, but we should also emit stage-level timings to spot performance regressions.

## Recommendations
1. **Bootstrap Plan for Classifier** – Document whether the classifier starts as a heuristic rule or lightweight CNN, and how we will transition to YOLO in P2.
2. **Configurable Regionizer Thresholds** – Expose the crop percentages and whitespace thresholds via configuration so we can tune them without a code push.
3. **Regex Test Harness** – Add unit/property tests that feed OCR strings through the regex set to ensure we don’t introduce overlapping or contradictory matches.
4. **Store Dictionary Management** – Store canonical names in a centralized JSON with metadata (aliases, normalized forms) to simplify updates and telemetry analysis.
5. **LLM Fallback Budgeting** – Track LLM invocation counts and durations in telemetry to inform caching or batching decisions.
6. **Golden Set Automation** – Integrate the golden corpus into CI with automatic diffing so failures surface immediately after merges.

## Conclusion
The plan is strong and addresses previously identified weaknesses. With clarity on classifier bootstrapping and configurable regionizer parameters, we should move forward. The suggested telemetry and testing additions will help ensure the pipeline remains maintainable as we integrate more layout types and heuristics.

---

## Addendum – Action Plan Review (March follow-up)

The follow-up implementation blueprint adds the missing execution detail from the original proposal. Overall it is well scoped and directly answers the open risks we flagged earlier. Below is a point-by-point readout to help translate it into tickets and call out remaining concerns.

### What Looks Solid
1. **Explicit Success Criteria** – The golden-set pass conditions and error-rate targets provide a crisp “definition of done.” They align with our October regressions and should plug directly into CI.
2. **Classifier Path** – The staged approach (heuristics now, YOLO later) removes the model-blocker risk and keeps us shippable during P0. Documenting the hand-off threshold (0.6 confidence) is particularly helpful.
3. **Config-driven Regionizer** – Shipping `assets/coupon_regionizer.json` plus a Dev Settings tuner addresses the configurability recommendation we raised. This will let QA iterate on crops without APK rebuilds.
4. **Regex Harness + Store Canon** – The dedicated test suite, `stores.json`, and telemetry hook for unknown stores cover the maintenance gaps we highlighted.
5. **Golden Corpus + CI** – End-to-end assertions on the six canonical screenshots ensure the success metrics are continuously enforced.
6. **Telemetry Coverage** – The proposed counters and timers mirror the observability checklist we requested and should surface both accuracy and performance drift.

### Multi-coupon Extraction Coverage
The tightened plan does call out the full multi-coupon path—from regionizing grid layouts to persisting every distinct (store, code) pair—but it is worth underscoring the expected flow so it remains a release gate:

1. **Regionizer Support** – The `MULTI_GRID` mode combined with configurable `minCardWidthPx`, `minGapPx`, and `maxCols` thresholds should consistently isolate each tile in screenshots like the Times Prime 2×2 grid. QA should validate this against wider aspect ratios to ensure no tiles bleed into neighbors.
2. **Extractor Iteration** – Once regions are identified, the deterministic extractor must operate per tile. The Regex Harness needs fixtures that assert we return four offers/codes for the Times Prime sample and do not accidentally merge them into a single description block.
3. **Persistence Contract** – Updating `handleMultiCouponResult()` to emit and persist a `List<Coupon>` (with deduplication by canonical store + code) directly addresses the “second coupon lost” regression. This change should ship with an instrumentation test that confirms all four grid coupons make it into storage and the success toast enumerates the stores.
4. **Golden Corpus Guardrail** – The golden CI check for the grid image is the backstop; keep it mandatory for release so any future regression in segmentation or persistence is caught immediately.

If any of these steps slip, we risk repeating the October 15th discard. Keeping them explicitly tracked in the multi-coupon epic will ensure the feature lands end-to-end rather than piecemeal.

### Items to Clarify or Adjust
1. **Regionizer Dev UI Scope** – Confirm whether the preview tool can ingest local gallery images and export updated configs. Without export, tuning still requires manual edits.
2. **Regex Negative Cases** – The harness list mentions 40+ cases. Let’s ensure we include examples for hyphenated codes, mixed-case stores, and offers containing slash pricing (e.g., “2 for ₹199”).
3. **Store Canon Telemetry Volume** – `unknown_store_detected` should include screenshot hash or session id so we can correlate to user corrections later.
4. **Description Fallback Text** – The default strings (“Exclusive offer…”, “Special discount…”) could surface in production if both store and offer fail. We should tag these with NEEDS_REVIEW automatically to avoid silent bland entries.
5. **LLM Timeout Handling** – The 900 ms ceiling is reasonable, but we still need a retry/backoff policy when the on-device model is unavailable or warm-up exceeds the limit.
6. **Multi-coupon Review Screen** – If we add the optional review table, specify whether edits sync back to the dedup hash to prevent discrepancies between the UI and persisted values.

### Ready-to-Track TODOs
The checklist in section 13 is comprehensive. When importing into Jira/Linear, consider grouping under epics by stage (Regionizer, Extraction, Persistence) to clarify ownership. Additionally:
- Add a task for tagging NEEDS_REVIEW on composer fallbacks.
- Include QA-owned tasks for curating and updating the golden set as new failure cases emerge.

### Final Assessment
Nothing in the tightened plan blocks execution. Once the clarifications above are acknowledged (or translated into follow-up tasks), the team can proceed with implementation. This addendum, coupled with the original review, should give engineering, QA, and ML clear marching orders through P2.
