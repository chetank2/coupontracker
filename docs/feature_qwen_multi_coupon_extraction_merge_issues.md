# Issues introduced when merging main into `feature/qwen-multi-coupon-extraction`

## 1. Two-stage detector now crashes immediately because the shipped assets are still placeholders
- `TwoStageDetector` now enforces a 5 MB minimum via `ModelAssetIntegrity.ensureAssetMinSize(...)` before it will spin up the interpreters.【F:app/src/main/kotlin/com/example/coupontracker/ml/ModelAssetIntegrity.kt†L14-L35】【F:app/src/main/kotlin/com/example/coupontracker/ml/TwoStageDetector.kt†L120-L156】
- The staged binaries under `app/src/main/assets/models/multi_coupon/` are still the ~0.8 KB stubs that triggered the original production outage.【e31d04†L1-L5】
- The manifest keeps `stub_mode` set to `false`, so release builds take the production path instead of the debug stub path and will always throw the guard exception.【F:app/src/main/assets/models/multi_coupon/manifest.json†L4-L30】
- As a result, the scanner view model now flips straight into an unrecoverable error state on app start and never exposes the legacy OCR-only fallback that QA relied on.【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L146-L191】

**Impact:** the branch will ship with multi-coupon detection hard-disabled until we either publish the real TFLite binaries or re-mark the manifest as `stub_mode=true` and surface a proper download workflow.

## 2. Vision remains disabled because the mmproj projector is still absent
- The merge did not add `mmproj-model-f16.gguf` (or any equivalent vision projector) to the Android assets tree, so the MiniCPM/Qwen runtime still only has the text model available.【0b1f2d†L1-L2】
- Without the projector the JNI logs will continue to say “Vision: DISABLED (need mmproj)”, leaving Qwen blind to layout cues – the original regression we were asked to unblock.

**Impact:** even if the detector guard is addressed, Qwen still runs text-only and cannot improve store recognition.

## 3. No user-facing recovery path when detector integrity fails
- The spec called for a blocking dialog that offers a one-tap download when integrity checks fail, but the merge only logs telemetry and sets `ScannerUiState.Error`/`BatchScannerUiState.error` – there is no actionable UI for the user to recover.【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt†L146-L191】【F:app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/BatchScannerViewModel.kt†L52-L88】

**Impact:** QA (and end users) are stuck on an error screen with no guidance, so even once real models are uploaded we will need another patch to hook up the download dialog.

---

Addressing these three items will get the feature branch back to parity with main while honoring the integrity requirements laid out in the handoff notes.
