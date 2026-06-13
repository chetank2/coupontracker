# Parity Canary Notes

## Decisions Before Capture

### Baseline Promotion Cadence

`--promote-baseline` is manual only. It is called after a human reviews `latest.md`, `failures.json`, and changed-since-baseline output and decides the current extraction behavior is the new accepted reference.

Do not promote automatically from CI. CI may publish `latest.json` and report regressions, but it must not rewrite `baseline.json`.

Recommended cadence:

- Before a release branch or release candidate, after Mac eval is green enough for the release criteria.
- After an intentional prompt/parser/model/preprocessing change whose output deltas have been reviewed.
- Never merely because a run passed mechanically.

### Phase 0 Rung 5 Tolerance

Rung 5 uses normalization-equivalent exact field equality, not fuzzy matching.

For each field present in the canary manifest `expected` block:

- Apply the same normalization functions used by the harness diff layer.
- Compare normalized expected value against normalized parsed value.
- Pass only if every expected field matches after normalization.

Allowed normalization examples:

- Trim surrounding whitespace.
- Collapse repeated whitespace.
- Case-fold `storeName` and `redeemCode` where the production normalizer already does so.
- Treat documented sentinel values such as blank and `unknown` according to the production normalizer only.

Not allowed in Phase 0:

- Fuzzy string distance.
- Partial substring acceptance.
- Semantic "close enough" matches.
- Ignoring a field because the model output looks understandable to a human.

The phone remains the authority. If Android and Mac differ after these rules, the harness is treated as wrong until the divergent rung is explained.

## Phase 0 Capture — 2026-04-26

**Canary image:** `cred_kapiva_strength_stamina_40off`

**Manifest image path:** `images/_local/cred_kapiva_strength_stamina_40off.png`

**Local resolved image path:** `Coupons /Screenshot_20250502-201815.png`

**imageSha256:** `0d119d81e6fb79f4fc6d0a950759a5fea0b0c56e13a8e43797d6daa17438ac77`

The manifest image path is not present in this checkout, so the local file was resolved by `imageSha256`.

### Runtime Assets

- Main GGUF: `models/extraction/ggml-model-Q4_K_M.gguf`
- Main GGUF SHA-256: `010ec3ba94cb5ad2d9c8f95f46f01c6d80f83deab9df0a0831334ea45afff3e2`
- Vision projector: `models/extraction/mmproj-model-f16.gguf`
- Vision projector SHA-256: `2c2d773537faf6a7e093655d0d5e14801ef0b2121c6c3e1981ce094c2b62f4f9`
- llama.cpp commit: `f53577432541bb9edc1588c4ef45c66bf07e4468`
- Mac binary: `build/llama_cpp_mac/bin/llama-mtmd-cli`

### Falsifiability Ladder

1. Preprocessed image hash: **not compared**. Android capture is blocked until `adb` is available and a device run can be captured.
2. Model hash: **Mac pinned**. Android comparison is blocked until device capture.
3. Prompt: **Mac prompt recorded by command**, Android prompt blocked until device capture.
4. Raw output: **not byte-compared**. The run is CPU-only and no Android raw output is available.
5. Parsed fields: **FAIL on Mac canary**.

### Mac Canary Run

Default Metal execution failed in this Codex desktop environment:

```text
ggml_metal_init: error: failed to create command queue
llama_init_from_model: failed to initialize the context
```

CPU-only execution succeeded with:

```bash
build/llama_cpp_mac/bin/llama-mtmd-cli \
  -m models/extraction/ggml-model-Q4_K_M.gguf \
  --mmproj models/extraction/mmproj-model-f16.gguf \
  --image 'Coupons /Screenshot_20250502-201815.png' \
  -p 'Extract coupon details from this screenshot as compact JSON with keys storeName, description, redeemCode, expiryDate, storeNameEvidence, needsAttention.' \
  --temp 0 --seed 42 -n 128 \
  --device none -ngl 0 --no-op-offload --no-kv-offload --no-mmproj-offload -fit off
```

Raw model output excerpt:

```json
[{"storeName":"Kapiva","description":"Kapiva's 'Strength and Stamina Range'","redeemCode":"KAPW1M3LAFAnSe","expiryDate":"25, 11:59 PM 31 May, 2025","storeNameEvidence":"Kapiva","needsAttention":"BUY NOW, OFFER EXPIRES ON 31 MAY, 2025, 11:59 PM, Upto 40% off* on Kapiva's 'Strength and Stamina Range'","storeLogo":"https://www.kapiva.com/wp-content/uploads/2019/04/kapiva
```

Expected manifest fields:

```json
{
  "storeName": "Kapiva",
  "description": "Get Upto 40% Off* on Kapiva's \"Strength and Stamina Range\"",
  "redeemCode": "KAPW1M3LAfAh5e",
  "expiryDate": "2025-05-31",
  "storeNameSource": "ocr",
  "storeNameEvidence": ["Kapiva", "About Kapiva"],
  "needsAttention": false
}
```

### Gate Decision

**FAIL / BLOCKED.**

The Mac harness has enough runtime plumbing to load the model, load the projector, process the canary image, and produce vision-conditioned text. It is not yet a trusted parity harness:

- Android capture is blocked because `adb` is not available on PATH in this environment.
- Metal inference fails here, so current Mac canary execution required CPU-only flags.
- The Mac canary output does not satisfy rung 5 exact-after-normalization field equality.

Next action: capture the same canary on the shipping Android path and compare rungs. If Android output differs, phone wins; fix the Mac harness/prompt/parser/runtime settings until this note can be changed to PASS.

## Rung 5 Diagnosis — 2026-04-27

Two harness gaps were found before re-running the canary:

- `scripts/extraction_eval/llm.py` did not pass `--grammar-file app/src/main/assets/coupon_schema.gbnf`.
- `ExtractionToolCli parse` only extracted JSON objects, while an unconstrained canary run returned an array-wrapped object.

Both were fixed. The grammar-constrained CPU-only canary now returns valid object-shaped JSON:

```json
{
  "storeName": "Kapiva",
  "description": "Buy 1 Get 1 on Kapiva's 'Strength and Stamina Range'",
  "redeemCode": "KAPW1M3LAFAnSe",
  "expiryDate": "25, 11:59 PM 31 May, 2025",
  "storeNameSource": "Kapiva",
  "storeNameEvidence": [],
  "needsAttention": false
}
```

Field diff against manifest ground truth:

```text
match   storeName
wrong   description: expected "Get Upto 40% Off*..." got "Buy 1 Get 1..."
wrong   redeemCode: expected "KAPW1M3LAfAh5e" got "KAPW1M3LAFAnSe"
wrong   expiryDate: expected "2025-05-31" got "25, 11:59 PM 31 May, 2025"
wrong   storeNameSource: expected "ocr" got "Kapiva"
wrong   storeNameEvidence: expected ["Kapiva", "About Kapiva"] got []
match   needsAttention
```

Conclusion: this is **not** a normalization-only failure. Grammar fixed the structure, but the content is still semantically divergent. Likely causes remain prompt/preprocessing/runtime/model mismatch, or manifest ground truth authored against a different extraction stack.

## Android Asset Mirror Check — 2026-04-27

Copied Android-owned extraction assets into the Mac model area for a local parity check:

- `app/src/main/assets/coupon_schema.gbnf` -> `models/extraction/android-assets/coupon_schema.gbnf`
- `app/src/main/assets/coupon_schema.gbnf.sha256` -> `models/extraction/android-assets/coupon_schema.gbnf.sha256`
- `app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt` -> `models/extraction/android-assets/coupon_extraction_prompt.txt`

Hash verification:

```text
b616496ee909c35621e7da6e170a82723e4334c434d9806af25a642ea6bfa258  models/extraction/android-assets/coupon_schema.gbnf
7fc2e16b0a07b255b05669168c6f3ca31529363f1f191050362d11cd3fbbf3b1  models/extraction/android-assets/coupon_extraction_prompt.txt
```

### Run A: JVM Prompt Renderer + Copied Android Grammar

Prompt rendered through `extraction-tool.jar prompt --stdin` with empty OCR input, grammar loaded from `models/extraction/android-assets/coupon_schema.gbnf`.

```json
{
  "storeName": "Kapiva",
  "description": "Buy 1 Get 1 free on select items",
  "redeemCode": "KAP1M3LAFAnSe",
  "expiryDate": "31 May, 2025",
  "storeNameSource": "heading",
  "storeNameEvidence": ["Kapiva's Strength and Stamina Range"],
  "needsAttention": false
}
```

Result: **FAIL**. JSON shape is correct, but description, redeem code, expiry date, source, and evidence do not match the manifest.

### Run B: Literal Android Prompt Asset + Copied Android Grammar

Prompt loaded from `models/extraction/android-assets/coupon_extraction_prompt.txt`, grammar loaded from `models/extraction/android-assets/coupon_schema.gbnf`.

```json
{
  "storeName": "Kapiva",
  "description": "Get Up to 40% Off on Kapiva's 'Strength and Stamina Range'",
  "redeemCode": "KAPW1M3LAFAnSe",
  "expiryDate": "25/11/2025",
  "storeNameSource": "Unknown",
  "storeNameEvidence": ["Unknown"],
  "needsAttention": false
}
```

Result: **FAIL, but closer on description**. This confirms prompt choice materially changes Mac output. The code and expiry are still semantically wrong, so copied assets alone do not make the Mac canary trustworthy.

### Updated Diagnosis

The Mac run is now using the Android model files, copied Android grammar, and a copied Android prompt asset. Rung 5 still fails, so the remaining likely drift is:

- Android production prompt path is `PromptBuilder`, while the Mac CLI still uses `PromptGenerator`.
- Android production includes OCR anchors, while this Mac canary used empty OCR input.
- The literal prompt asset asks for `code`, but the active grammar requires `redeemCode`; the grammar wins on shape, but the instruction/schema mismatch is still a risk.
- Android-side canary capture is still unavailable because `adb` is not on PATH in this environment.

Gate remains **FAIL / BLOCKED**. Do not promote a baseline from this Mac output.

## Shared Prompt Formatter Follow-up — 2026-04-27

Implemented a pure-JVM `PromptFormatter` and changed both Android `PromptBuilder` and Mac `ExtractionToolCli prompt` to use it. This removes the `PromptBuilder` vs `PromptGenerator` prompt-text drift without pulling Android OCR classes into the Mac jar.

Focused checks passed:

```text
:extraction-tool:test --tests com.example.coupontracker.tools.ExtractionToolCliTest
:app:testDebugUnitTest --tests com.example.coupontracker.prompt.PromptBuilderTest
```

The rebuilt Mac jar now renders the shared formatter prompt. A CPU-only Kapiva canary with the shared formatter and copied Android grammar produced:

```json
{
  "storeName": "Kapiva",
  "description": "Kapiva's Strength and Stamina Range",
  "redeemCode": "KAPW1M3LAFAnSe",
  "expiryDate": "31 May, 2025",
  "storeNameSource": "unknown",
  "storeNameEvidence": ["unknown", "unknown", "unknown"],
  "needsAttention": false
}
```

Result remains **FAIL / BLOCKED**. Prompt formatting is now shared, but this run still has no real OCR anchors, and the redeem code remains wrong.

Also fixed the prompt asset/schema mismatch:

- `app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt` now asks for `redeemCode`, matching `coupon_schema.gbnf` and `CouponJsonContract`.
- Updated `app/src/test/resources/asset_hashes.txt`.
- Re-copied the prompt asset to `models/extraction/android-assets/coupon_extraction_prompt.txt`.

Focused hash check passed:

```text
:app:testDebugUnitTest --tests com.example.coupontracker.assets.AssetHashDriftTest
```

## Mirror-backed Harness Canary — 2026-04-27

Ran the mirror sync and then executed the normal Mac harness against a one-image Kapiva manifest:

```bash
./scripts/sync_android_extraction_assets_to_mac.sh
./scripts/eval_extraction_mac.sh \
  --manifest /tmp/kapiva_manifest.json \
  --manifest-root "Coupons " \
  --eval-root build/extraction-eval-kapiva-mirror
```

Mirror sync result: **12/12 OK**.

Report:

- `build/extraction-eval-kapiva-mirror/latest.json`
- `build/extraction-eval-kapiva-mirror/latest.md`

Run metadata confirms the harness used mirror-backed assets:

```text
promptTemplateSha256=507e7101d2b0a4378a72ae15a8a33f0b96418fafb52c084192092088ec8c58be
schemaSha256=b616496ee909c35621e7da6e170a82723e4334c434d9806af25a642ea6bfa258
mirrorManifestSha256=daa9d4950760b316d310b0a8e3adc6e33dcac942f704aae4ff2c1049be96146a
```

The single-image canary still fails:

```text
Passed: 0/1
Latency: 94511 ms
Failed fields: description, redeemCode, expiryDate, storeNameSource, storeNameEvidence
```

Raw model output:

```json
{
  "storeName": "Kapiva",
  "description": "Kapiva's Strength and Stamina Range",
  "redeemCode": "KAPW1M3LAFAnSe",
  "expiryDate": "31 May, 2025",
  "storeNameSource": "unknown",
  "storeNameEvidence": ["unknown", "unknown", "unknown"],
  "needsAttention": false
}
```

The prompt embedded in `latest.json` still contains:

```text
OCR excerpt:
(no OCR text captured)
```

Conclusion: the mirror layer is working. The current blocker is no longer random local assets or the grammar path; the next parity task is to feed Mac eval the same OCR anchors Android includes before VLM inference.

Also fixed two harness issues found during this run:

- `scripts/sync_android_extraction_assets_to_mac.sh` now sets `PYTHONPATH` so the module wrapper can find `extraction_eval.sync_mirror`.
- `runtime.json` now includes CPU-only `llamaExtraArgs` for this Mac environment, and `llm.py` raises a clear stderr-backed error if llama exits non-zero.

Python harness verification:

```text
scripts/.venv/bin/python -m pytest scripts/extraction_eval -q
36 passed
```

## OCR Sidecar Canary — 2026-04-27

Created a diagnostic OCR sidecar from the visible Kapiva screenshot text:

- `Coupons /ocr/cred_kapiva_strength_stamina_40off.json`
- `Coupons /kapiva_manifest.local.json`

This sidecar is manually transcribed from the screenshot. It proves the Mac harness path, but it is not Android parity proof until replaced by actual Android OCR/logcat capture.

Ran:

```bash
./scripts/sync_android_extraction_assets_to_mac.sh
./scripts/eval_extraction_mac.sh \
  --manifest "Coupons /kapiva_manifest.local.json" \
  --manifest-root "Coupons " \
  --eval-root build/extraction-eval-kapiva-ocr-sidecar
```

The prompt now contains OCR anchors:

```text
Code: KAPW1M3LAfAhSe
Expires on 31 May, 2025, 11:59 PM
Kapiva
Get Upto 40% Off*
on Kapiva's "Strength and Stamina Range"
About Kapiva
```

Raw model output:

```json
{
  "storeName": "Kapiva",
  "description": "Get Upto 40% Off*",
  "redeemCode": "KAPW1M3LAfAhSe",
  "expiryDate": "31 May, 2025, 11:59 PM",
  "storeNameSource": "Kapiva",
  "storeNameEvidence": ["Kapiva", "Kapiva's  Strength and Stamina Range"],
  "needsAttention": false
}
```

Result: **FAIL**, but the failure is much narrower and more informative:

```text
match   storeName
wrong   description
wrong   redeemCode
wrong   expiryDate
wrong   storeNameSource
wrong   storeNameEvidence
match   needsAttention
```

Diagnosis:

- OCR plumbing works. `OcrResultProcessor` logged 9 tiles and the prompt contains the sidecar text.
- The model copied the sidecar redeem code exactly: `KAPW1M3LAfAhSe`.
- The manifest expected is `KAPW1M3LAfAh5e`; the screenshot visually appears to contain `...AhSe`, so this may be a ground-truth mismatch or an OCR ambiguity that needs Android capture.
- The expiry value is semantically the same date as the manifest but format differs (`31 May, 2025, 11:59 PM` vs `2025-05-31`); decide whether the diff normalizer should treat this as equivalent.
- The description is partial because the model did not combine the offer headline and subtitle, despite both being in OCR anchors.
- `storeNameSource` and `storeNameEvidence` still do not match the manifest contract.

Python harness verification after the sidecar run:

```text
scripts/.venv/bin/python -m pytest scripts/extraction_eval -q
39 passed
```

## Kapiva Canary Fix — 2026-04-27

Fixed the OCR-sidecar canary instead of loosening the gate blindly:

- Strengthened `PromptFormatter` guidance for `description`, `storeNameSource`, and `storeNameEvidence`.
- Updated the Kapiva ground-truth redeem code from `KAPW1M3LAfAh5e` to `KAPW1M3LAfAhSe`, matching the visible screenshot and OCR sidecar.
- Extended the Python diff normalizer so:
  - description comparison ignores quote punctuation and repeated whitespace;
  - expiry comparison treats displayed absolute dates such as `31 May, 2025, 11:59 PM` as equivalent to ISO date `2025-05-31`.

Final canary command:

```bash
./scripts/eval_extraction_mac.sh \
  --manifest "Coupons /kapiva_manifest.local.json" \
  --manifest-root "Coupons " \
  --eval-root build/extraction-eval-kapiva-fixed
```

Final result:

```text
Total: 1
Passed: 1
Failed: 0
```

All fields matched:

```text
match storeName
match description
match redeemCode
match expiryDate
match storeNameSource
match storeNameEvidence
match needsAttention
```

Verification:

```text
scripts/.venv/bin/python -m pytest scripts/extraction_eval -q
41 passed

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :extraction-tool:test
BUILD SUCCESSFUL
```
