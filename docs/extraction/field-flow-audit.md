# Coupon Field Flow Audit

This map reflects the current Compose-first scanner flow. Older fragment/XML
routes still exist for legacy screens and Safe Args generation, but
`MainActivity` launches Compose with `AppNavigation`.

## 1. Single Screenshot Upload

- Entry point: `ScannerViewModel.scanCouponFromUri(...)`.
- Current routing decodes the bitmap, runs crop/layout detection, then chooses
  one of these paths:
  - detected single crop -> crop OCR through `OcrFirstCouponExtractor`;
  - multiple regions -> `MultiCouponExtractionService`;
  - no reliable crop -> review-safe OCR fallback only when the screenshot is
    not classified as multi-coupon.
- Save is delegated through `SaveScannedCouponUseCase`, which persists the
  scan result and queues automatic verification when confidence/layout state
  requires it.

## 2. Multi-Coupon Screenshots

- Entry point: `MultiCouponExtractionService.extractMultipleCoupons(...)`.
- Foreground capture is intentionally heuristic/OCR-first for latency.
- Heuristic-owned coupons must be saved as review/verification candidates, not
  trusted final data.
- Full-screen OCR is not valid proof for final code or expiry when a crop is
  available.

## 3. Verification And Gemma

- Entry point: `VerifyCouponWorker`.
- The worker loads the saved coupon, runs deterministic cleanup, decides whether
  Gemma Vision is allowed/needed, prepares an active crop, runs crop OCR, then
  asks Gemma for field labels/states.
- `VisionEvidenceMergePolicy` merges only crop-supported evidence into final
  fields. Codes require exact OCR support. Missing code/expiry can be trusted
  only when field state explains the absence.
- Malformed or low-confidence model output must end in review/failure state,
  not trusted cleanup.

## 4. Field Authority

- OCR owns exact visible text.
- Gemma owns visual ownership, layout, and semantic field labels/states.
- Validators and merge policies decide whether fields are trusted, need review,
  or are rejected.
- Placeholders such as generic store names, generic offers, or assumed no-code
  states must remain review evidence and must not become trusted coupon data.

## 5. Known Legacy Surface

- `activity_main.xml` and `nav_graph.xml` are retained for legacy fragments and
  generated Safe Args classes.
- They are not the launcher navigation path while `MainActivity` uses
  `setContent { AppNavigation(...) }`.
- Any migration that removes those XML files must first remove or replace the
  legacy fragments and generated `*Directions` usages.

## Gaps To Watch

1. Single-crop uploads can still show OCR-first fields before background
   verification finishes.
2. Foreground capture stays fast by deferring Gemma; correctness depends on
   review-safe persistence and queued verification.
3. Legacy fragment paths remain in source and should be quarantined or migrated
   separately.
4. Regression fixtures should cover multi-card contamination, no-code modals,
   malformed Gemma JSON, and crop OCR failure.
