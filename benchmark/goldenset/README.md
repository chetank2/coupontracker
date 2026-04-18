# Coupon Extraction Golden Set

Labeled samples for the Phase 1 baseline benchmark.

## Layout

- `manifest.json` — ordered list of samples; schema in `manifest.schema.json`.
- `images/` — PNG screenshots. Committed synthetic samples only.
- `images/_local/` — gitignored; drop real coupon screenshots you cannot
  redistribute here, then add manifest entries pointing at the relative path.
- `replay/` — per-sample pre-recorded canonical JSON, keyed by `id`. Used by
  `ReplayCouponModel` so the hermetic benchmark works without a device.

## Growing the set

1. Add a new sample definition to `SAMPLES` in `scripts/generate_golden_set.py`
   OR drop a real screenshot into `images/_local/` and hand-edit `manifest.json`.
2. If you used the script, re-run:
   ```
   python3 scripts/generate_golden_set.py
   ```
   This rewrites `manifest.json` and the replay fixtures; commit the updates.
3. If you added a hand-authored sample, compute its SHA-256 with
   `shasum -a 256 path/to.png` and put the hex under `imageSha256`.

## Re-recording replay fixtures against a live model

```
./gradlew :app:connectedBenchmarkAndroidTest -PcouponBenchmark=record
```
Writes a replay fixture per sample under `replay/`. Review the diff carefully
before committing — this becomes the CI-enforced baseline.
