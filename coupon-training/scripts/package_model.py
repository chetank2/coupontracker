#!/usr/bin/env python3
"""Package YOLO detector weights into deployment artifacts."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import os
import sys

CURRENT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = CURRENT_DIR.parent
sys.path.append(str(PROJECT_ROOT))

from ml.packaging import ModelPackager
from ml.packaging.registry import PackagingRegistry
from ml.packaging.compliance import evaluate_bundle


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='Export trained detectors to ONNX/TFLite bundles')
    parser.add_argument('--weights', required=True, help='Path to YOLO weights (.pt)')
    parser.add_argument('--output-dir', default='artifacts/packages', help='Directory to place exported artifacts')
    parser.add_argument('--formats', nargs='*', default=['onnx', 'tflite'], help='Export formats (onnx, tflite, engine, etc.)')
    parser.add_argument('--int8', action='store_true', help='Attempt INT8 quantization (requires appropriate dependencies)')
    parser.add_argument('--registry', default='artifacts/packages/index.json', help='Path to packaging registry index')
    parser.add_argument('--notes', default=None, help='Optional notes stored in registry entry')
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    packager = ModelPackager(Path(args.weights), Path(args.output_dir))
    result = packager.package(formats=args.formats, int8=args.int8)
    compliance = evaluate_bundle(
        manifest_path=Path(result.manifest_path),
        labels_path=Path(result.labels_path),
        artifacts=[artifact.__dict__ for artifact in result.artifacts],
        required_labels=result.labels,
    )
    registry = PackagingRegistry(Path(args.registry))
    record = registry.add(
        result,
        notes=args.notes,
        compliance={'status': compliance.status, 'issues': compliance.issues},
    )
    print(json.dumps({
        'weights_path': result.weights_path,
        'output_dir': result.output_dir,
        'exported_at': result.exported_at,
        'artifacts': [artifact.__dict__ for artifact in result.artifacts],
        'registry_entry': record.__dict__
    }, indent=2))


if __name__ == '__main__':
    main()
