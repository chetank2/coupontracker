#!/usr/bin/env python3
"""Run pre-annotation candidate generation for images or manifests."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

from ml.preannotation import generate_candidates


def iter_images_from_manifest(manifest_path: Path) -> Iterable[Path]:
    records = json.loads(manifest_path.read_text())
    for record in records:
        yield Path(record["image_path"])


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate pre-annotation suggestions")
    parser.add_argument("target", help="Image file or manifest.json")
    parser.add_argument("--output", default="suggestions.json")
    args = parser.parse_args()

    target_path = Path(args.target)
    outputs = []
    if target_path.suffix.lower() == ".json":
        for image_path in iter_images_from_manifest(target_path):
            candidates = [c.__dict__ for c in generate_candidates(image_path)]
            outputs.append({"image_path": str(image_path), "candidates": candidates})
    else:
        candidates = [c.__dict__ for c in generate_candidates(target_path)]
        outputs.append({"image_path": str(target_path), "candidates": candidates})

    Path(args.output).write_text(json.dumps(outputs, indent=2))
    print(f"Wrote suggestions for {len(outputs)} image(s) to {args.output}")


if __name__ == "__main__":
    main()
