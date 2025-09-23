#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional


@dataclass
class EvaluationRecord:
    run_path: str
    trained_at: str
    manifest_path: Optional[str]
    detector_checkpoint: Optional[str]
    detector_summary: Dict[str, float]
    ocr_metrics: Dict[str, float]
    detector_per_class: List[Dict[str, float]]


class EvaluationManager:
    def __init__(self, artifacts_dir: Path | str = Path('artifacts')) -> None:
        self.artifacts_dir = Path(artifacts_dir)

    def _load_json(self, path: Path) -> Optional[Dict]:
        try:
            return json.loads(path.read_text())
        except Exception:
            return None

    def list_evaluations(self) -> List[EvaluationRecord]:
        records: List[EvaluationRecord] = []
        if not self.artifacts_dir.exists():
            return records

        for metadata_path in self.artifacts_dir.glob('**/metadata.json'):
            try:
                meta = self._load_json(metadata_path) or {}
                evaluation_path = metadata_path.parent / 'evaluation.json'
                evaluation = self._load_json(evaluation_path) or {}

                trained_at = meta.get('trained_at') or datetime.utcfromtimestamp(metadata_path.stat().st_mtime).isoformat() + 'Z'
                detector_summary = (meta.get('detector_metrics') or {}).get('summary') or {}
                ocr_metrics = meta.get('ocr_metrics') or {}

                try:
                    relative_run = str(metadata_path.parent.relative_to(self.artifacts_dir))
                except ValueError:
                    relative_run = str(metadata_path.parent)

                record = EvaluationRecord(
                    run_path=relative_run,
                    trained_at=trained_at,
                    manifest_path=meta.get('manifest_path'),
                    detector_checkpoint=meta.get('detector_checkpoint'),
                    detector_summary={k: float(v) for k, v in detector_summary.items() if isinstance(v, (int, float))},
                    ocr_metrics={k: float(v) for k, v in ocr_metrics.items() if isinstance(v, (int, float))},
                    detector_per_class=[
                        {
                            'category': entry.get('category'),
                            'map50': float(entry.get('map50')) if isinstance(entry.get('map50'), (int, float)) else None
                        }
                        for entry in ((meta.get('detector_metrics') or {}).get('per_class') or (evaluation.get('detector') or {}).get('per_class') or [])
                    ],
                )
                record.detector_summary.setdefault('map50', None)
                records.append(record)
            except Exception:
                continue

        records.sort(key=lambda rec: rec.trained_at, reverse=True)
        return records

    def get_evaluation(self, run_path: str) -> Optional[Dict]:
        target = self.artifacts_dir / run_path
        metadata_path = target / 'metadata.json'
        if not metadata_path.exists():
            return None
        meta = self._load_json(metadata_path) or {}
        evaluation = self._load_json(target / 'evaluation.json') or {}
        return {
            'metadata': meta,
            'evaluation': evaluation,
        }


__all__ = ['EvaluationManager', 'EvaluationRecord']
