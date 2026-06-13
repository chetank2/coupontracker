# Mac Extraction Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Mac-side extraction harness driven by `Coupons /manifest.json` that replaces the build → sideload → screenshot → ship-to-AI debug loop, with Android demoted to final smoke testing.

**Architecture:** A Python CLI orchestrates per-image evaluation: load manifest → preprocess image (via shared JVM core extracted from `ImagePreprocessor.kt`) → run pinned llama.cpp + Qwen GGUF → invoke shared Kotlin parser via a JVM CLI bridge → diff against `expected` → emit timestamped run reports + stable pointers + baseline diff. The Kotlin parser, prompt template, schema, and preprocessing core are single-source assets consumed by both Mac and Android; an asset-hash drift unit test fails the build if any consumer's copy diverges. Android runs only the fixed canary set + current Mac failures, with the rule that the phone wins on any disagreement.

**Tech Stack:** Python 3.11 (harness), Kotlin/JVM (shared parser + preprocessor core, exposed via fat-jar CLI), Gradle, llama.cpp (pinned commit, Mac build), Qwen GGUF + mmproj, pytest, JUnit, Android instrumentation (Layer 3 only).

**Spec:** `docs/superpowers/specs/2026-04-26-mac-extraction-harness-design.md`

**Architectural decisions locked in for this plan** (resolves spec's open questions):
- Canonical asset path: keep `app/src/main/assets/` as the single source of truth. The Mac harness reads from there directly. No `shared/extraction/` move.
- Parser invocation: JVM CLI bridge (fat jar built by Gradle), not a Python re-port. Batch mode — one invocation per run, all images on stdin, results on stdout.
- Preprocessing parity: extract `ImagePreprocessor.kt`'s logic into a new pure-JVM module that operates on `BufferedImage` / `IntArray` (no `android.graphics`). Android side becomes a thin wrapper that converts `Bitmap`↔`BufferedImage`-equivalent. The JVM CLI bridge exposes the pure core to the Mac harness.
- llama.cpp on Mac: separate build script (`scripts/build_llama_cpp_mac.sh`); pin commit and build flags in `config/extraction/runtime.json`.
- `--promote-baseline`: explicit manual flag, never automatic.

---

## File Structure

**New files (Python harness):**
- `scripts/eval_extraction_mac.sh` — top-level CLI entry point
- `scripts/extraction_eval/__init__.py`
- `scripts/extraction_eval/__main__.py` — argparse, wires the runner
- `scripts/extraction_eval/manifest.py` — load `Coupons /manifest.json`, validate, surface unannotated images
- `scripts/extraction_eval/preprocess.py` — invoke JVM bridge for preprocessing, hash output
- `scripts/extraction_eval/llm.py` — invoke pinned llama.cpp, capture raw output + latency
- `scripts/extraction_eval/parser_bridge.py` — invoke JVM bridge for parsing
- `scripts/extraction_eval/diff.py` — field comparison against `expected`
- `scripts/extraction_eval/report.py` — emit `run.json`, `run.md`, `failures.json`, stable pointers
- `scripts/extraction_eval/baseline.py` — promote/compare baseline
- `scripts/extraction_eval/runner.py` — orchestrates all of the above
- `scripts/extraction_eval/runtime_meta.py` — collect run metadata (git sha, model hashes, etc.)
- `scripts/extraction_eval/tests/__init__.py`
- `scripts/extraction_eval/tests/test_manifest.py`
- `scripts/extraction_eval/tests/test_diff.py`
- `scripts/extraction_eval/tests/test_report.py`
- `scripts/extraction_eval/tests/test_baseline.py`
- `scripts/extraction_eval/tests/test_runner.py`
- `scripts/extraction_eval/tests/fixtures/mini_manifest.json`
- `scripts/extraction_eval/tests/fixtures/mini_image.png`
- `scripts/extraction_eval/requirements.txt`

**New files (Kotlin shared core + JVM CLI):**
- `app/src/main/kotlin/com/example/coupontracker/preprocessing/ImagePreprocessorCore.kt` — pure-JVM preprocessing logic (no `android.graphics`)
- `app/src/main/kotlin/com/example/coupontracker/preprocessing/PreprocessConfig.kt` — config record (max dim, jpeg quality, color matrix params)
- `app/src/main/kotlin/com/example/coupontracker/tools/ExtractionToolCli.kt` — JVM CLI: subcommands `preprocess`, `parse`, `prompt`
- `app/src/test/kotlin/com/example/coupontracker/preprocessing/ImagePreprocessorCoreTest.kt`
- `app/src/test/kotlin/com/example/coupontracker/tools/ExtractionToolCliTest.kt`
- `app/src/test/kotlin/com/example/coupontracker/assets/AssetHashDriftTest.kt`

**New config files:**
- `config/extraction/runtime.json` — pinned llama.cpp commit, GGUF SHA-256, mmproj SHA-256, build flags
- `config/extraction/canary.json` — fixed canary set (3 image IDs)
- `scripts/build_llama_cpp_mac.sh` — Mac build of llama.cpp at pinned commit

**New Android instrumentation (Layer 3):**
- `app/src/androidTest/kotlin/com/example/coupontracker/extraction/ExtractionSmokeTest.kt`
- `app/src/androidTest/assets/canary/<3 images>` — symlinked or copied
- `app/src/androidTest/assets/canary_expected.json`

**Modified files:**
- `app/src/main/kotlin/com/example/coupontracker/util/ImagePreprocessor.kt` — refactored to delegate to `ImagePreprocessorCore`
- `app/build.gradle.kts` — add `application` plugin block for the JVM CLI fat jar; add `extractionTool` Gradle task
- `.gitignore` — ignore `build/extraction-eval/`, `build/llama_cpp_mac/`

---

## Phases & Parallelism Map

The plan runs in five phases. Within a phase, tasks marked `[parallel]` may run concurrently in separate worktrees. Tasks marked `[serial]` block downstream work in the same phase.

- **Phase 0 — Layer 0 parity canary** [serial throughout — gate]
- **Phase 1 — Layer 1 CLI harness** [tasks 1.4, 1.5, 1.6, 1.7 parallel after 1.0–1.3 set up scaffolding]
- **Phase 2 — Layer 2 shared assets** [parallel with Phase 1.5]
- **Phase 3 — Layer 1.5 baseline tracking** [parallel with Phase 2]
- **Phase 4 — Layer 3 Android smoke** [serial after Phase 1 complete]
- **Phase 5 — Pending review flow** [parallel with Phase 4]

---

## Phase 0 — Layer 0: Parity Canary

**Gate.** No Phase 1 work begins until Phase 0 confirms parity per the falsifiability ladder. If parity fails, the design is revisited before continuing.

### Task 0.1: Pin runtime config

**Files:**
- Create: `config/extraction/runtime.json`

- [ ] **Step 1: Create `config/extraction/runtime.json` with placeholders the next steps will fill**

```json
{
  "qwenGgufSha256": "PENDING",
  "qwenGgufPath": "PENDING",
  "mmprojSha256": "PENDING",
  "mmprojPath": "PENDING",
  "llamaCppCommit": "PENDING",
  "llamaCppBuildFlags": ["-DGGML_METAL=ON", "-DLLAMA_CURL=OFF"],
  "macBinary": "build/llama_cpp_mac/bin/llama-mtmd-cli",
  "promptTemplateSha256": "PENDING",
  "schemaSha256": "PENDING"
}
```

- [ ] **Step 2: Commit**

```bash
git add config/extraction/runtime.json
git commit -m "chore(extraction): scaffold runtime config for harness"
```

### Task 0.2: Locate and pin Qwen model assets

**Files:**
- Modify: `config/extraction/runtime.json`

- [ ] **Step 1: Find the GGUF + mmproj the Android app currently downloads**

Read `app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt` and `app/src/main/kotlin/com/example/coupontracker/llm/ModelAssetManager.kt`. Identify the URL, expected filename, and any existing checksum. Record both the GGUF and the mmproj URLs in your notes.

- [ ] **Step 2: Download both files to `models/extraction/` and compute SHA-256**

```bash
mkdir -p models/extraction
# Replace URLs with the ones found in step 1.
curl -L "<QWEN_GGUF_URL>" -o models/extraction/qwen.gguf
curl -L "<MMPROJ_URL>" -o models/extraction/mmproj.gguf
shasum -a 256 models/extraction/qwen.gguf models/extraction/mmproj.gguf
```

Expected: two SHA-256 hashes printed.

- [ ] **Step 3: Update `config/extraction/runtime.json` with real hashes and paths**

Replace the four `PENDING` model fields with the real values. Leave llama.cpp and template fields as `PENDING` for later tasks.

- [ ] **Step 4: Add `models/extraction/` to `.gitignore`**

```bash
echo "models/extraction/" >> .gitignore
```

- [ ] **Step 5: Commit**

```bash
git add config/extraction/runtime.json .gitignore
git commit -m "chore(extraction): pin Qwen GGUF and mmproj for harness"
```

### Task 0.3: Build llama.cpp for Mac at a pinned commit

**Files:**
- Create: `scripts/build_llama_cpp_mac.sh`
- Modify: `config/extraction/runtime.json`, `.gitignore`

- [ ] **Step 1: Pick a llama.cpp commit known to support Qwen vision**

Use the same commit referenced in `scripts/build_llama_cpp.sh` if it pins one. Otherwise pick the latest tagged release that documents Qwen2.5-VL or Qwen3-VL support. Record the full SHA.

- [ ] **Step 2: Create `scripts/build_llama_cpp_mac.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMMIT="$(jq -r .llamaCppCommit "${PROJECT_ROOT}/config/extraction/runtime.json")"
BUILD_DIR="${PROJECT_ROOT}/build/llama_cpp_mac"
SRC_DIR="${BUILD_DIR}/src"

if [ "${COMMIT}" = "PENDING" ] || [ -z "${COMMIT}" ]; then
  echo "ERROR: llamaCppCommit is PENDING in config/extraction/runtime.json" >&2
  exit 1
fi

mkdir -p "${BUILD_DIR}"
if [ ! -d "${SRC_DIR}/.git" ]; then
  git clone https://github.com/ggerganov/llama.cpp "${SRC_DIR}"
fi
git -C "${SRC_DIR}" fetch origin
git -C "${SRC_DIR}" checkout "${COMMIT}"

cmake -S "${SRC_DIR}" -B "${BUILD_DIR}/cmake" \
  -DGGML_METAL=ON -DLLAMA_CURL=OFF \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "${BUILD_DIR}/cmake" --target llama-mtmd-cli -j

ln -sf "${BUILD_DIR}/cmake/bin" "${BUILD_DIR}/bin"
echo "Built: ${BUILD_DIR}/bin/llama-mtmd-cli"
```

- [ ] **Step 3: Make it executable and update runtime.json with the commit**

```bash
chmod +x scripts/build_llama_cpp_mac.sh
# Edit config/extraction/runtime.json: replace "llamaCppCommit": "PENDING" with the chosen SHA.
```

- [ ] **Step 4: Run the build**

```bash
./scripts/build_llama_cpp_mac.sh
```

Expected: ends with `Built: …/llama-mtmd-cli` and the binary is executable.

- [ ] **Step 5: Smoke-test the binary**

```bash
build/llama_cpp_mac/bin/llama-mtmd-cli --help | head -10
```

Expected: usage message printed, exit 0.

- [ ] **Step 6: Commit**

```bash
git add scripts/build_llama_cpp_mac.sh config/extraction/runtime.json .gitignore
echo "build/llama_cpp_mac/" >> .gitignore
git commit -m "build(extraction): pinned Mac llama.cpp build for harness"
```

### Task 0.4: Capture canary image and Android raw output

**Files (no code changes):**
- Notes go in: `docs/superpowers/specs/parity-canary-notes.md` (created in this task)

- [ ] **Step 1: Pick the canary image**

From `Coupons /manifest.json`, pick `cred_kapiva_strength_stamina_40off` as the canary (any single sample works; this one has a long redeem code which is a good stress test). Note its `imageSha256` and `image` path.

- [ ] **Step 2: Run extraction on the device with verbose logging**

Build, install, and open the app. Run extraction on the canary image. Capture the device-side log:

```bash
adb logcat -d > /tmp/canary_android.log
```

- [ ] **Step 3: Extract from logcat the raw model output, the prompt sent, and the preprocessed image bytes (or hash)**

Find the lines logged by `GemmaVisionRuntime.kt` / `MlcLlmNative.kt` / `LlmTelemetryService.kt` containing the prompt and the raw model response. If preprocessed-bytes logging does not yet exist, add a one-line `Log.d` in `ImagePreprocessor` that logs `sha256(outputBytes)` (this is throwaway debug code; revert before commit).

- [ ] **Step 4: Save findings to a notes file**

Create `docs/superpowers/specs/parity-canary-notes.md` with:

```markdown
# Parity Canary — captured 2026-04-26

**Canary image:** cred_kapiva_strength_stamina_40off
**imageSha256:** 0d119d81e6fb79f4fc6d0a950759a5fea0b0c56e13a8e43797d6daa17438ac77

## Android side
- Preprocessed image SHA-256: <fill>
- Prompt text (verbatim): <fill, in a code block>
- Raw model output: <fill, in a code block>
- Parsed fields: <fill JSON>
- Latency ms: <fill>
- Build commit: <git sha at time of capture>
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/parity-canary-notes.md
git commit -m "docs(extraction): capture Android-side parity canary baseline"
```

### Task 0.5: Verify falsifiability ladder rungs 1–3 on Mac

This task does NOT yet involve the Python harness. It is a manual one-shot Mac-side reproduction.

- [ ] **Step 1: Reproduce the prompt on Mac**

Read the `app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt` template. Render it with the same OCR result the device used (or with no OCR if the device path skips OCR for the canary). Compare byte-for-byte with the prompt captured in Task 0.4.

Expected: identical. If not, identify the difference (likely OCR inputs) and reconcile.

- [ ] **Step 2: Run llama.cpp on Mac with the canary image and the same prompt**

```bash
QWEN_GGUF=$(jq -r .qwenGgufPath config/extraction/runtime.json)
MMPROJ=$(jq -r .mmprojPath config/extraction/runtime.json)
build/llama_cpp_mac/bin/llama-mtmd-cli \
  -m "$QWEN_GGUF" \
  --mmproj "$MMPROJ" \
  --image "Coupons /images/_local/cred_kapiva_strength_stamina_40off.png" \
  -p "$(cat /tmp/canary_prompt.txt)" \
  --temp 0 --seed 42 \
  -n 512 \
  > /tmp/canary_mac_raw.txt
```

- [ ] **Step 3: Apply the falsifiability ladder**

Compare in order, recording results in `docs/superpowers/specs/parity-canary-notes.md`:

1. **Preprocessed image SHA-256** — Mac vs Android. (For the canary, this requires a quick Mac script that runs the same preprocessing; if `ImagePreprocessor` is not yet portable, skip rung 1 and proceed knowing parity is unverified at that rung.)
2. **Model asset SHA-256s** — already pinned in `runtime.json`; the Android side must use the same files.
3. **Prompt text** — already compared in step 1 of this task.
4. **Raw output** — only if temp=0 and seeds match. Note discrepancies but treat as informational.
5. **Parsed fields** — run the captured raw output through the existing Kotlin parser (Task 0.4 already has the Android parsed fields). Compare `expected` vs Mac-parsed.

- [ ] **Step 4: Decision gate**

If rung 1 (when computable), rung 2, rung 3, and rung 5 all pass: **Phase 0 PASSES.** Append a `## Result: PASS` block to `parity-canary-notes.md` and proceed to Phase 1.

If any rung fails: **Phase 0 FAILS.** Append a `## Result: FAIL` block describing which rung diverged. Stop. Revisit the spec before any Phase 1 work.

- [ ] **Step 5: Commit the result**

```bash
git add docs/superpowers/specs/parity-canary-notes.md
git commit -m "docs(extraction): record Phase 0 parity canary result"
```

---

## Phase 1 — Layer 1: Mac CLI Harness

Execute Task 1.0 first (scaffolds the package). Then Tasks 1.1–1.3 in order. After 1.3, Tasks 1.4, 1.5, 1.6, 1.7 are parallelizable. Task 1.8 (orchestration) and Task 1.9 (entrypoint) close the phase serially.

### Task 1.0: Bootstrap Python package + pytest

**Files:**
- Create: `scripts/extraction_eval/__init__.py` (empty)
- Create: `scripts/extraction_eval/requirements.txt`
- Create: `scripts/extraction_eval/tests/__init__.py` (empty)
- Create: `scripts/extraction_eval/tests/test_smoke.py`

- [ ] **Step 1: Write a smoke test**

Create `scripts/extraction_eval/tests/test_smoke.py`:

```python
def test_package_importable():
    import extraction_eval  # noqa: F401
```

- [ ] **Step 2: Create `scripts/extraction_eval/requirements.txt`**

```
pytest==8.3.3
Pillow==10.4.0
jsonschema==4.23.0
```

- [ ] **Step 3: Install and run the test from the package directory**

```bash
cd scripts && python -m venv .venv && source .venv/bin/activate
pip install -r extraction_eval/requirements.txt
pytest extraction_eval/tests/test_smoke.py -v
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add scripts/extraction_eval/__init__.py scripts/extraction_eval/tests/__init__.py scripts/extraction_eval/tests/test_smoke.py scripts/extraction_eval/requirements.txt
git commit -m "feat(extraction): bootstrap Python harness package"
```

### Task 1.1: Manifest loader

**Files:**
- Create: `scripts/extraction_eval/manifest.py`
- Create: `scripts/extraction_eval/tests/test_manifest.py`
- Create: `scripts/extraction_eval/tests/fixtures/mini_manifest.json`

- [ ] **Step 1: Write a fixture**

Create `scripts/extraction_eval/tests/fixtures/mini_manifest.json`:

```json
{
  "schemaVersion": 1,
  "samples": [
    {
      "id": "sample_a",
      "image": "images/sample_a.png",
      "imageSha256": "aaaa",
      "expected": {"storeName": "Acme", "redeemCode": "ABC123", "expiryDate": "2025-12-31", "needsAttention": false}
    },
    {
      "id": "sample_b",
      "image": "images/sample_b.png",
      "imageSha256": "bbbb",
      "expected": {"storeName": "Beta", "redeemCode": "XYZ789", "expiryDate": null, "needsAttention": true}
    }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

Create `scripts/extraction_eval/tests/test_manifest.py`:

```python
from pathlib import Path
import pytest
from extraction_eval.manifest import load_manifest, Sample

FIX = Path(__file__).parent / "fixtures" / "mini_manifest.json"

def test_load_manifest_returns_samples():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    assert len(samples) == 2
    assert samples[0].id == "sample_a"
    assert samples[0].image_sha256 == "aaaa"
    assert samples[0].expected["storeName"] == "Acme"

def test_load_manifest_resolves_image_path():
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    assert samples[0].image_path == Path("/tmp/notreal/images/sample_a.png")

def test_load_manifest_rejects_unknown_schema_version(tmp_path):
    bad = tmp_path / "bad.json"
    bad.write_text('{"schemaVersion": 99, "samples": []}')
    with pytest.raises(ValueError, match="schemaVersion"):
        load_manifest(bad, root=tmp_path)

def test_load_manifest_index_by_image_sha(tmp_path):
    samples = load_manifest(FIX, root=Path("/tmp/notreal"))
    by_sha = {s.image_sha256: s for s in samples}
    assert "aaaa" in by_sha
    assert by_sha["bbbb"].id == "sample_b"
```

- [ ] **Step 3: Run tests, confirm they fail**

```bash
cd scripts && pytest extraction_eval/tests/test_manifest.py -v
```

Expected: 4 errors (`extraction_eval.manifest` not importable).

- [ ] **Step 4: Implement `manifest.py`**

Create `scripts/extraction_eval/manifest.py`:

```python
"""Load and validate Coupons /manifest.json."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from typing import Any
import json

SUPPORTED_SCHEMA_VERSIONS = {1}

@dataclass(frozen=True)
class Sample:
    id: str
    image_path: Path
    image_sha256: str
    expected: dict[str, Any]

def load_manifest(manifest_path: Path, root: Path) -> list[Sample]:
    """Load manifest.json. `root` is the directory containing the `images/` subtree."""
    data = json.loads(Path(manifest_path).read_text())
    version = data.get("schemaVersion")
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise ValueError(f"Unsupported schemaVersion: {version}")
    samples = []
    for raw in data.get("samples", []):
        samples.append(
            Sample(
                id=raw["id"],
                image_path=root / raw["image"],
                image_sha256=raw["imageSha256"],
                expected=raw["expected"],
            )
        )
    return samples
```

- [ ] **Step 5: Run tests, confirm they pass**

```bash
cd scripts && pytest extraction_eval/tests/test_manifest.py -v
```

Expected: 4 PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/extraction_eval/manifest.py scripts/extraction_eval/tests/test_manifest.py scripts/extraction_eval/tests/fixtures/mini_manifest.json
git commit -m "feat(extraction): manifest loader with imageSha256 indexing"
```

### Task 1.2: Refactor ImagePreprocessor into a pure-JVM core

This task makes the preprocessing portable so both Mac and Android use the same code.

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/preprocessing/ImagePreprocessorCore.kt`
- Create: `app/src/main/kotlin/com/example/coupontracker/preprocessing/PreprocessConfig.kt`
- Create: `app/src/test/kotlin/com/example/coupontracker/preprocessing/ImagePreprocessorCoreTest.kt`
- Modify: `app/src/main/kotlin/com/example/coupontracker/util/ImagePreprocessor.kt`

- [ ] **Step 1: Read `ImagePreprocessor.kt` end-to-end**

Identify every operation it performs (resize, color matrix, JPEG encode, etc.). The goal is to move all pixel math into `ImagePreprocessorCore` operating on `IntArray` (ARGB) and `Int` width/height — no `android.graphics` types.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/com/example/coupontracker/preprocessing/ImagePreprocessorCoreTest.kt`:

```kotlin
package com.example.coupontracker.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreprocessorCoreTest {

    @Test
    fun `resize keeps aspect ratio and respects max dimension`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(100 * 50) { 0xFF000000.toInt() }  // black 100x50
        val out = core.preprocess(src, width = 100, height = 50)
        assertEquals(out.width <= PreprocessConfig.DEFAULT.maxDimension, true)
        assertEquals(out.height <= PreprocessConfig.DEFAULT.maxDimension, true)
        // No upscaling for already-small images:
        assertEquals(100, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun `preprocess output is deterministic for identical input`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(800 * 600) { i -> (0xFF000000.toInt()) or (i and 0xFFFFFF) }
        val a = core.preprocess(src.copyOf(), 800, 600)
        val b = core.preprocess(src.copyOf(), 800, 600)
        assertEquals(a.pixels.toList(), b.pixels.toList())
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.preprocessing.ImagePreprocessorCoreTest"
```

Expected: FAIL — class not found.

- [ ] **Step 4: Implement `PreprocessConfig.kt`**

```kotlin
package com.example.coupontracker.preprocessing

data class PreprocessConfig(
    val maxDimension: Int,
    val minDimension: Int,
    val jpegQuality: Int,
) {
    companion object {
        val DEFAULT = PreprocessConfig(
            maxDimension = 1600,
            minDimension = 800,
            jpegQuality = 90,
        )
    }
}
```

- [ ] **Step 5: Implement `ImagePreprocessorCore.kt`**

```kotlin
package com.example.coupontracker.preprocessing

class ImagePreprocessorCore(private val config: PreprocessConfig) {

    data class Output(val pixels: IntArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean {
            if (other !is Output) return false
            return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
        }
        override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
    }

    fun preprocess(src: IntArray, width: Int, height: Int): Output {
        require(src.size == width * height) { "src size mismatch" }
        val (newW, newH) = targetDimensions(width, height)
        val resized = if (newW == width && newH == height) src.copyOf() else resizeBilinear(src, width, height, newW, newH)
        // (Port any color matrix / contrast operations from ImagePreprocessor.kt here.)
        return Output(resized, newW, newH)
    }

    private fun targetDimensions(w: Int, h: Int): Pair<Int, Int> {
        val maxDim = maxOf(w, h)
        if (maxDim <= config.maxDimension) return w to h
        val scale = config.maxDimension.toDouble() / maxDim
        return (w * scale).toInt() to (h * scale).toInt()
    }

    private fun resizeBilinear(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val xRatio = sw.toDouble() / dw
        val yRatio = sh.toDouble() / dh
        for (y in 0 until dh) {
            val sy = (y * yRatio).toInt().coerceIn(0, sh - 1)
            for (x in 0 until dw) {
                val sx = (x * xRatio).toInt().coerceIn(0, sw - 1)
                out[y * dw + x] = src[sy * sw + sx]
            }
        }
        return out
    }
}
```

(Note: nearest-neighbor resampling is used here as a placeholder. Audit `ImagePreprocessor.kt` for the actual algorithm and port it accurately — the parity canary will catch deviation.)

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.preprocessing.ImagePreprocessorCoreTest"
```

Expected: PASS.

- [ ] **Step 7: Refactor `ImagePreprocessor.kt` to delegate**

Replace its core resize/matrix logic with a call to `ImagePreprocessorCore.preprocess` after converting `Bitmap` → `IntArray` (using `Bitmap.getPixels`), then convert back to `Bitmap`. Preserve the public API of `ImagePreprocessor` so callers don't change.

- [ ] **Step 8: Run the full app unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/preprocessing/ \
        app/src/main/kotlin/com/example/coupontracker/util/ImagePreprocessor.kt \
        app/src/test/kotlin/com/example/coupontracker/preprocessing/
git commit -m "refactor(preprocessing): extract pure-JVM ImagePreprocessorCore"
```

### Task 1.3: JVM CLI fat jar — `extractionTool` Gradle task

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/tools/ExtractionToolCli.kt`
- Create: `app/src/test/kotlin/com/example/coupontracker/tools/ExtractionToolCliTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write the failing CLI test (preprocess subcommand)**

Create `app/src/test/kotlin/com/example/coupontracker/tools/ExtractionToolCliTest.kt`:

```kotlin
package com.example.coupontracker.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class ExtractionToolCliTest {

    @Test
    fun `preprocess subcommand emits ARGB IntArray and dimensions as JSON`() {
        // Build a 4x4 red PNG into a byte array.
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) img.setRGB(x, y, 0xFFFF0000.toInt())
        val pngBytes = ByteArrayOutputStream().apply { ImageIO.write(img, "png", this) }.toByteArray()

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("preprocess", "--stdin"),
            stdin = ByteArrayInputStream(pngBytes),
            stdout = PrintStream(out),
        )

        val json = out.toString()
        assert(json.contains("\"width\":4")) { "got: $json" }
        assert(json.contains("\"height\":4")) { "got: $json" }
        assert(json.contains("\"sha256\":")) { "got: $json" }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.tools.ExtractionToolCliTest"
```

Expected: FAIL — class not found.

- [ ] **Step 3: Implement `ExtractionToolCli.kt`**

```kotlin
package com.example.coupontracker.tools

import com.example.coupontracker.preprocessing.ImagePreprocessorCore
import com.example.coupontracker.preprocessing.PreprocessConfig
import java.io.InputStream
import java.io.PrintStream
import java.security.MessageDigest
import javax.imageio.ImageIO

object ExtractionToolCli {

    @JvmStatic
    fun main(args: Array<String>) = main(args, System.`in`, System.out)

    fun main(args: Array<String>, stdin: InputStream, stdout: PrintStream) {
        when (args.firstOrNull()) {
            "preprocess" -> preprocess(stdin, stdout)
            "parse" -> parse(stdin, stdout)
            "prompt" -> renderPrompt(stdin, stdout)
            else -> {
                stdout.println("usage: ExtractionToolCli {preprocess|parse|prompt} [--stdin]")
                throw IllegalArgumentException("unknown subcommand: ${args.firstOrNull()}")
            }
        }
    }

    private fun preprocess(stdin: InputStream, stdout: PrintStream) {
        val img = ImageIO.read(stdin) ?: error("could not decode image from stdin")
        val w = img.width; val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        val out = ImagePreprocessorCore(PreprocessConfig.DEFAULT).preprocess(pixels, w, h)
        val sha = sha256OfPixels(out.pixels)
        stdout.println("""{"width":${out.width},"height":${out.height},"sha256":"$sha"}""")
    }

    private fun parse(stdin: InputStream, stdout: PrintStream) {
        // Wired in Task 1.5.
        TODO("parse subcommand not yet implemented")
    }

    private fun renderPrompt(stdin: InputStream, stdout: PrintStream) {
        // Wired in Task 1.5.
        TODO("prompt subcommand not yet implemented")
    }

    private fun sha256OfPixels(pixels: IntArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(pixels.size * 4)
        for (p in pixels) buf.putInt(p)
        md.update(buf.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Add the `extractionTool` task to `app/build.gradle.kts`**

Add in the appropriate place (near other custom tasks):

```kotlin
tasks.register<Jar>("extractionToolJar") {
    archiveBaseName.set("extraction-tool")
    manifest { attributes["Main-Class"] = "com.example.coupontracker.tools.ExtractionToolCli" }
    val main = sourceSets.named("main").get()
    from(main.output)
    dependsOn(configurations.named("runtimeClasspath"))
    from({ configurations.named("runtimeClasspath").get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.tools.ExtractionToolCliTest"
```

Expected: PASS.

- [ ] **Step 6: Build the fat jar**

```bash
./gradlew :app:extractionToolJar
ls -la app/build/libs/extraction-tool*.jar
```

Expected: jar present.

- [ ] **Step 7: Smoke-run the fat jar**

```bash
java -jar app/build/libs/extraction-tool.jar preprocess --stdin < "Coupons /images/_local/cred_kapiva_strength_stamina_40off.png"
```

Expected: a JSON line with `width`, `height`, `sha256`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/tools/ \
        app/src/test/kotlin/com/example/coupontracker/tools/ \
        app/build.gradle.kts
git commit -m "feat(tools): JVM CLI fat jar with preprocess subcommand"
```

### Task 1.4 [parallel]: Python preprocess module (calls JVM CLI)

**Files:**
- Create: `scripts/extraction_eval/preprocess.py`
- Create: `scripts/extraction_eval/tests/test_preprocess.py`

- [ ] **Step 1: Write the failing test**

Create `scripts/extraction_eval/tests/test_preprocess.py`:

```python
from pathlib import Path
from unittest.mock import patch
from extraction_eval.preprocess import PreprocessResult, run_preprocess

def test_run_preprocess_parses_jvm_output():
    sample_image = Path(__file__).parent / "fixtures" / "mini_image.png"
    fake_json = b'{"width":800,"height":600,"sha256":"abc123"}'
    with patch("extraction_eval.preprocess._invoke_jvm", return_value=fake_json):
        result = run_preprocess(sample_image, jar="/x/y.jar")
    assert isinstance(result, PreprocessResult)
    assert result.width == 800
    assert result.height == 600
    assert result.sha256 == "abc123"
```

- [ ] **Step 2: Create `scripts/extraction_eval/tests/fixtures/mini_image.png`**

Any small valid PNG (e.g., generate with PIL):

```bash
python -c "from PIL import Image; Image.new('RGB',(4,4),'red').save('scripts/extraction_eval/tests/fixtures/mini_image.png')"
```

- [ ] **Step 3: Run test, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_preprocess.py -v
```

Expected: FAIL — module not importable.

- [ ] **Step 4: Implement `preprocess.py`**

```python
"""Invoke the JVM extraction-tool CLI to preprocess an image."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json
import subprocess

@dataclass(frozen=True)
class PreprocessResult:
    width: int
    height: int
    sha256: str

def _invoke_jvm(jar: str, image_path: Path) -> bytes:
    with image_path.open("rb") as f:
        return subprocess.check_output(
            ["java", "-jar", jar, "preprocess", "--stdin"],
            stdin=f,
        )

def run_preprocess(image_path: Path, *, jar: str) -> PreprocessResult:
    raw = _invoke_jvm(jar, image_path)
    data = json.loads(raw)
    return PreprocessResult(
        width=int(data["width"]),
        height=int(data["height"]),
        sha256=str(data["sha256"]),
    )
```

- [ ] **Step 5: Run test, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_preprocess.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/extraction_eval/preprocess.py scripts/extraction_eval/tests/test_preprocess.py scripts/extraction_eval/tests/fixtures/mini_image.png
git commit -m "feat(extraction): preprocess module bridging to JVM CLI"
```

### Task 1.5: Wire `parse` and `prompt` subcommands in JVM CLI + Python bridge

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/tools/ExtractionToolCli.kt`
- Modify: `app/src/test/kotlin/com/example/coupontracker/tools/ExtractionToolCliTest.kt`
- Create: `scripts/extraction_eval/parser_bridge.py`
- Create: `scripts/extraction_eval/tests/test_parser_bridge.py`

- [ ] **Step 1: Identify the parser entry point**

Read `app/src/main/kotlin/com/example/coupontracker/llm/` and the `extraction/` package. Find the function that converts a raw model output string to the structured `CouponInfo` (or the Map equivalent). Note its package and signature.

- [ ] **Step 2: Identify the prompt entry point**

`PromptBuilder.kt`'s `Result` (`prompt: String`, `systemPrompt: String`, `userPrompt: String`, `assistantPrimer: String`) is the target. Identify the constructor inputs: `OcrResultProcessor.ProcessedOcrResult` or raw OCR text.

- [ ] **Step 3: Add JUnit tests for `parse` and `prompt` subcommands**

Append to `ExtractionToolCliTest.kt`:

```kotlin
@Test
fun `parse subcommand returns structured JSON for known model output`() {
    val raw = """{"storeName":"Acme","redeemCode":"X1","expiryDate":"2025-01-01","needsAttention":false}"""
    val out = ByteArrayOutputStream()
    ExtractionToolCli.main(
        args = arrayOf("parse", "--stdin"),
        stdin = ByteArrayInputStream(raw.toByteArray()),
        stdout = PrintStream(out),
    )
    val parsed = out.toString()
    assert(parsed.contains("\"storeName\":\"Acme\"")) { "got: $parsed" }
}

@Test
fun `prompt subcommand renders deterministic text from given OCR JSON`() {
    val ocrJson = """{"text":"GET 40% OFF on Acme","tiles":[]}"""
    val out = ByteArrayOutputStream()
    ExtractionToolCli.main(
        args = arrayOf("prompt", "--stdin"),
        stdin = ByteArrayInputStream(ocrJson.toByteArray()),
        stdout = PrintStream(out),
    )
    val prompt = out.toString()
    assert(prompt.isNotBlank())
    assert(prompt.contains("Acme"))
}
```

- [ ] **Step 4: Run the tests, confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.tools.ExtractionToolCliTest"
```

Expected: 2 new tests fail (TODO not implemented).

- [ ] **Step 5: Implement `parse` and `prompt`**

In `ExtractionToolCli.kt`, replace the two `TODO` bodies with calls into the existing parser and `PromptBuilder` from steps 1–2. Keep the wire format (stdin: input JSON, stdout: result JSON) consistent.

- [ ] **Step 6: Run all CLI tests, confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.tools.ExtractionToolCliTest"
```

Expected: all PASS.

- [ ] **Step 7: Add Python bridge `parser_bridge.py`**

```python
"""Invoke the JVM extraction-tool CLI for parse and prompt."""
from __future__ import annotations
from pathlib import Path
import json
import subprocess

def parse_model_output(raw: str, *, jar: str) -> dict:
    out = subprocess.check_output(
        ["java", "-jar", jar, "parse", "--stdin"],
        input=raw.encode("utf-8"),
    )
    return json.loads(out)

def render_prompt(ocr_json: dict, *, jar: str) -> str:
    out = subprocess.check_output(
        ["java", "-jar", jar, "prompt", "--stdin"],
        input=json.dumps(ocr_json).encode("utf-8"),
    )
    return out.decode("utf-8")
```

- [ ] **Step 8: Add a Python test that mocks `subprocess.check_output`**

Create `scripts/extraction_eval/tests/test_parser_bridge.py`:

```python
from unittest.mock import patch
from extraction_eval.parser_bridge import parse_model_output, render_prompt

def test_parse_model_output_returns_dict():
    fake = b'{"storeName":"Acme"}'
    with patch("extraction_eval.parser_bridge.subprocess.check_output", return_value=fake):
        assert parse_model_output("ignored", jar="/x.jar") == {"storeName": "Acme"}

def test_render_prompt_returns_string():
    fake = b"PROMPT TEXT"
    with patch("extraction_eval.parser_bridge.subprocess.check_output", return_value=fake):
        assert render_prompt({}, jar="/x.jar") == "PROMPT TEXT"
```

- [ ] **Step 9: Run tests, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_parser_bridge.py -v
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/tools/ExtractionToolCli.kt \
        app/src/test/kotlin/com/example/coupontracker/tools/ExtractionToolCliTest.kt \
        scripts/extraction_eval/parser_bridge.py \
        scripts/extraction_eval/tests/test_parser_bridge.py
git commit -m "feat(tools): wire parse and prompt subcommands + Python bridge"
```

### Task 1.6 [parallel with 1.4]: LLM runner module

**Files:**
- Create: `scripts/extraction_eval/llm.py`
- Create: `scripts/extraction_eval/tests/test_llm.py`

- [ ] **Step 1: Write the failing test**

```python
from pathlib import Path
from unittest.mock import patch
from extraction_eval.llm import LlmResult, run_llm

def test_run_llm_invokes_binary_and_captures_output():
    fake_stdout = b"MODEL OUTPUT TEXT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
        mock_run.return_value.returncode = 0
        result = run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
        )
    assert isinstance(result, LlmResult)
    assert result.raw == "MODEL OUTPUT TEXT"
    assert result.latency_ms >= 0
```

Save as `scripts/extraction_eval/tests/test_llm.py`.

- [ ] **Step 2: Run test, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_llm.py -v
```

Expected: FAIL.

- [ ] **Step 3: Implement `llm.py`**

```python
"""Run pinned llama.cpp on a single image + prompt and capture raw output."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import subprocess
import time

@dataclass(frozen=True)
class LlmResult:
    raw: str
    latency_ms: int
    return_code: int

def run_llm(
    *,
    binary: str,
    gguf: str,
    mmproj: str,
    image: Path,
    prompt: str,
    temp: float = 0.0,
    seed: int = 42,
    n_predict: int = 512,
) -> LlmResult:
    start = time.monotonic()
    proc = subprocess.run(
        [
            binary,
            "-m", gguf,
            "--mmproj", mmproj,
            "--image", str(image),
            "-p", prompt,
            "--temp", str(temp),
            "--seed", str(seed),
            "-n", str(n_predict),
        ],
        capture_output=True,
        check=False,
    )
    elapsed_ms = int((time.monotonic() - start) * 1000)
    return LlmResult(
        raw=proc.stdout.decode("utf-8", errors="replace"),
        latency_ms=elapsed_ms,
        return_code=proc.returncode,
    )
```

- [ ] **Step 4: Run test, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_llm.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/extraction_eval/llm.py scripts/extraction_eval/tests/test_llm.py
git commit -m "feat(extraction): llama.cpp runner module"
```

### Task 1.7 [parallel with 1.4, 1.6]: Field diff module

**Files:**
- Create: `scripts/extraction_eval/diff.py`
- Create: `scripts/extraction_eval/tests/test_diff.py`

- [ ] **Step 1: Write the failing tests**

```python
from extraction_eval.diff import compare_fields, FieldStatus

def test_match_when_normalized_values_equal():
    diff = compare_fields(
        expected={"storeName": "Kapiva", "redeemCode": "ABC123", "needsAttention": False},
        got={"storeName": "kapiva", "redeemCode": "ABC123", "needsAttention": False},
    )
    statuses = {d.field: d.status for d in diff}
    assert statuses["storeName"] == FieldStatus.MATCH
    assert statuses["redeemCode"] == FieldStatus.MATCH
    assert statuses["needsAttention"] == FieldStatus.MATCH

def test_missing_when_field_absent_in_got():
    diff = compare_fields(expected={"redeemCode": "X"}, got={})
    assert diff[0].field == "redeemCode"
    assert diff[0].status == FieldStatus.MISSING

def test_wrong_when_values_differ():
    diff = compare_fields(expected={"redeemCode": "X"}, got={"redeemCode": "Y"})
    assert diff[0].status == FieldStatus.WRONG

def test_extra_field_reported():
    diff = compare_fields(expected={}, got={"storeName": "X"})
    assert diff[0].field == "storeName"
    assert diff[0].status == FieldStatus.EXTRA
```

Save as `scripts/extraction_eval/tests/test_diff.py`.

- [ ] **Step 2: Run, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_diff.py -v
```

Expected: FAIL.

- [ ] **Step 3: Implement `diff.py`**

```python
"""Compare extraction output against expected fields."""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
from typing import Any

class FieldStatus(str, Enum):
    MATCH = "match"
    MISSING = "missing"
    WRONG = "wrong"
    EXTRA = "extra"

@dataclass(frozen=True)
class FieldDiff:
    field: str
    expected: Any
    got: Any
    status: FieldStatus

def _normalize(v: Any) -> Any:
    if isinstance(v, str):
        return v.strip().lower()
    return v

def compare_fields(*, expected: dict, got: dict) -> list[FieldDiff]:
    out: list[FieldDiff] = []
    for k, v in expected.items():
        if k not in got:
            out.append(FieldDiff(k, v, None, FieldStatus.MISSING))
        elif _normalize(got[k]) == _normalize(v):
            out.append(FieldDiff(k, v, got[k], FieldStatus.MATCH))
        else:
            out.append(FieldDiff(k, v, got[k], FieldStatus.WRONG))
    for k, v in got.items():
        if k not in expected:
            out.append(FieldDiff(k, None, v, FieldStatus.EXTRA))
    return out
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_diff.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/extraction_eval/diff.py scripts/extraction_eval/tests/test_diff.py
git commit -m "feat(extraction): field diff module with normalization"
```

### Task 1.8: Report writers + stable pointers

**Files:**
- Create: `scripts/extraction_eval/report.py`
- Create: `scripts/extraction_eval/runtime_meta.py`
- Create: `scripts/extraction_eval/tests/test_report.py`

- [ ] **Step 1: Write the failing tests**

```python
import json
from pathlib import Path
from extraction_eval.diff import FieldDiff, FieldStatus
from extraction_eval.report import write_run, RunResult, SampleResult

def make_sample(passed: bool) -> SampleResult:
    return SampleResult(
        id="sample_a",
        image_sha256="aaaa",
        image_path="images/sample_a.png",
        expected={"redeemCode": "X"},
        prompt_text="P",
        raw_model_output="O",
        parsed={"redeemCode": "X" if passed else "Y"},
        preprocessed_image_sha256="hhhh",
        latency_ms=123,
        field_diff=[FieldDiff("redeemCode", "X", "X" if passed else "Y",
                              FieldStatus.MATCH if passed else FieldStatus.WRONG)],
        passed=passed,
    )

def test_write_run_emits_run_json_and_md_and_failures(tmp_path):
    run = RunResult(
        timestamp="2026-04-26T20-00-00Z",
        run_meta={"git": "deadbeef"},
        samples=[make_sample(True), make_sample(False)],
    )
    write_run(run, eval_root=tmp_path)
    runs = sorted((tmp_path / "runs").iterdir())
    assert len(runs) == 1
    assert (runs[0] / "run.json").exists()
    assert (runs[0] / "run.md").exists()
    latest = json.loads((tmp_path / "latest.json").read_text())
    assert latest["timestamp"] == "2026-04-26T20-00-00Z"
    failures = json.loads((tmp_path / "failures.json").read_text())
    assert len(failures["samples"]) == 1
    assert failures["samples"][0]["passed"] is False
```

Save as `scripts/extraction_eval/tests/test_report.py`.

- [ ] **Step 2: Run, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_report.py -v
```

Expected: FAIL.

- [ ] **Step 3: Implement `runtime_meta.py`**

```python
"""Collect run-level metadata for reproducibility."""
from __future__ import annotations
from pathlib import Path
import hashlib
import json
import platform
import subprocess

def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def collect_meta(*, runtime_config_path: Path, repo_root: Path) -> dict:
    cfg = json.loads(runtime_config_path.read_text())
    git_sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repo_root
    ).decode().strip()
    return {
        "gitSha": git_sha,
        "host": f"{platform.system().lower()}-{platform.machine()}",
        "qwenGgufSha256": cfg["qwenGgufSha256"],
        "mmprojSha256": cfg["mmprojSha256"],
        "llamaCppCommit": cfg["llamaCppCommit"],
        "llamaCppBuildFlags": cfg["llamaCppBuildFlags"],
        "promptTemplateSha256": cfg.get("promptTemplateSha256"),
        "schemaSha256": cfg.get("schemaSha256"),
    }
```

- [ ] **Step 4: Implement `report.py`**

```python
"""Write run.json, run.md, latest.* pointers, and failures.json."""
from __future__ import annotations
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any
import json
import shutil

from extraction_eval.diff import FieldDiff

@dataclass(frozen=True)
class SampleResult:
    id: str
    image_sha256: str
    image_path: str
    expected: dict
    prompt_text: str
    raw_model_output: str
    parsed: dict
    preprocessed_image_sha256: str
    latency_ms: int
    field_diff: list[FieldDiff]
    passed: bool

@dataclass(frozen=True)
class RunResult:
    timestamp: str
    run_meta: dict
    samples: list[SampleResult]

def _sample_to_dict(s: SampleResult) -> dict:
    return {
        **{k: v for k, v in asdict(s).items() if k != "field_diff"},
        "field_diff": [{"field": d.field, "expected": d.expected, "got": d.got, "status": d.status.value} for d in s.field_diff],
    }

def _run_to_dict(r: RunResult) -> dict:
    return {"timestamp": r.timestamp, "run_meta": r.run_meta, "samples": [_sample_to_dict(s) for s in r.samples]}

def _render_md(r: RunResult) -> str:
    lines = [
        f"# Extraction Eval — {r.timestamp}",
        "",
        f"- Total: {len(r.samples)}",
        f"- Passed: {sum(1 for s in r.samples if s.passed)}",
        f"- Failed: {sum(1 for s in r.samples if not s.passed)}",
        "",
        "| Sample | Pass | Latency ms | Failed fields |",
        "|---|---|---|---|",
    ]
    for s in r.samples:
        failed = [d.field for d in s.field_diff if d.status.value != "match"]
        lines.append(f"| {s.id} | {'✅' if s.passed else '❌'} | {s.latency_ms} | {', '.join(failed) if failed else '-'} |")
    return "\n".join(lines) + "\n"

def write_run(run: RunResult, *, eval_root: Path) -> Path:
    runs_dir = eval_root / "runs" / run.timestamp
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_json = runs_dir / "run.json"
    run_md = runs_dir / "run.md"
    run_json.write_text(json.dumps(_run_to_dict(run), indent=2))
    run_md.write_text(_render_md(run))
    shutil.copyfile(run_json, eval_root / "latest.json")
    shutil.copyfile(run_md, eval_root / "latest.md")
    failures = RunResult(
        timestamp=run.timestamp,
        run_meta=run.run_meta,
        samples=[s for s in run.samples if not s.passed],
    )
    (eval_root / "failures.json").write_text(json.dumps(_run_to_dict(failures), indent=2))
    return runs_dir
```

- [ ] **Step 5: Run, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_report.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/extraction_eval/report.py scripts/extraction_eval/runtime_meta.py scripts/extraction_eval/tests/test_report.py
git commit -m "feat(extraction): report writers, stable pointers, failures.json"
```

### Task 1.9: Runner orchestration

**Files:**
- Create: `scripts/extraction_eval/runner.py`
- Create: `scripts/extraction_eval/tests/test_runner.py`

- [ ] **Step 1: Write the failing test**

```python
from pathlib import Path
from unittest.mock import patch, MagicMock
from extraction_eval.runner import run_eval

def test_run_eval_invokes_pipeline_per_sample(tmp_path):
    manifest = tmp_path / "manifest.json"
    manifest.write_text('''{"schemaVersion":1,"samples":[
        {"id":"a","image":"a.png","imageSha256":"sha","expected":{"redeemCode":"X"}}
    ]}''')
    (tmp_path / "a.png").write_bytes(b"\x89PNG\r\n\x1a\n")
    eval_root = tmp_path / "eval"

    with patch("extraction_eval.runner.run_preprocess") as pre, \
         patch("extraction_eval.runner.render_prompt", return_value="P"), \
         patch("extraction_eval.runner.run_llm") as llm, \
         patch("extraction_eval.runner.parse_model_output", return_value={"redeemCode":"X"}), \
         patch("extraction_eval.runner.collect_meta", return_value={"gitSha":"deadbeef"}):
        pre.return_value = MagicMock(width=4, height=4, sha256="hh")
        llm.return_value = MagicMock(raw="O", latency_ms=10, return_code=0)
        run_eval(
            manifest_path=manifest,
            manifest_root=tmp_path,
            eval_root=eval_root,
            jar="/jar.jar",
            runtime_config_path=tmp_path / "runtime.json",
        )

    assert (eval_root / "latest.json").exists()
    assert (eval_root / "failures.json").exists()
```

Save as `scripts/extraction_eval/tests/test_runner.py`. Note: this test patches `collect_meta` to avoid needing a real `runtime.json`; create one so reading it never happens by also patching `runtime_meta` path access if needed. If the patch alone is insufficient, also write a minimal valid `runtime.json` to `tmp_path`.

- [ ] **Step 2: Run, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_runner.py -v
```

Expected: FAIL — runner not implemented.

- [ ] **Step 3: Implement `runner.py`**

```python
"""Top-level orchestration: load manifest, run pipeline per sample, write reports."""
from __future__ import annotations
from datetime import datetime, timezone
from pathlib import Path

from extraction_eval.diff import FieldStatus, compare_fields
from extraction_eval.llm import run_llm
from extraction_eval.manifest import load_manifest
from extraction_eval.parser_bridge import parse_model_output, render_prompt
from extraction_eval.preprocess import run_preprocess
from extraction_eval.report import RunResult, SampleResult, write_run
from extraction_eval.runtime_meta import collect_meta

def run_eval(
    *,
    manifest_path: Path,
    manifest_root: Path,
    eval_root: Path,
    jar: str,
    runtime_config_path: Path,
    binary: str,
    gguf: str,
    mmproj: str,
) -> Path:
    samples = load_manifest(manifest_path, root=manifest_root)
    meta = collect_meta(runtime_config_path=runtime_config_path, repo_root=Path.cwd())
    results: list[SampleResult] = []
    for s in samples:
        pre = run_preprocess(s.image_path, jar=jar)
        prompt = render_prompt({"text": "", "tiles": []}, jar=jar)
        llm = run_llm(
            binary=binary,
            gguf=gguf,
            mmproj=mmproj,
            image=s.image_path,
            prompt=prompt,
        )
        parsed = parse_model_output(llm.raw, jar=jar)
        diffs = compare_fields(expected=s.expected, got=parsed)
        passed = all(d.status == FieldStatus.MATCH for d in diffs if d.field in s.expected)
        results.append(SampleResult(
            id=s.id,
            image_sha256=s.image_sha256,
            image_path=str(s.image_path),
            expected=s.expected,
            prompt_text=prompt,
            raw_model_output=llm.raw,
            parsed=parsed,
            preprocessed_image_sha256=pre.sha256,
            latency_ms=llm.latency_ms,
            field_diff=diffs,
            passed=passed,
        ))
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    return write_run(RunResult(timestamp=timestamp, run_meta=meta, samples=results), eval_root=eval_root)
```

(`binary`, `gguf`, and `mmproj` are required keyword args — the caller in Task 1.10 reads them from `runtime.json` and passes file paths, never hashes.)

- [ ] **Step 4: Run, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_runner.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/extraction_eval/runner.py scripts/extraction_eval/tests/test_runner.py
git commit -m "feat(extraction): top-level runner orchestration"
```

### Task 1.10: CLI entrypoint + shell wrapper

**Files:**
- Create: `scripts/extraction_eval/__main__.py`
- Create: `scripts/eval_extraction_mac.sh`

- [ ] **Step 1: Implement `__main__.py`**

```python
"""argparse entrypoint: python -m extraction_eval"""
from __future__ import annotations
import argparse
import json
from pathlib import Path

from extraction_eval.runner import run_eval

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="extraction_eval")
    p.add_argument("--manifest", default="Coupons /manifest.json")
    p.add_argument("--manifest-root", default="Coupons ")
    p.add_argument("--eval-root", default="build/extraction-eval")
    p.add_argument("--jar", required=True)
    p.add_argument("--runtime-config", default="config/extraction/runtime.json")
    args = p.parse_args(argv)

    cfg = json.loads(Path(args.runtime_config).read_text())
    out = run_eval(
        manifest_path=Path(args.manifest),
        manifest_root=Path(args.manifest_root),
        eval_root=Path(args.eval_root),
        jar=args.jar,
        runtime_config_path=Path(args.runtime_config),
        binary=cfg["macBinary"],
        gguf=cfg["qwenGgufPath"],
        mmproj=cfg["mmprojPath"],
    )
    print(f"Run written to {out}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Implement `scripts/eval_extraction_mac.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

JAR="${PROJECT_ROOT}/app/build/libs/extraction-tool.jar"
if [ ! -f "${JAR}" ]; then
  ./gradlew :app:extractionToolJar
fi

if [ ! -f "build/llama_cpp_mac/bin/llama-mtmd-cli" ]; then
  ./scripts/build_llama_cpp_mac.sh
fi

source "${PROJECT_ROOT}/scripts/.venv/bin/activate" 2>/dev/null || {
  python3 -m venv "${PROJECT_ROOT}/scripts/.venv"
  source "${PROJECT_ROOT}/scripts/.venv/bin/activate"
  pip install -r "${PROJECT_ROOT}/scripts/extraction_eval/requirements.txt"
}

cd "${PROJECT_ROOT}/scripts"
python -m extraction_eval --jar "${JAR}" "$@"
```

- [ ] **Step 3: Make it executable, run an end-to-end smoke**

```bash
chmod +x scripts/eval_extraction_mac.sh
./scripts/eval_extraction_mac.sh
ls -la build/extraction-eval/
```

Expected: `latest.json`, `latest.md`, `failures.json` present; a directory under `runs/`.

- [ ] **Step 4: Add `build/extraction-eval/` to `.gitignore`**

```bash
echo "build/extraction-eval/" >> .gitignore
```

- [ ] **Step 5: Commit**

```bash
git add scripts/extraction_eval/__main__.py scripts/eval_extraction_mac.sh .gitignore
git commit -m "feat(extraction): one-command Mac eval entrypoint"
```

---

## Phase 2 — Layer 2: Shared Asset Enforcement [parallel with Phase 3]

### Task 2.1: Asset hash drift unit test

**Files:**
- Create: `app/src/test/kotlin/com/example/coupontracker/assets/AssetHashDriftTest.kt`

- [ ] **Step 1: Identify the asset files that must not drift**

List:
- `app/src/main/assets/coupon_schema.gbnf`
- `app/src/main/assets/coupon_model.json`
- `app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt`

(Audit `app/src/main/assets/` for any other shared files.)

- [ ] **Step 2: Write the failing test**

```kotlin
package com.example.coupontracker.assets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pin the SHA-256 of every file the Mac harness reads. If anyone changes one
 * of these without updating the pinned hash, the build breaks. Pinned hashes
 * live next to this test in golden_hashes.txt.
 */
class AssetHashDriftTest {

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `pinned asset hashes match`() {
        val golden = File("src/test/resources/asset_hashes.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.split("  ", limit = 2).let { (h, p) -> p to h } }
        for ((path, expected) in golden) {
            val f = File("src/main/$path")
            val actual = sha256(f)
            assertEquals("Asset $path drifted from pinned hash", expected, actual)
        }
    }
}
```

- [ ] **Step 3: Pin the current hashes**

```bash
mkdir -p app/src/test/resources
{
  for f in \
    assets/coupon_schema.gbnf \
    assets/coupon_model.json \
    assets/prompt_templates/coupon_extraction_prompt.txt \
  ; do
    h=$(shasum -a 256 "app/src/main/$f" | awk '{print $1}')
    echo "$h  $f"
  done
} > app/src/test/resources/asset_hashes.txt
```

- [ ] **Step 4: Update `runtime.json` with the same hashes**

Update `config/extraction/runtime.json`:
- `promptTemplateSha256` ← hash of `coupon_extraction_prompt.txt`
- `schemaSha256` ← hash of `coupon_schema.gbnf`

- [ ] **Step 5: Run the test, confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.coupontracker.assets.AssetHashDriftTest"
```

Expected: PASS.

- [ ] **Step 6: Confirm the test breaks on drift**

Append a space to `app/src/main/assets/coupon_model.json`, rerun the test, confirm FAIL. Revert.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/kotlin/com/example/coupontracker/assets/AssetHashDriftTest.kt \
        app/src/test/resources/asset_hashes.txt \
        config/extraction/runtime.json
git commit -m "test(assets): pin SHA-256 of shared extraction assets"
```

---

## Phase 3 — Layer 1.5: Baseline Tracking [parallel with Phase 2]

### Task 3.1: Baseline diff + per-field accuracy

**Files:**
- Create: `scripts/extraction_eval/baseline.py`
- Create: `scripts/extraction_eval/tests/test_baseline.py`
- Modify: `scripts/extraction_eval/report.py` (add accuracy + drift sections to run.md)
- Modify: `scripts/extraction_eval/__main__.py` (add `--promote-baseline` flag)

- [ ] **Step 1: Write the failing tests**

```python
import json
from pathlib import Path
from extraction_eval.baseline import diff_against_baseline, ChangedField

def write_run(p: Path, samples):
    p.write_text(json.dumps({"timestamp": "T", "run_meta": {}, "samples": samples}))

def test_diff_against_baseline_finds_changed_field(tmp_path):
    base = tmp_path / "baseline.json"
    latest = tmp_path / "latest.json"
    write_run(base, [{"id": "a", "image_sha256": "h", "parsed": {"redeemCode": "X"}, "passed": True, "field_diff": []}])
    write_run(latest, [{"id": "a", "image_sha256": "h", "parsed": {"redeemCode": "Y"}, "passed": False, "field_diff": []}])
    changes = diff_against_baseline(latest=latest, baseline=base)
    assert any(c.id == "a" and c.field == "redeemCode" for c in changes)

def test_per_field_accuracy(tmp_path):
    from extraction_eval.baseline import per_field_accuracy
    samples = [
        {"field_diff": [{"field": "redeemCode", "status": "match"}, {"field": "expiryDate", "status": "wrong"}]},
        {"field_diff": [{"field": "redeemCode", "status": "match"}, {"field": "expiryDate", "status": "match"}]},
    ]
    acc = per_field_accuracy(samples)
    assert acc["redeemCode"] == (2, 2)
    assert acc["expiryDate"] == (1, 2)
```

Save as `scripts/extraction_eval/tests/test_baseline.py`.

- [ ] **Step 2: Run, confirm fail**

```bash
cd scripts && pytest extraction_eval/tests/test_baseline.py -v
```

Expected: FAIL.

- [ ] **Step 3: Implement `baseline.py`**

```python
"""Baseline persistence and drift detection."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json

@dataclass(frozen=True)
class ChangedField:
    id: str
    field: str
    baseline: object
    latest: object

def diff_against_baseline(*, latest: Path, baseline: Path) -> list[ChangedField]:
    if not baseline.exists():
        return []
    base = {s["id"]: s for s in json.loads(baseline.read_text())["samples"]}
    out: list[ChangedField] = []
    for s in json.loads(latest.read_text())["samples"]:
        if s["id"] not in base:
            continue
        old = base[s["id"]].get("parsed", {})
        new = s.get("parsed", {})
        for k in set(old) | set(new):
            if old.get(k) != new.get(k):
                out.append(ChangedField(s["id"], k, old.get(k), new.get(k)))
    return out

def per_field_accuracy(samples: list[dict]) -> dict[str, tuple[int, int]]:
    counts: dict[str, list[int]] = {}
    for s in samples:
        for d in s.get("field_diff", []):
            f = d["field"]
            ok = 1 if d["status"] == "match" else 0
            slot = counts.setdefault(f, [0, 0])
            slot[0] += ok
            slot[1] += 1
    return {k: (v[0], v[1]) for k, v in counts.items()}

def promote_baseline(*, latest: Path, baseline: Path) -> None:
    baseline.write_text(latest.read_text())
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_baseline.py -v
```

Expected: PASS.

- [ ] **Step 5: Wire `--promote-baseline` into `__main__.py`**

In `__main__.py`'s argparse block, add:

```python
p.add_argument("--promote-baseline", action="store_true",
               help="After this run, copy latest.json to baseline.json.")
```

After `run_eval` returns, if `args.promote_baseline`:
```python
from extraction_eval.baseline import promote_baseline
promote_baseline(latest=Path(args.eval_root) / "latest.json",
                 baseline=Path(args.eval_root) / "baseline.json")
```

- [ ] **Step 6: Extend `report.py` to render accuracy + drift in `run.md`**

In `_render_md`, after the per-sample table, append a section that lists `per_field_accuracy(...)` results and (if `baseline.json` exists) a `## Changed since baseline` section listing each `ChangedField`. Add unit-test coverage for the new sections in `test_report.py`.

- [ ] **Step 7: Run all harness tests**

```bash
cd scripts && pytest extraction_eval -v
```

Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add scripts/extraction_eval/baseline.py scripts/extraction_eval/tests/test_baseline.py \
        scripts/extraction_eval/report.py scripts/extraction_eval/__main__.py \
        scripts/extraction_eval/tests/test_report.py
git commit -m "feat(extraction): baseline drift detection and per-field accuracy"
```

---

## Phase 4 — Layer 3: Android Smoke

### Task 4.1: Define canary set

**Files:**
- Create: `config/extraction/canary.json`
- Create: `app/src/androidTest/assets/canary/` (3 image files copied from `Coupons /images/_local/`)
- Create: `app/src/androidTest/assets/canary_expected.json`

- [ ] **Step 1: Pick 3 canary samples**

From `Coupons /manifest.json`, pick 3 IDs that span the difficulty spectrum: one easy (clear store + short code), one with a long code, one without an `expiryDate`. Suggested:
- `cred_kapiva_strength_stamina_40off`
- `cred_lenskart_goldmax_1year_free`
- one with `expiryDate: null` from the manifest (find one)

- [ ] **Step 2: Create `config/extraction/canary.json`**

```json
{
  "canary": [
    "cred_kapiva_strength_stamina_40off",
    "cred_lenskart_goldmax_1year_free",
    "<third id>"
  ]
}
```

- [ ] **Step 3: Copy the 3 images into `app/src/androidTest/assets/canary/`**

```bash
mkdir -p app/src/androidTest/assets/canary
cp "Coupons /images/_local/cred_kapiva_strength_stamina_40off.png" app/src/androidTest/assets/canary/
cp "Coupons /images/_local/cred_lenskart_goldmax_1year_free.png" app/src/androidTest/assets/canary/
cp "Coupons /images/_local/<third>.png" app/src/androidTest/assets/canary/
```

- [ ] **Step 4: Generate `canary_expected.json`**

Extract the 3 entries from `Coupons /manifest.json` into a new file `app/src/androidTest/assets/canary_expected.json` (same schema as the manifest, samples filtered).

- [ ] **Step 5: Commit**

```bash
git add config/extraction/canary.json app/src/androidTest/assets/canary/ app/src/androidTest/assets/canary_expected.json
git commit -m "test(android): define canary set for Layer 3 smoke"
```

### Task 4.2: Instrumented smoke test

**Files:**
- Create: `app/src/androidTest/kotlin/com/example/coupontracker/extraction/ExtractionSmokeTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.example.coupontracker.extraction

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExtractionSmokeTest {

    @Test
    fun canary_samples_extract_correctly() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedJson = ctx.assets.open("canary_expected.json").bufferedReader().use { it.readText() }
        val expected = JSONObject(expectedJson).getJSONArray("samples")
        val results = JSONArray()
        for (i in 0 until expected.length()) {
            val sample = expected.getJSONObject(i)
            val name = sample.getString("image").substringAfterLast('/')
            val bytes = ctx.assets.open("canary/$name").readBytes()
            val parsed = runExtraction(bytes)  // existing app extraction entry point
            results.put(JSONObject().put("id", sample.getString("id")).put("parsed", parsed))
        }
        // Write the report so it can be pulled with `adb pull`.
        val reportFile = File(ctx.getExternalFilesDir(null), "extraction_smoke_report.json")
        reportFile.writeText(JSONObject().put("samples", results).toString(2))
        assertTrue("Report was not written", reportFile.exists())
    }

    private fun runExtraction(imageBytes: ByteArray): JSONObject {
        // Wire to the same extraction service the app uses; specifics depend on
        // the app's DI graph. Use UniversalExtractionService or
        // MultiCouponExtractionService as appropriate.
        TODO("wire to app's extraction service")
    }
}
```

- [ ] **Step 2: Wire `runExtraction` to the app's extraction service**

Read `app/src/main/kotlin/com/example/coupontracker/universal/UniversalExtractionService.kt` to find the public entry point that takes bytes and returns parsed coupon JSON. Replace the `TODO` body. The simplest path is to construct the service with Hilt's test entrypoint or instantiate it directly with required collaborators from `app/src/main/kotlin/com/example/coupontracker/di/UniversalExtractionModule.kt`.

- [ ] **Step 3: Run the test on a connected device**

```bash
./scripts/run_connected_android_tests.sh -- --tests "com.example.coupontracker.extraction.ExtractionSmokeTest"
```

Expected: PASS, and `extraction_smoke_report.json` is written on the device.

- [ ] **Step 4: Pull the report**

```bash
adb pull /sdcard/Android/data/com.example.coupontracker/files/extraction_smoke_report.json /tmp/
cat /tmp/extraction_smoke_report.json | jq .
```

Expected: 3 samples, all with `parsed` blocks.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/kotlin/com/example/coupontracker/extraction/ExtractionSmokeTest.kt
git commit -m "test(android): canary instrumented smoke test"
```

### Task 4.3: Wire current Mac failures into the smoke set

**Files:**
- Modify: `app/src/androidTest/kotlin/com/example/coupontracker/extraction/ExtractionSmokeTest.kt`
- Create: `scripts/extraction_eval/sync_failures_to_androidtest.py`

- [ ] **Step 1: Build a sync script**

```python
"""Copy current Mac failures into androidTest assets and append them to canary_expected.json."""
from __future__ import annotations
from pathlib import Path
import json
import shutil
import sys

def main(argv: list[str]) -> int:
    eval_root = Path(argv[1] if len(argv) > 1 else "build/extraction-eval")
    target_assets = Path("app/src/androidTest/assets")
    target_assets.joinpath("failures").mkdir(parents=True, exist_ok=True)
    failures = json.loads((eval_root / "failures.json").read_text())["samples"]
    canary_expected = json.loads((target_assets / "canary_expected.json").read_text())
    canary_expected.setdefault("samples", [])
    canary_expected["failures"] = []
    for s in failures:
        src = Path(s["image_path"])
        dst = target_assets / "failures" / src.name
        shutil.copyfile(src, dst)
        canary_expected["failures"].append({
            "id": s["id"], "image": f"failures/{src.name}",
            "expected": s["expected"],
        })
    (target_assets / "canary_expected.json").write_text(json.dumps(canary_expected, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
```

Save as `scripts/extraction_eval/sync_failures_to_androidtest.py`.

- [ ] **Step 2: Extend the smoke test to iterate over `failures` if present**

In `ExtractionSmokeTest.kt`, after iterating `samples`, also iterate `failures` if the JSON contains that key. Use the same `runExtraction` path. Mark each result `source` = `canary` or `failure`.

- [ ] **Step 3: Smoke run end-to-end**

```bash
./scripts/eval_extraction_mac.sh
python scripts/extraction_eval/sync_failures_to_androidtest.py
./scripts/run_connected_android_tests.sh -- --tests "com.example.coupontracker.extraction.ExtractionSmokeTest"
adb pull /sdcard/Android/data/com.example.coupontracker/files/extraction_smoke_report.json /tmp/
```

Expected: report contains canary + every Mac failure.

- [ ] **Step 4: Commit**

```bash
git add scripts/extraction_eval/sync_failures_to_androidtest.py \
        app/src/androidTest/kotlin/com/example/coupontracker/extraction/ExtractionSmokeTest.kt
git commit -m "test(android): include current Mac failures in smoke set"
```

### Task 4.4: Opt-in changed-since-baseline expansion of the smoke set

**Files:**
- Modify: `scripts/extraction_eval/sync_failures_to_androidtest.py`

The spec calls out a third Android-smoke input (off by default): every sample whose parsed output has changed since the last baseline, even if still passing.

- [ ] **Step 1: Add a `--include-changed` flag to the sync script**

In `sync_failures_to_androidtest.py`, accept `--include-changed` via argparse. When set, additionally read `build/extraction-eval/baseline.json` and `build/extraction-eval/latest.json`, run `diff_against_baseline` from `extraction_eval.baseline`, and copy the union of {failure, changed} samples into `app/src/androidTest/assets/`.

- [ ] **Step 2: Extend `canary_expected.json` with a `changed` block**

When `--include-changed` runs, add a `changed` array to `canary_expected.json` parallel to `samples` and `failures`. The instrumented test from Task 4.2 iterates `changed` if present, marking source `changed`.

- [ ] **Step 3: Smoke run with the flag**

```bash
python scripts/extraction_eval/sync_failures_to_androidtest.py --include-changed
./scripts/run_connected_android_tests.sh -- --tests "com.example.coupontracker.extraction.ExtractionSmokeTest"
```

Expected: report includes canary + failures + changed-since-baseline samples.

- [ ] **Step 4: Commit**

```bash
git add scripts/extraction_eval/sync_failures_to_androidtest.py app/src/androidTest/
git commit -m "test(android): opt-in --include-changed expansion for Layer 3 smoke"
```

### Task 4.5: Mac-vs-Android comparison report

**Files:**
- Create: `scripts/extraction_eval/compare_android.py`
- Create: `scripts/extraction_eval/tests/test_compare_android.py`

- [ ] **Step 1: Write the failing test**

```python
import json
from pathlib import Path
from extraction_eval.compare_android import compare

def test_compare_flags_disagreements(tmp_path):
    mac = tmp_path / "mac.json"
    android = tmp_path / "android.json"
    mac.write_text(json.dumps({"samples": [{"id":"a","parsed":{"redeemCode":"X"},"latency_ms":100}]}))
    android.write_text(json.dumps({"samples": [{"id":"a","parsed":{"redeemCode":"Y"},"latency_ms":200}]}))
    report = compare(mac=mac, android=android)
    assert report.disagreements[0].field == "redeemCode"
    assert report.disagreements[0].mac == "X"
    assert report.disagreements[0].android == "Y"
    assert report.latency["a"] == (100, 200)
```

Save as `scripts/extraction_eval/tests/test_compare_android.py`.

- [ ] **Step 2: Implement `compare_android.py`**

```python
"""Compare Mac harness output to the Android smoke report. The phone always wins."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json

@dataclass(frozen=True)
class Disagreement:
    id: str
    field: str
    mac: object
    android: object

@dataclass(frozen=True)
class CompareReport:
    disagreements: list[Disagreement]
    latency: dict[str, tuple[int, int]]  # id -> (mac_ms, android_ms)

def compare(*, mac: Path, android: Path) -> CompareReport:
    mac_data = {s["id"]: s for s in json.loads(mac.read_text())["samples"]}
    android_data = {s["id"]: s for s in json.loads(android.read_text())["samples"]}
    disagreements: list[Disagreement] = []
    latency: dict[str, tuple[int, int]] = {}
    for sid in mac_data.keys() & android_data.keys():
        m = mac_data[sid].get("parsed", {})
        a = android_data[sid].get("parsed", {})
        for k in set(m) | set(a):
            if m.get(k) != a.get(k):
                disagreements.append(Disagreement(sid, k, m.get(k), a.get(k)))
        latency[sid] = (mac_data[sid].get("latency_ms", -1), android_data[sid].get("latency_ms", -1))
    return CompareReport(disagreements=disagreements, latency=latency)
```

- [ ] **Step 3: Run, confirm pass**

```bash
cd scripts && pytest extraction_eval/tests/test_compare_android.py -v
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add scripts/extraction_eval/compare_android.py scripts/extraction_eval/tests/test_compare_android.py
git commit -m "feat(extraction): Mac-vs-Android comparison report"
```

---

## Phase 5 — Pending Review Flow [parallel with Phase 4]

### Task 5.1: Pending sample handling

**Status: DONE** — commit `066941b3`

**Files modified:**
- `scripts/extraction_eval/manifest.py`
- `scripts/extraction_eval/runner.py`
- `scripts/extraction_eval/report.py`
- `scripts/extraction_eval/tests/fixtures/mini_manifest.json`
- `scripts/extraction_eval/tests/test_manifest.py`
- `scripts/extraction_eval/tests/test_report.py`

- [x] **Step 1: Update the test fixture**

Added `pending_a` sample (no `expected` key) to `mini_manifest.json`. Fixture now has 3 samples total.

- [x] **Step 2: Add failing tests (red phase)**

Added `test_load_manifest_handles_samples_without_expected_block` in `test_manifest.py` (verifies `expected is None` and `is_pending is True`). Added `test_run_md_pending_section_and_accuracy_exclusion` in `test_report.py`.

- [x] **Step 3: Implement `Sample.is_pending` and make `expected` optional**

`manifest.py`: Changed `expected: dict[str, Any]` to `Optional[dict[str, Any]]`. Updated `load_manifest` to use `raw.get("expected")` (no KeyError on missing key). Added `is_pending` property. Added docstring note on `frozen=True` hashability: `Sample` is immutable but not hashable because `dict | None` is unhashable — use `Sample.image_sha256` as dict key instead.

`SampleResult` in `report.py`: Added `pending: bool = False` field. Updated `_sample_to_dict` to include `pending` in JSON output. Updated `failures.json` writer to exclude pending samples (filter `not s.passed and not s.pending`).

`runner.py`: When `s.is_pending`, still runs full pipeline (preprocess → prompt → LLM → parse), then sets `field_diff=[]`, `passed=False`, `pending=True`. Diff step is only thing skipped.

- [x] **Step 4: Add a `## Pending` section to `run.md`**

`_render_md` now splits samples into `evaluated` and `pending_samples`. Summary counts (Total/Passed/Failed) and per-sample table use `evaluated` only. Per-field accuracy uses `evaluated` only. A `## Pending` section is appended listing each pending sample's id, image SHA256, and parsed JSON output.

- [x] **Step 5: Run all harness tests, confirm pass**

```
21 passed in 0.04s
```

19 prior tests all green. 2 new tests (1 manifest + 1 report) = 21 total. No regressions.

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/extraction_eval/manifest.py scripts/extraction_eval/runner.py scripts/extraction_eval/report.py scripts/extraction_eval/tests/test_manifest.py scripts/extraction_eval/tests/fixtures/mini_manifest.json
git commit -m "feat(extraction): pending sample reporting (non-gating)"
```

---

## Wrap-up

### Task W.1: End-to-end live run

- [ ] **Step 1: Run the full harness against the real manifest**

```bash
./scripts/eval_extraction_mac.sh
```

- [ ] **Step 2: Inspect `build/extraction-eval/latest.md`**

Expected: a table covering all 35 annotated samples with per-sample pass/fail and timing, plus per-field accuracy.

- [ ] **Step 3: Promote baseline once the run looks correct**

```bash
./scripts/eval_extraction_mac.sh --promote-baseline
```

- [ ] **Step 4: Run Android smoke**

```bash
python scripts/extraction_eval/sync_failures_to_androidtest.py
./scripts/run_connected_android_tests.sh -- --tests "com.example.coupontracker.extraction.ExtractionSmokeTest"
adb pull /sdcard/Android/data/com.example.coupontracker/files/extraction_smoke_report.json /tmp/
python -c "from extraction_eval.compare_android import compare; from pathlib import Path; r = compare(mac=Path('build/extraction-eval/latest.json'), android=Path('/tmp/extraction_smoke_report.json')); print(r)"
```

Expected: zero disagreements, or any disagreement is investigated under the "phone wins" rule.

### Task W.2: Final commit / branch ready

- [ ] **Step 1: Verify everything passes in one go**

```bash
./gradlew :app:testDebugUnitTest
cd scripts && pytest extraction_eval -v && cd ..
./scripts/eval_extraction_mac.sh
```

Expected: all green.

- [ ] **Step 2: Decide branch fate**

The implementing engineer hands the branch off using the `superpowers:finishing-a-development-branch` skill.

---

## Done

Success criteria from the spec — verify each:

1. ✅ Normal extraction-debug iteration runs on Mac in seconds against all manifest samples — `./scripts/eval_extraction_mac.sh`
2. ✅ Output is sufficient to diagnose a failure without a phone — `failures.json` contains prompt, raw output, parsed, diff
3. ✅ Mac-green / Android-red discrepancy detectable in one Layer 3 run — `compare_android.py`
4. ✅ Regression flipping a previously-correct field is caught — `baseline.py`'s `diff_against_baseline`
