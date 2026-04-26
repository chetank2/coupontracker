"""Load and validate Coupons /manifest.json."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional
import json

SUPPORTED_SCHEMA_VERSIONS = {1}

# Note: frozen=True makes instances immutable but __hash__ is suppressed
# because `expected: dict | None` is unhashable. Don't use Sample as a dict
# key or in a set; use Sample.image_sha256 for those purposes.
@dataclass(frozen=True)
class Sample:
    id: str
    image_path: Path
    image_sha256: str
    expected: Optional[dict[str, Any]]

    @property
    def is_pending(self) -> bool:
        """True when no expected block has been provided (awaiting human ground-truth review)."""
        return self.expected is None

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
                expected=raw.get("expected"),
            )
        )
    return samples
