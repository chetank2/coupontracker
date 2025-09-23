"""Compliance checks for packaged model bundles."""

from __future__ import annotations

import json
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

SIZE_LIMITS = {
    'tflite': 8 * 1024 * 1024,  # 8 MB target
    'onnx': 25 * 1024 * 1024,
}


@dataclass
class ComplianceResult:
    status: str
    issues: List[str]


def check_artifact(path: Path, fmt: str) -> Optional[str]:
    if not path.exists():
        return f"{fmt}: file missing ({path})"
    limit = SIZE_LIMITS.get(fmt.lower())
    if limit and path.stat().st_size > limit:
        return f"{fmt}: size {path.stat().st_size} exceeds limit {limit}"
    return None


def validate_labels(manifest_path: Path, labels_path: Path, required: List[str]) -> List[str]:
    issues: List[str] = []
    try:
        manifest = json.loads(manifest_path.read_text())
    except Exception as exc:
        return [f"manifest.json unreadable: {exc}"]

    if not labels_path.exists():
        issues.append("labels.json missing")
        return issues

    labels_bytes = labels_path.read_bytes()
    labels_sha = hashlib.sha256(labels_bytes).hexdigest()
    if manifest.get('labels_sha256') != labels_sha:
        issues.append("labels_sha256 mismatch between manifest and labels.json")

    try:
        labels_doc = json.loads(labels_bytes.decode('utf-8'))
    except Exception as exc:
        issues.append(f"labels.json malformed: {exc}")
        return issues

    labels_list = labels_doc.get('labels') or []
    if not labels_list:
        issues.append("labels.json contains no labels")
        return issues
    if len(labels_list) != len(set(labels_list)):
        issues.append("labels.json contains duplicate entries")

    head = labels_list[:len(required)]
    if head != required:
        issues.append(f"First {len(required)} labels must be {required}, got {head}")

    if manifest.get('labels_name') != labels_doc.get('name'):
        issues.append("labels_name mismatch")
    if str(manifest.get('labels_version')) != str(labels_doc.get('version')):
        issues.append("labels_version mismatch")

    return issues


def evaluate_artifacts(artifacts: List[Dict]) -> ComplianceResult:
    issues: List[str] = []
    for artifact in artifacts:
        fmt = artifact.get('format') or 'unknown'
        path_str = artifact.get('path')
        if not path_str:
            issues.append(f"{fmt}: missing path metadata")
            continue
        issue = check_artifact(Path(path_str), fmt)
        if issue:
            issues.append(issue)
    status = 'passed' if not issues else 'failed'
    return ComplianceResult(status=status, issues=issues)


def evaluate_bundle(manifest_path: Path, labels_path: Path, artifacts: List[Dict], required_labels: List[str]) -> ComplianceResult:
    label_issues = validate_labels(manifest_path, labels_path, required_labels)
    artifact_result = evaluate_artifacts(artifacts)
    issues = label_issues + artifact_result.issues
    status = 'passed' if not issues else 'failed'
    return ComplianceResult(status=status, issues=issues)


__all__ = ['ComplianceResult', 'evaluate_bundle', 'evaluate_artifacts', 'validate_labels']
