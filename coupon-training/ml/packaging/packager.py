"""Utilities for exporting trained detectors to deployment bundles."""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

try:
    from ultralytics import YOLO
except ModuleNotFoundError:  # pragma: no cover
    YOLO = None

LOGGER = logging.getLogger("ml.packaging")


@dataclass
class PackageArtifact:
    format: str
    path: str
    sha256: str
    size_bytes: int


@dataclass
class PackageResult:
    weights_path: str
    output_dir: str
    exported_at: str
    artifacts: List[PackageArtifact]
    labels_path: str
    manifest_path: str
    labels_sha256: str
    labels: List[str]
    notes: Optional[str] = None


class ModelPackager:
    """Exports YOLO detectors to deployment-ready formats."""

    def __init__(
        self,
        weights_path: Path | str,
        output_dir: Path | str,
        labels_config: Path | str = Path('registry/labels/coupons-detection/v1/labels.json'),
        model_name: Optional[str] = None,
        model_version: Optional[str] = None,
    ) -> None:
        self.weights_path = Path(weights_path)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        if not self.weights_path.exists():
            raise FileNotFoundError(f"Weights not found: {self.weights_path}")
        if YOLO is None:
            raise RuntimeError("Ultralytics not installed. Install from requirements-ml.txt to package models.")

        self.labels_config = Path(labels_config)
        if not self.labels_config.exists():
            raise FileNotFoundError(f"Canonical labels config not found: {self.labels_config}")
        self.labels_bytes = self.labels_config.read_bytes()
        self.labels_doc = json.loads(self.labels_bytes.decode('utf-8'))
        self.labels_sha = hashlib.sha256(self.labels_bytes).hexdigest()
        self.labels_name = self.labels_doc.get('name', 'unknown')
        self.labels_version = self.labels_doc.get('version', '1')
        self.model_name = model_name or self.labels_doc.get('model_name') or self.weights_path.stem
        self.model_version = model_version or self.labels_doc.get('model_version') or self.labels_version

    def package(self, formats: Optional[List[str]] = None, int8: bool = False) -> PackageResult:
        formats = formats or ["onnx", "tflite"]
        model = YOLO(str(self.weights_path))
        artifacts: List[PackageArtifact] = []

        for fmt in formats:
            try:
                exported_path = self._export(model, fmt, int8=int8)
                artifact = self._build_artifact(exported_path, fmt)
                artifacts.append(artifact)
            except Exception as exc:
                LOGGER.warning("Failed to export format %s: %s", fmt, exc)

        exported_at = datetime.utcnow().isoformat() + "Z"
        labels_output, manifest_path = self._write_bundle_metadata(artifacts, exported_at)
        packaging_manifest_path = self.output_dir / "packaging_manifest.json"
        packaging_manifest_path.write_text(json.dumps({
            'weights_path': str(self.weights_path.resolve()),
            'output_dir': str(self.output_dir.resolve()),
            'exported_at': exported_at,
            'manifest_path': str(manifest_path.resolve()),
            'labels_path': str(labels_output.resolve()),
            'artifacts': [asdict(artifact) for artifact in artifacts],
        }, indent=2))

        return PackageResult(
            weights_path=str(self.weights_path.resolve()),
            output_dir=str(self.output_dir.resolve()),
            exported_at=exported_at,
            artifacts=artifacts,
            labels_path=str(labels_output.resolve()),
            manifest_path=str(manifest_path.resolve()),
            labels_sha256=self.labels_sha,
            labels=self.labels_doc.get('labels', []),
        )

    def _export(self, model: YOLO, fmt: str, int8: bool = False) -> Path:
        LOGGER.info("Exporting %s -> %s", self.weights_path, fmt)
        export_dir = self.output_dir
        params: Dict[str, object] = {
            'format': fmt,
            'device': 'cpu',
            'half': False,
            'simplify': True,
            'int8': int8,
        }
        result = model.export(**params)
        if isinstance(result, (list, tuple)):
            exported = Path(result[0])
        else:
            exported = Path(result)
        if not exported.exists():
            raise FileNotFoundError(f"Export did not produce output: {exported}")
        target = export_dir / exported.name
        shutil.move(str(exported), target)
        return target

    def _build_artifact(self, path: Path, fmt: str) -> PackageArtifact:
        sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
        return PackageArtifact(
            format=fmt,
            path=str(path.resolve()),
            sha256=sha256,
            size_bytes=path.stat().st_size,
        )

    def _write_bundle_metadata(self, artifacts: List[PackageArtifact], exported_at: str) -> (Path, Path):
        labels_output = self.output_dir / "labels.json"
        labels_output.write_bytes(self.labels_bytes)

        manifest = {
            'name': self.model_name,
            'version': self.model_version,
            'exported_at': exported_at,
            'weights_path': str(self.weights_path.resolve()),
            'labels_name': self.labels_name,
            'labels_version': self.labels_version,
            'labels_sha256': self.labels_sha,
            'artifacts': [asdict(artifact) for artifact in artifacts],
        }

        manifest_path = self.output_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2))
        return labels_output, manifest_path
