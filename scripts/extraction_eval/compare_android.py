"""Compare Mac harness results with an Android extraction smoke report."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any
import json

from extraction_eval.backend_check import assert_backend_parity


@dataclass(frozen=True)
class Disagreement:
    id: str
    field: str
    mac: Any
    android: Any


@dataclass(frozen=True)
class CompareReport:
    disagreements: list[Disagreement]
    latency: dict[str, dict[str, int | None]]
    missing_on_android: list[str]
    missing_on_mac: list[str]

    @property
    def passed(self) -> bool:
        return not self.disagreements and not self.missing_on_android and not self.missing_on_mac


def _load_samples(path: Path) -> dict[str, dict[str, Any]]:
    data = json.loads(path.read_text())
    return {sample["id"]: sample for sample in data.get("samples", [])}


def _normalize(value: Any) -> Any:
    if isinstance(value, str):
        return value.strip().lower()
    if isinstance(value, list):
        return [_normalize(item) for item in value]
    if isinstance(value, dict):
        return {key: _normalize(value[key]) for key in sorted(value)}
    return value


def compare(*, mac: Path, android: Path) -> CompareReport:
    assert_backend_parity(android, expected="llama.cpp")
    mac_samples = _load_samples(mac)
    android_samples = _load_samples(android)
    mac_ids = set(mac_samples)
    android_ids = set(android_samples)

    disagreements: list[Disagreement] = []
    latency: dict[str, dict[str, int | None]] = {}
    for sample_id in sorted(mac_ids & android_ids):
        mac_sample = mac_samples[sample_id]
        android_sample = android_samples[sample_id]
        mac_parsed = mac_sample.get("parsed", {})
        android_parsed = android_sample.get("parsed", {})
        for field in sorted(set(mac_parsed) | set(android_parsed)):
            mac_value = mac_parsed.get(field)
            android_value = android_parsed.get(field)
            if _normalize(mac_value) != _normalize(android_value):
                disagreements.append(Disagreement(sample_id, field, mac_value, android_value))
        latency[sample_id] = {
            "mac_ms": mac_sample.get("latency_ms"),
            "android_ms": android_sample.get("latency_ms"),
        }

    return CompareReport(
        disagreements=disagreements,
        latency=latency,
        missing_on_android=sorted(mac_ids - android_ids),
        missing_on_mac=sorted(android_ids - mac_ids),
    )
