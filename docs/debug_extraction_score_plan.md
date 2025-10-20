# Temporary Extraction Debug Score Plan

## Objective
Create a temporary, developer-facing diagnostic overlay that ranks every coupon extraction by stage so engineers can quickly identify whether a failure stemmed from detection (YOLO), OCR, LLM parsing, or fusion/validation. The overlay must be easy to remove before shipping to end users.

## Guiding Principles
1. **Non-production only** – Surface diagnostics only in debug/internal builds and keep the UI isolated so it can be deleted without impacting release APKs.
2. **Leverage existing telemetry** – Reuse `ExtractResult`, `RunPath`, and confidence scores instead of building brand-new probes.
3. **Deterministic attribution** – Always emit a primary culprit stage together with per-stage scores so QA knows where to look.
4. **Minimal persistence** – Store diagnostics in-memory via a repository so we avoid schema migrations for a temporary feature.

## Phased Workstream

### Phase 1 – Diagnostic scaffolding
- Define lightweight data models (`ExtractionDebugSnapshot`, `ExtractionStageScore`) capturing stage scores, status (healthy/degraded/failed), notes, and timestamp.
- Implement `ExtractionDebugScorer` helpers that convert:
  - `ExtractResult` (LLM path)
  - `FieldExtractionResult` + `RunPath` (detector/OCR pipeline)
  - Universal / traditional OCR fallbacks
  into structured snapshots using heuristics derived from existing confidence metrics.
- Add an in-memory `ExtractionDebugRepository` (Hilt singleton) exposing a `StateFlow<Map<Long, Snapshot>>` for the UI.

### Phase 2 – Pipeline integration
- Thread snapshots through extraction flows:
  - Populate `FieldExtractionResult` with debug metadata during two-stage detection.
  - Capture LLM-first, universal, and OCR fallback outcomes and attach snapshots to pending previews.
  - When coupons are persisted (`persistCoupon` and batch saves), publish snapshots to the repository keyed by coupon ID.
- Ensure previews keep their snapshots so delayed saves still report diagnostics.

### Phase 3 – Debug UI surface
- Extend `HomeViewModel` state with the repository flow and pass snapshots to the coupon list.
- Update `EnhancedCouponCard` to render a compact debug panel (overall score + per-stage breakdown) only when `BuildConfig.DEBUG` is true and a snapshot exists.
- Add clear styling to highlight the primary culprit stage for quick triage.

### Phase 4 – Validation & cleanup hooks
- Provide unit coverage for scorer heuristics to lock expected classifications for representative scenarios.
- Document removal steps (delete repository + UI block) and flag TODO so the feature is not shipped in production builds.

## Implementation status

- ✅ `ExtractionDebugSnapshot`, `ExtractionStageScore`, and the stage status enums all ship in [`app/src/main/kotlin/com/example/coupontracker/debug/ExtractionDebugModels.kt`](../app/src/main/kotlin/com/example/coupontracker/debug/ExtractionDebugModels.kt). The file also contains the concrete `ExtractionDebugScorer` heuristics and a `@Singleton` in-memory `ExtractionDebugRepository` that backs the UI surface.
- ✅ `ScannerViewModel` threads snapshots through every extraction branch (LLM-first, two-stage, universal, and traditional OCR) and forwards them to persistence paths, ensuring debug data accompanies real coupon saves.
- ✅ `HomeViewModel` exposes repository data alongside coupon state so the feed can render the diagnostics for each persisted item.
- ✅ `EnhancedCouponCard` renders the debug panel in Compose behind a `BuildConfig.DEBUG` guard, keeping the feature developer-only while providing per-stage context in internal builds.
