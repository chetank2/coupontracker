# Extraction Reliability Program

We have spent months shipping one-off fixes for store names, descriptions, coupon codes, and expiry dates. The patches keep regressing because the heuristics are scattered across services, duplicated between single and multi coupon flows, and untested against historical data. This program proposes a durable solution that restructures the code base and the operational process so we stop debugging the same failures every week.

## 1. Consolidated field pipeline

* Introduce the `FieldExtractionFramework` (already scaffolded under `extraction/framework`). It provides:
  * A registry of strategies per field (store, description, code, expiry) with deterministic ordering.
  * Trace logging that records which strategy emitted the final value, enabling instant RCA.
  * Confidence arbitration so ML, deterministic, and rule-based strategies can coexist without manual wiring.
* Migrate existing heuristics into small, testable `StrategyEntry` implementations instead of spreading them across sanitizer, canonicalizer, and validator classes.
* Maintain a `strategy_catalog.yml` file (to be added) that configures active strategies per build flavour so product and ops teams can hotfix by toggling config rather than shipping code.

## 2. Shared fixtures and red team corpus

* Curate a versioned dataset of annotated screenshots (at least 500 per critical merchant) with golden OCR output and expected field values.
* Add a hermetic test harness that replays each screenshot through the full pipeline, writing snapshots and diffs under `tests/golden`. The harness should run in CI and locally via `./gradlew extractionGoldenTest`.
* Whenever a discrepancy is reported, reproduce it by adding the screenshot to the dataset and letting the regression harness flag whichever field regressed. This replaces ad-hoc manual repro steps.

## 3. Observability and fast rollback

* Emit structured traces from the framework into analytics (for example, `extraction_field_trace` events) with labels for strategy name, confidence, and fallback flag.
* Build a Looker/Metabase dashboard that shows weekly failure rate per field and per merchant. Highlight spikes when a new rollout ships.
* Wrap strategies in feature flags so that a bad heuristic can be disabled remotely. The `StrategyRegistry` simply reloads configuration.

## 4. Ownership and change control

* Assign a DRI for each field:
  * Store name: Search relevance team
  * Description: Content intelligence
  * Coupon code: Merchant integrations
  * Expiry date: Compliance team
* Require RFCs for adding or modifying strategies. RFCs must include:
  * Sample input/output pairs
  * Expected confidence distribution
  * Rollback plan and monitoring checklist
* Enforce reviewer rotation where the DRI signs off changes touching their field registry entry.

## 5. Migration plan

1. **Week 1** – Land the framework skeleton (done) and wire traces from the existing multi coupon service without changing logic. Capture at least 1k traces from production shadow mode.
2. **Week 2** – Port store-name heuristics into dedicated strategies. Delete the old canonicalizer code path once parity is proven by the golden tests.
3. **Week 3** – Port description and coupon code logic. Enable dynamic configuration for strategy ordering.
4. **Week 4** – Integrate expiry calculations and align with timezone normalization using the captured screenshot timestamps.
5. **Week 5** – Turn on CI harness gating. No PR merges without green regression replay.
6. **Week 6** – Deprecate legacy discrepancy triage documents and rely on dashboard alerts + golden diffs.

## 6. Expected outcomes

* 80% reduction in duplicate discrepancy PRs (tracked by weekly query against Jira/Linear tickets).
* Mean time to diagnose field regressions drops below 30 minutes thanks to deterministic traces.
* Strategy registry enables new brand launches without code changes, because merchants become configuration entries.

This program requires cross-team investment, but it converts the debugging treadmill into an engineered system with telemetry, ownership, and reversible rollout controls.
