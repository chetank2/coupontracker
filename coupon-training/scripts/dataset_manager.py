#!/usr/bin/env python3
"""Command line interface for dataset versioning."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ml.dataset_manager import DatasetManager


def cmd_create(args: argparse.Namespace) -> None:
    manager = DatasetManager(
        annotation_dir=Path(args.annotations),
        datasets_dir=Path(args.datasets_dir),
        index_path=Path(args.index),
    )
    summary = manager.create_dataset(
        description=args.description,
        tags=args.tags or [],
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        seed=args.seed,
    )
    print(json.dumps(summary.__dict__, indent=2))


def cmd_list(args: argparse.Namespace) -> None:
    manager = DatasetManager(
        annotation_dir=Path(args.annotations),
        datasets_dir=Path(args.datasets_dir),
        index_path=Path(args.index),
    )
    summaries = manager.list_versions()
    for summary in summaries:
        print(json.dumps(summary.__dict__, indent=2))


def cmd_info(args: argparse.Namespace) -> None:
    manager = DatasetManager(
        annotation_dir=Path(args.annotations),
        datasets_dir=Path(args.datasets_dir),
        index_path=Path(args.index),
    )
    summary = manager.get_version(args.version)
    if not summary:
        raise SystemExit(f"Version {args.version} not found")
    print(json.dumps(summary.__dict__, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Dataset Manager CLI")
    parser.add_argument("--annotations", default="data/annotated")
    parser.add_argument("--datasets-dir", default="data/datasets")
    parser.add_argument("--index", default="data/manifests/index.json")

    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create", help="Create a new dataset version")
    create.add_argument("--description", default="")
    create.add_argument("--tags", nargs="*", default=[])
    create.add_argument("--train-ratio", type=float, default=0.7)
    create.add_argument("--val-ratio", type=float, default=0.15)
    create.add_argument("--seed", type=int, default=42)
    create.set_defaults(func=cmd_create)

    listing = subparsers.add_parser("list", help="List dataset versions")
    listing.set_defaults(func=cmd_list)

    info = subparsers.add_parser("info", help="Show dataset version details")
    info.add_argument("version")
    info.set_defaults(func=cmd_info)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
