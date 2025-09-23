"""Dataset manager for coupon training manifests."""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
import random
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional


@dataclass
class DatasetSummary:
    version: str
    created_at: str
    description: str
    tags: List[str]
    num_records: int
    split_counts: Dict[str, int]
    annotation_hash: str
    manifest_path: str
    packaging_manifest: Optional[str] = None


class DatasetManager:
    """Utility to build and version dataset manifests."""

    def __init__(
        self,
        annotation_dir: Path | str = Path("data/annotated"),
        datasets_dir: Path | str = Path("data/datasets"),
        index_path: Path | str = Path("data/manifests/index.json"),
    ) -> None:
        self.annotation_dir = Path(annotation_dir)
        self.datasets_dir = Path(datasets_dir)
        self.index_path = Path(index_path)
        self.base_dir = self.annotation_dir.resolve().parent
        self.annotation_dir.mkdir(parents=True, exist_ok=True)
        self.datasets_dir.mkdir(parents=True, exist_ok=True)
        self.index_path.parent.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # Manifest construction helpers
    # ------------------------------------------------------------------
    def _read_annotation_records(self) -> List[Dict]:
        records: List[Dict] = []
        for path in sorted(self.annotation_dir.glob("*.json")):
            try:
                data = json.loads(path.read_text())
            except json.JSONDecodeError:
                continue
            if not isinstance(data, dict):
                continue

            image_path_raw = data.get("image_path") or data.get("image")
            if not image_path_raw:
                continue

            img_path = Path(image_path_raw)
            if not img_path.is_absolute():
                candidates = [
                    self.base_dir / "processed" / img_path.name,
                    self.annotation_dir / img_path.name,
                    Path(image_path_raw)
                ]
                for candidate in candidates:
                    if candidate.exists():
                        img_path = candidate.resolve()
                        break

            record = {
                "image_path": str(img_path),
                "width": int(data.get("width") or data.get("image_width") or 0),
                "height": int(data.get("height") or data.get("image_height") or 0),
                "split": data.get("split", "train"),
                "metadata": data.get("metadata", {}),
                "bboxes": [],
            }

            regions = data.get("regions") or []
            if isinstance(regions, dict):
                regions = [
                    {"type": key, "box": value, "text": None}
                    for key, value in regions.items()
                ]
            for region in regions:
                if not isinstance(region, dict):
                    continue
                box = region.get("box") or region.get("bbox")
                if not box or len(box) != 4:
                    continue
                record["bboxes"].append(
                    {
                        "category": region.get("type", "unknown"),
                        "bbox": [float(v) for v in box],
                        "text": region.get("text"),
                    }
                )

            annotations = data.get("annotations") or []
            if isinstance(annotations, list):
                for ann in annotations:
                    if not isinstance(ann, dict):
                        continue
                    left = ann.get("left")
                    top = ann.get("top")
                    width = ann.get("width")
                    height = ann.get("height")
                    if None in (left, top, width, height):
                        continue
                    bbox = [left, top, left + width, top + height]
                    record["bboxes"].append(
                        {
                            "category": ann.get("type", "unknown"),
                            "bbox": [float(v) for v in bbox],
                            "text": ann.get("text"),
                        }
                    )

            records.append(record)
        return records

    @staticmethod
    def _compute_annotation_hash(records: Iterable[Dict]) -> str:
        digest = hashlib.sha256()
        for rec in records:
            digest.update(json.dumps(rec, sort_keys=True).encode("utf-8"))
        return digest.hexdigest()

    def build_manifest(
        self,
        train_ratio: float = 0.7,
        val_ratio: float = 0.15,
        seed: int = 42,
    ) -> List[Dict]:
        records = self._read_annotation_records()
        if not records:
            raise RuntimeError("No annotations available to build a manifest")

        random.Random(seed).shuffle(records)
        total = len(records)
        train_end = int(total * train_ratio)
        val_end = train_end + int(total * val_ratio)

        for idx, rec in enumerate(records):
            if idx < train_end:
                rec["split"] = "train"
            elif idx < val_end:
                rec["split"] = "val"
            else:
                rec["split"] = "test"
        return records

    # ------------------------------------------------------------------
    # Version management
    # ------------------------------------------------------------------
    def _load_index(self) -> Dict[str, List[Dict]]:
        if self.index_path.exists():
            return json.loads(self.index_path.read_text())
        return {"versions": []}

    def _write_index(self, index: Dict) -> None:
        self.index_path.write_text(json.dumps(index, indent=2))

    def list_versions(self) -> List[DatasetSummary]:
        index = self._load_index()
        summaries = []
        for entry in index.get("versions", []):
            summaries.append(DatasetSummary(**entry))
        return summaries

    def get_version(self, version: str) -> Optional[DatasetSummary]:
        for summary in self.list_versions():
            if summary.version == version:
                return summary
        return None

    def create_dataset(
        self,
        description: str = "",
        tags: Optional[List[str]] = None,
        train_ratio: float = 0.7,
        val_ratio: float = 0.15,
        seed: int = 42,
    ) -> DatasetSummary:
        tags = tags or []
        manifest = self.build_manifest(train_ratio=train_ratio, val_ratio=val_ratio, seed=seed)
        annotation_hash = self._compute_annotation_hash(manifest)
        counts: Dict[str, int] = {"train": 0, "val": 0, "test": 0}
        for rec in manifest:
            split = rec.get("split", "train")
            counts[split] = counts.get(split, 0) + 1

        version = _dt.datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        version_dir = self.datasets_dir / version
        version_dir.mkdir(parents=True, exist_ok=True)

        manifest_path = version_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2))

        summary = DatasetSummary(
            version=version,
            created_at=_dt.datetime.utcnow().isoformat() + "Z",
            description=description,
            tags=tags,
            num_records=len(manifest),
            split_counts=counts,
            annotation_hash=annotation_hash,
            manifest_path=str(manifest_path.resolve()),
        )

        index = self._load_index()
        index.setdefault("versions", [])
        index["versions"].append(asdict(summary))
        self._write_index(index)
        return summary
