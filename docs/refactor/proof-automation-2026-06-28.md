# Proof Automation Note - 2026-06-28

Why:
Tasks 7 and 8 need repeatable proof for screenshot regressions and GitHub
Actions runtime status without changing the extraction pipeline.

What changed:
- Added `scripts/prove_real_screenshot_regressions.py` to verify committed
  golden screenshot manifests, image SHA-256 values, replay fixtures, and the
  inventory-only real screenshot corpus in `Coupons `.
- Added `tests/proof/test_screenshot_regression_proof.py` so the proof can run
  under pytest when project Python test dependencies are available.
- Added `scripts/prove_github_actions_runtime.sh` for safe `gh` workflow proof.
  It checks auth and workflow status by default, and only dispatches when
  `--dispatch` is passed.
- Added `workflow_dispatch` to the CI Guardrails workflow and included the
  screenshot replay proof in that workflow.

Failure or gap solved:
The existing regression harness had useful code-level fixtures, but the
committed screenshot replay manifests could drift from images or replay JSON
without a cheap hermetic check. GitHub workflow proof also had no repo-local
entrypoint for confirming whether `gh` could see or dispatch CI.

Quality:
Good. The screenshot proof is deterministic, offline, and CI-safe. The GitHub
proof is read-only unless explicitly dispatched.

Remaining risk:
The 40 screenshots in `Coupons ` are currently inventory-only. They are hashed
and reported, but they need expected coupon JSON before they become gating
field regressions.

Tests/evidence:
- `python3 scripts/prove_real_screenshot_regressions.py`
- Direct Python assertions against the proof module
- `git diff --check`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest` was blocked by pre-existing test/source
  constructor mismatch outside this work item.
- `adb devices` ran outside the sandbox and found no attached devices.
- `scripts/prove_github_actions_runtime.sh` was blocked by invalid `gh` auth.
