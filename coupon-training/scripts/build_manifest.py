#!/usr/bin/env python3
"""Build a manifest JSON directly from the annotation directory."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ml.dataset_manager import DatasetManager


def main() -> None:
    parser = argparse.ArgumentParser(description="Build manifest from annotations")
    parser.add_argument("--annotations", default="data/annotated")
    parser.add_argument("--output", default="data/manifests/full_manifest.json")
    parser.add_argument("--train-ratio", type=float, default=0.7)
    parser.add_argument("--val-ratio", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    manager = DatasetManager(
        annotation_dir=Path(args.annotations),
        datasets_dir=Path("data/datasets/_tmp"),
        index_path=Path("data/manifests/_tmp_index.json"),
    )
    manifest = manager.build_manifest(
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        seed=args.seed,
    )

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(manifest, indent=2))

    counts = {"train": 0, "val": 0, "test": 0}
    for rec in manifest:
        split = rec.get("split", "train")
        counts[split] = counts.get(split, 0) + 1

    print(
        "Wrote {total} records to {path} (train={train}, val={val}, test={test})".format(
            total=len(manifest),
            path=output_path,
            train=counts.get("train", 0),
            val=counts.get("val", 0),
            test=counts.get("test", 0),
        )
    )

    # Remove temporary dataset artifacts created by the manager
    if manager.index_path.exists():
        manager.index_path.unlink()
    if manager.datasets_dir.exists():
        for child in manager.datasets_dir.glob("**/*"):
            if child.is_file():
                child.unlink()
        for child in sorted(manager.datasets_dir.glob("**/*"), reverse=True):
            if child.is_dir():
                child.rmdir()
        manager.datasets_dir.rmdir()


if __name__ == "__main__":
    main()
