#!/usr/bin/env python3
"""Prove committed screenshot regression fixtures are replayable.

This script is intentionally hermetic: it does not run OCR, VLMs, or Android.
It verifies that committed screenshot manifests point at existing images, that
image hashes still match, and that replay fixtures still match expected JSON.
The unlabeled real screenshot corpus is inventoried separately so local proof
reports show the current gap without pretending those screenshots are golden
field regressions yet.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GOLDEN_MANIFESTS = (
    "benchmark/goldenset/manifest.json",
    "benchmark/goldenset/multi/manifest.json",
)
DEFAULT_REAL_CORPUS = "Coupons "


@dataclass(frozen=True)
class ProofResult:
    label: str
    samples: int
    failures: list[str]

    @property
    def passed(self) -> bool:
        return not self.failures


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text())


def canonical(value: Any) -> Any:
    """Return JSON-comparable data with stable object key ordering."""
    return json.loads(json.dumps(value, sort_keys=True))


def normalize_manifest(raw: Any, *, manifest_path: Path) -> list[dict[str, Any]]:
    if isinstance(raw, list):
        return raw
    if isinstance(raw, dict) and isinstance(raw.get("samples"), list):
        return raw["samples"]
    raise ValueError(f"{manifest_path} must be a sample array or contain samples[]")


def replay_path_for(manifest_path: Path, sample_id: str) -> Path:
    return manifest_path.parent / "replay" / f"{sample_id}.json"


def prove_golden_manifest(manifest_path: Path) -> ProofResult:
    failures: list[str] = []
    samples = normalize_manifest(load_json(manifest_path), manifest_path=manifest_path)
    seen_ids: set[str] = set()

    for index, sample in enumerate(samples):
        sample_id = sample.get("id")
        if not sample_id:
            failures.append(f"{manifest_path}: sample #{index + 1} has no id")
            continue
        if sample_id in seen_ids:
            failures.append(f"{manifest_path}: duplicate sample id {sample_id}")
        seen_ids.add(sample_id)

        image_value = sample.get("image")
        if not image_value:
            failures.append(f"{manifest_path}: {sample_id} has no image")
            continue
        image_path = manifest_path.parent / image_value
        if not image_path.is_file():
            failures.append(f"{manifest_path}: {sample_id} image missing: {image_path}")
            continue

        expected_sha = sample.get("imageSha256")
        actual_sha = sha256_file(image_path)
        if actual_sha != expected_sha:
            failures.append(
                f"{manifest_path}: {sample_id} imageSha256 mismatch "
                f"expected={expected_sha} actual={actual_sha}"
            )

        if "expected" not in sample:
            failures.append(f"{manifest_path}: {sample_id} has no expected block")
            continue

        replay_path = replay_path_for(manifest_path, sample_id)
        if not replay_path.is_file():
            failures.append(f"{manifest_path}: {sample_id} replay missing: {replay_path}")
            continue
        replay = load_json(replay_path)
        if canonical(replay) != canonical(sample["expected"]):
            failures.append(f"{manifest_path}: {sample_id} replay JSON differs from expected")

    return ProofResult(str(manifest_path.relative_to(REPO_ROOT)), len(samples), failures)


def inventory_real_corpus(corpus_dir: Path) -> dict[str, Any]:
    images = sorted(corpus_dir.glob("*.png"))
    return {
        "path": str(corpus_dir.relative_to(REPO_ROOT)),
        "images": [
            {
                "path": str(image.relative_to(REPO_ROOT)),
                "sha256": sha256_file(image),
            }
            for image in images
        ],
        "status": "inventory_only",
        "note": "These real screenshots need expected coupon JSON before they can be gating regressions.",
    }


def render_text(results: list[ProofResult], real_inventory: dict[str, Any] | None) -> str:
    lines = ["Screenshot regression proof"]
    for result in results:
        state = "PASS" if result.passed else "FAIL"
        lines.append(f"- {state} {result.label}: {result.samples} labeled samples")
        for failure in result.failures:
            lines.append(f"  - {failure}")
    if real_inventory is not None:
        lines.append(
            f"- INFO {real_inventory['path']}: "
            f"{len(real_inventory['images'])} real screenshots, inventory_only"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        action="append",
        default=[],
        help="Golden manifest path relative to the repo root. Defaults to the committed single and multi sets.",
    )
    parser.add_argument(
        "--real-corpus-dir",
        default=DEFAULT_REAL_CORPUS,
        help="Real screenshot corpus directory relative to the repo root.",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON.")
    args = parser.parse_args(argv)

    manifest_values = args.manifest or list(DEFAULT_GOLDEN_MANIFESTS)
    manifests = [REPO_ROOT / value for value in manifest_values]
    results = [prove_golden_manifest(path) for path in manifests]

    real_dir = REPO_ROOT / args.real_corpus_dir
    real_inventory = inventory_real_corpus(real_dir) if real_dir.is_dir() else None

    if args.json:
        payload = {
            "golden": [
                {
                    "label": result.label,
                    "samples": result.samples,
                    "passed": result.passed,
                    "failures": result.failures,
                }
                for result in results
            ],
            "realCorpus": real_inventory,
        }
        print(json.dumps(payload, indent=2))
    else:
        print(render_text(results, real_inventory), end="")

    return 1 if any(not result.passed for result in results) else 0


if __name__ == "__main__":
    sys.exit(main())
