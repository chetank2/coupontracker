"""Compare extraction output against expected fields."""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
from typing import Any

class FieldStatus(str, Enum):
    MATCH = "match"
    MISSING = "missing"
    WRONG = "wrong"
    EXTRA = "extra"

@dataclass(frozen=True)
class FieldDiff:
    field: str
    expected: Any
    got: Any
    status: FieldStatus

def _normalize(v: Any) -> Any:
    if isinstance(v, str):
        return v.strip().lower()
    return v

def compare_fields(*, expected: dict, got: dict) -> list[FieldDiff]:
    out: list[FieldDiff] = []
    for k, v in expected.items():
        if k not in got:
            out.append(FieldDiff(k, v, None, FieldStatus.MISSING))
        elif _normalize(got[k]) == _normalize(v):
            out.append(FieldDiff(k, v, got[k], FieldStatus.MATCH))
        else:
            out.append(FieldDiff(k, v, got[k], FieldStatus.WRONG))
    for k, v in got.items():
        if k not in expected:
            out.append(FieldDiff(k, None, v, FieldStatus.EXTRA))
    return out
