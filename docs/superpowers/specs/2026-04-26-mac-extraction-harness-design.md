# Mac Extraction Harness — Design Spec

**Date:** 2026-04-26
**Status:** Approved, ready for implementation plan
**Branch context:** `feature/qwen-multi-coupon-extraction`

## Problem

The current extraction debug loop is:

1. Build APK
2. Sideload to phone
3. Re-download Qwen model (sometimes)
4. Take a screenshot of the result
5. Send the screenshot to an AI agent to identify what is wrong

This loop is slow, manual, and lossy. The screenshot strips out the prompt, the raw model output, and the parser intermediate state — exactly the information needed to diagnose extraction failures.

The annotated ground truth already exists: `Coupons /manifest.json` currently lists 35 annotated samples keyed by `imageSha256`, each with an `expected` block (`storeName`, `description`, `redeemCode`, `expiryDate`, `storeNameSource`, `storeNameEvidence`, `needsAttention`). The folder contains 41 image files total; the 6 unannotated images are non-gating until promoted via the pending-review flow below.

## Goal

A Mac-side harness that runs the same Qwen extraction pipeline against the manifest's images, emits a structured pass/fail report, and replaces the phone screenshot loop for day-to-day iteration. The Android build is demoted to a final smoke test.

The harness must be **falsifiable against Android**. If it diverges from the device, it is wrong until proven otherwise.

## Non-goals

- Replacing the Android extraction runtime in production. Android remains the shipping target.
- Building a UI before the CLI works.
- Generalizing beyond Qwen / GGUF / llama.cpp inference.
- Auto-promoting new screenshots to ground truth without human review.

## Architecture — five layers

### Layer 0 — Parity canary (one image)

A single test that runs one image through both Mac and Android with identical inputs, and verifies parity using the falsifiability ladder below. Must pass before any further harness work is trusted.

### Layer 1 — Mac CLI harness

A script that runs every annotated sample in `Coupons /manifest.json` end-to-end on the Mac and emits structured reports. No UI.

Inputs:
- `Coupons /manifest.json` (ground truth, matched by `imageSha256`)
- Local images
- Pinned Qwen GGUF + mmproj
- Shared prompt template, schema, parser, preprocessor, normalization rules

Outputs:
- Timestamped run artifacts under `build/extraction-eval/runs/<timestamp>/`:
  - `run.json` — full per-sample result (raw output + parsed + diff + timing + all hashes)
  - `run.md` — human-readable summary table
- Stable pointers at `build/extraction-eval/`:
  - `latest.json` — copy of (or symlink to) the most recent `run.json`
  - `latest.md` — copy of (or symlink to) the most recent `run.md`
  - `failures.json` — slice of `latest.json` containing only samples with field drift
  - `baseline.json` — promoted snapshot for regression detection, written only via `--promote-baseline` (see Layer 1.5)

The split keeps a stable path that downstream tools (UI, CI, the `failures.json` consumer) can rely on, while preserving every historical run for forensic comparison.

CLI entry point: `./scripts/eval_extraction_mac.sh`

### Layer 1.5 — Baseline tracking

After each run, diff `latest.json` against `baseline.json` and report:
- Per-field accuracy (pass count / total)
- Fields that **changed** since the last run, even if still wrong (catches silent regressions where one wrong value flips to a different wrong value)
- Optional: write `latest.json` to `baseline.json` only on explicit promotion (`--promote-baseline`)

### Layer 2 — Shared asset enforcement

A single canonical location for assets that must not drift between Mac and Android:

- Prompt template
- JSON schema (current: `app/src/main/assets/coupon_schema.gbnf`, `coupon_model.json`)
- Parser rules / regex tables
- Field normalization rules
- Preprocessing config (target dimensions, color space, normalization constants)
- Model file metadata (GGUF SHA-256, mmproj SHA-256)

Mechanism:
- Source-of-truth files live in one canonical location. The exact path (existing `app/src/main/assets/` vs. a new `shared/extraction/`) is decided during Layer 2 implementation; both are viable and the choice is logistical, not architectural. See "Open questions" below.
- Android either reads the assets from their canonical location at runtime, or has them copied in by a Gradle task at build time. There is no hand-maintained second copy.
- A unit test in `app/src/test/` hashes the assets and fails CI if any consumer's copy diverges from the canonical source.
- For parser logic that lives in Kotlin (`PromptBuilder.kt`, `SchemaValidator.kt`, etc.), the Mac harness invokes the same Kotlin via a JVM CLI wrapper rather than reimplementing in Python. This eliminates "two parsers" drift entirely.

### Layer 3 — Final Android smoke

Android is built and run against, in order:

1. **The fixed canary set** — 3 specific images, IDs hardcoded, never drifts. Always runs, regardless of Mac results.
2. **Current Mac failures** — every sample the most recent Mac eval flagged as failing. Empty if Mac is green; the canary set still runs.
3. **(Optional) Changed-since-baseline samples** — anything `latest.json` flags as drifted from `baseline.json` since the last promotion, even if still passing. Off by default; opt-in for higher-confidence release smoke.

Latency is recorded and compared. Field drift is compared. If Android disagrees with Mac, the **phone wins** (see resolution rule).

## Falsifiability ladder (parity rule)

When checking Mac vs Android equivalence, compare in this order:

1. **Preprocessed image hash** — exact equality required. If they differ, preprocessing is wrong; nothing else matters.
2. **Model asset hashes** — exact equality required (GGUF SHA-256, mmproj SHA-256).
3. **Prompt text** — exact equality required (whitespace and all).
4. **Raw model output** — exact equality only if the runtime is fully deterministic (temperature 0, fixed seed, identical sampler config). Otherwise skip this rung.
5. **Parsed fields** — must show no material drift. "Material" = different value for any field in the manifest's `expected` set, after normalization.

If rungs 1–3 pass and rung 5 passes, parity is established. Rung 4 is a nice-to-have signal, not a gate.

## Resolution rule — when Mac and Android disagree

**The phone wins, always.** Android is the shipping target.

If the Mac harness says X and the device says Y on the same image:
1. Treat the harness as broken.
2. Identify which rung of the ladder diverged.
3. Fix the harness to match the device, not the other way around.

The harness is allowed to be a comfortable lie only if it is corrected the moment it diverges.

## Reports — fields recorded per run

Every run records, in `latest.json`:

**Run metadata (once per run):**
- Run timestamp (UTC)
- Git commit hash of the repo at run time
- Qwen GGUF SHA-256
- mmproj SHA-256
- llama.cpp commit / build flags
- Prompt template version + content hash
- Schema version + content hash
- Parser version + content hash
- Preprocessing config hash
- Host OS / arch (e.g. `darwin-arm64`)

**Per sample (one entry per image):**
- `id`, `imageSha256`, `image` (path)
- `expected` (copied from manifest)
- `preprocessed_image_sha256`
- `prompt_text` (full, verbatim — for failed samples; reference-only for passing)
- `raw_model_output` (full, verbatim)
- `parsed` (post-parser JSON)
- `field_diff` — list of `{ field, expected, got, status }` where `status ∈ {match, missing, wrong, extra}`
- `latency_ms` (total) + sub-timings (preprocess, inference, parse) if cheap
- `pass` (bool — true iff all manifest fields match after normalization)

## Pending ground-truth review flow

New screenshots can be added to the eval set, but they do not become accuracy gates without human review.

- New images land in `Coupons /pending/` with a stub manifest entry.
- The harness runs them but reports them in a separate `pending.md` section, not against `baseline.json`.
- A human edits the entry to fill in `expected`, then moves the entry to the main `manifest.json`.
- Only manifest-promoted entries count toward accuracy / regression gates.

This prevents the manifest from rotting via auto-confirmation of whatever the model happened to output the day the sample was added.

## CLI before UI

Build order is firm: **the CLI is the engine; the UI is a window into it.**

- The CLI must be runnable headless (cron, CI, pre-push hook) and produce the same artifacts a UI would render.
- The UI (when built, after the CLI is solid) reads the same `latest.json` / `failures.json` and renders them. It does not own any logic.
- Acceptable UI options, in order of effort: rendered `latest.md` in an IDE, static HTML report, Streamlit. Pick the least effort that lets you click an image and see its diff + raw output.

The UI is explicitly out of scope for the first implementation milestone.

## Implementation order

1. **This spec** (done when the user approves)
2. **Layer 0 — parity canary** (one image, manual run on Mac and Android, compare outputs against the falsifiability ladder)
3. **Layer 1 — CLI harness** (manifest-driven eval, all reports, no baseline yet)
4. **Layer 2 — shared asset wiring** (single source of truth, hash-drift unit test)
5. **Layer 1.5 — baseline tracking** (regression detection)
6. **Layer 3 — Android smoke wiring** (instrumented test against failing-Mac + canary set, latency reporting)
7. **(Out-of-scope for v1)** Thin UI on top of the report files

Layer 0 must succeed before Layer 1 is trusted. If Layer 0 reveals deep divergence (e.g. preprocessing cannot be made bit-exact across platforms), the design is revisited before Layer 1 is built.

## Open questions / decisions deferred to the implementation plan

- Exact path for shared assets: keep `app/src/main/assets/` as canonical (harness reads from there) vs. move to `shared/extraction/`. Decide during Layer 2.
- Whether the Mac harness invokes Kotlin parser via a JVM wrapper or reimplements in Python. Recommendation: JVM wrapper, since `PromptBuilder.kt` / `SchemaValidator.kt` already exist. Decide during Layer 1.
- Which llama.cpp build to pin on Mac (project already has `scripts/build_llama_cpp.sh`). Decide during Layer 0.
- Whether `--promote-baseline` is interactive or automatic on a passing run.

## Success criteria

The harness is successful when:

1. A normal extraction-debug iteration runs on the Mac in seconds, against all manifest samples, with no APK build, no `adb`, no phone, no screenshot.
2. The harness output is sufficient to diagnose an extraction failure without running it on a phone (raw model output + prompt + diff are all there).
3. A Mac-green / Android-red discrepancy is detectable within one Layer 3 smoke run, and the offending rung of the falsifiability ladder is identifiable from the report.
4. A regression that flips a previously-correct field to wrong is caught by the next eval run, before the change reaches Android.
