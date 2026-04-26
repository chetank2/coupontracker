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
