"""Load and validate Coupons /manifest.json.

OCR sidecars: optional per-sample OCR input lives at
``<ocr_root>/<sample_id>.json`` with shape::

    {"text": str, "tiles": [{"text": str, "left": int, "top": int, "right": int, "bottom": int, "confidence": float}, ...]}

By default, ``ocr_root`` is ``<manifest-file-parent>/ocr``. Pass
``ocr_root=`` to ``load_manifest`` to switch sidecar sources (e.g. between
manual, Mac-Tesseract, and Android-real captures). If the sidecar file is
absent, the sample runs with empty OCR.
"""
from __future__ import annotations
from dataclasses import dataclass, field
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
    ocr_path: Optional[Path] = field(default=None)

    @property
    def is_pending(self) -> bool:
        """True when no expected block has been provided (awaiting human ground-truth review)."""
        return self.expected is None

    def load_ocr(self) -> dict:
        """Return OCR sidecar as dict, or empty payload if no sidecar exists.

        The returned dict may carry a top-level ``_source`` field tagging the
        OCR pipeline that produced it. See runtime.json's ``_ocrSourceLabels``
        for canonical values; ``"manual"`` if absent. Only
        ``"android-mlkit-tesseract"`` is considered Android parity — other
        sources are test fixtures.
        """
        if self.ocr_path is not None:
            data = json.loads(self.ocr_path.read_text())
            data.setdefault("_source", "manual")
            return data
        return {"text": "", "tiles": [], "_source": "none"}

def load_manifest(
    manifest_path: Path,
    root: Path,
    ocr_root: Optional[Path] = None,
) -> list[Sample]:
    """Load manifest.json.

    `root` is the directory containing the `images/` subtree.
    `ocr_root` is where per-sample OCR sidecars live; defaults to
    ``<manifest_path>.parent / "ocr"``.
    """
    if ocr_root is None:
        ocr_root = Path(manifest_path).parent / "ocr"
    data = json.loads(Path(manifest_path).read_text())
    version = data.get("schemaVersion")
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise ValueError(f"Unsupported schemaVersion: {version}")
    samples = []
    for raw in data.get("samples", []):
        ocr_path = ocr_root / f"{raw['id']}.json"
        samples.append(
            Sample(
                id=raw["id"],
                image_path=root / raw["image"],
                image_sha256=raw["imageSha256"],
                expected=raw.get("expected"),
                ocr_path=ocr_path if ocr_path.is_file() else None,
            )
        )
    return samples
