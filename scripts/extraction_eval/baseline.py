"""Baseline persistence and drift detection."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json


@dataclass(frozen=True)
class ChangedField:
    id: str
    field: str
    baseline: object
    latest: object


def diff_against_baseline(*, latest: Path, baseline: Path) -> list[ChangedField]:
    if not baseline.exists():
        return []
    base = {s["id"]: s for s in json.loads(baseline.read_text())["samples"]}
    out: list[ChangedField] = []
    for s in json.loads(latest.read_text())["samples"]:
        if s["id"] not in base:
            continue
        old = base[s["id"]].get("parsed", {})
        new = s.get("parsed", {})
        for k in set(old) | set(new):
            if old.get(k) != new.get(k):
                out.append(ChangedField(s["id"], k, old.get(k), new.get(k)))
    return out


def per_field_accuracy(samples: list[dict]) -> dict[str, tuple[int, int]]:
    counts: dict[str, list[int]] = {}
    for s in samples:
        for d in s.get("field_diff", []):
            f = d["field"]
            ok = 1 if d["status"] == "match" else 0
            slot = counts.setdefault(f, [0, 0])
            slot[0] += ok
            slot[1] += 1
    return {k: (v[0], v[1]) for k, v in counts.items()}


def promote_baseline(*, latest: Path, baseline: Path) -> None:
    baseline.write_text(latest.read_text())
