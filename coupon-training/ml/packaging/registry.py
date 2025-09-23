"""Registry for packaged model artifacts."""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from .packager import PackageResult


@dataclass
class PackageRecord:
    package_id: str
    weights_path: str
    output_dir: str
    exported_at: str
    artifacts: List[Dict]
    manifest_path: str
    labels_path: str
    labels_sha256: str
    notes: Optional[str] = None
    compliance_status: str = 'pending'
    compliance_issues: List[str] = field(default_factory=list)
    release_status: str = 'draft'
    release_notes: Optional[str] = None
    promoted_at: Optional[str] = None


class PackagingRegistry:
    def __init__(self, registry_path: Path | str = Path('artifacts/packages/index.json')) -> None:
        self.registry_path = Path(registry_path)
        self.registry_path.parent.mkdir(parents=True, exist_ok=True)
        self._data = self._load()

    def _load(self) -> Dict:
        if self.registry_path.exists():
            try:
                return json.loads(self.registry_path.read_text())
            except json.JSONDecodeError:
                pass
        return {'packages': []}

    def _save(self) -> None:
        self.registry_path.write_text(json.dumps(self._data, indent=2))

    def add(
        self,
        result: PackageResult,
        notes: Optional[str] = None,
        compliance: Optional[Dict] = None,
        release_status: str = 'draft',
    ) -> PackageRecord:
        package_id = datetime.utcnow().strftime('%Y%m%d_%H%M%S')
        record = PackageRecord(
            package_id=package_id,
            weights_path=result.weights_path,
            output_dir=result.output_dir,
            exported_at=result.exported_at,
            artifacts=[artifact.__dict__ for artifact in result.artifacts],
            manifest_path=result.manifest_path,
            labels_path=result.labels_path,
            labels_sha256=getattr(result, 'labels_sha256', ''),
            notes=notes,
            compliance_status=(compliance or {}).get('status', 'pending'),
            compliance_issues=(compliance or {}).get('issues', []),
            release_status=release_status,
        )
        self._data['packages'].append(asdict(record))
        self._save()
        return record

    def list(self) -> List[PackageRecord]:
        records = []
        for entry in self._data.get('packages', []):
            entry.setdefault('compliance_status', 'pending')
            entry.setdefault('compliance_issues', [])
            entry.setdefault('manifest_path', '')
            entry.setdefault('labels_path', '')
            entry.setdefault('labels_sha256', '')
            entry.setdefault('release_status', 'draft')
            entry.setdefault('release_notes', None)
            entry.setdefault('promoted_at', None)
            try:
                records.append(PackageRecord(**entry))
            except TypeError:
                continue
        records.sort(key=lambda rec: rec.exported_at, reverse=True)
        return records

    def find(self, package_id: str) -> Optional[PackageRecord]:
        for record in self.list():
            if record.package_id == package_id:
                return record
        return None

    def find_artifact(self, package_id: str, format_name: str) -> Optional[Dict]:
        record = self.find(package_id)
        if not record:
            return None
        for artifact in record.artifacts:
            if artifact.get('format') == format_name:
                return artifact
        return None

    def update_status(self, package_id: str, release_status: str, release_notes: Optional[str] = None) -> Optional[PackageRecord]:
        for entry in self._data.get('packages', []):
            if entry.get('package_id') == package_id:
                entry['release_status'] = release_status
                entry['release_notes'] = release_notes
                entry['promoted_at'] = datetime.utcnow().isoformat() + 'Z'
                self._save()
                return PackageRecord(**entry)
        return None


__all__ = ['PackagingRegistry', 'PackageRecord']
