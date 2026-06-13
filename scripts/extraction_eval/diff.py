"""Compare extraction output against expected fields."""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
from typing import Any
from datetime import datetime
import re

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

_DATE_FORMATS = (
    "%Y-%m-%d",
    "%d %B, %Y",
    "%d %B, %Y, %I:%M %p",
    "%d %b, %Y",
    "%d %b, %Y, %I:%M %p",
)

def _normalize_date(value: str) -> str | None:
    cleaned = re.sub(r"\s+", " ", value.strip())
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(cleaned, fmt).date().isoformat()
        except ValueError:
            continue
    return None

def _normalize_text(value: str) -> str:
    lowered = value.strip().lower()
    unquoted = lowered.replace('"', "").replace("'", "")
    return re.sub(r"\s+", " ", unquoted)

def _normalize(field: str, v: Any) -> Any:
    if isinstance(v, str):
        if field == "expiryDate":
            return _normalize_date(v) or _normalize_text(v)
        return _normalize_text(v)
    if isinstance(v, list):
        return [_normalize(field, item) for item in v]
    return v

def compare_fields(*, expected: dict, got: dict) -> list[FieldDiff]:
    out: list[FieldDiff] = []
    for k, v in expected.items():
        if k not in got:
            out.append(FieldDiff(k, v, None, FieldStatus.MISSING))
        elif _normalize(k, got[k]) == _normalize(k, v):
            out.append(FieldDiff(k, v, got[k], FieldStatus.MATCH))
        else:
            out.append(FieldDiff(k, v, got[k], FieldStatus.WRONG))
    for k, v in got.items():
        if k not in expected:
            out.append(FieldDiff(k, None, v, FieldStatus.EXTRA))
    return out
