#!/usr/bin/env python3
"""Generate a CSV summarising missing fields in coupon annotations."""

import os
import json
import csv
from collections import Counter
from typing import Dict, Any

ANNOT_DIR = os.environ.get("ANNOT_DIR", "data/annotated")
OUTPUT_PATH = os.environ.get("OUTPUT_PATH", "reports/annotation_gaps.csv")
FIELD_ORDER = ["store", "partner", "discount", "code", "expiry_date", "min_order"]


def classification(fields: Dict[str, Any]) -> Dict[str, bool]:
    """Return a bool map indicating whether each field has a trustworthy value."""
    status = {}
    for key in FIELD_ORDER:
        value = fields.get(key)
        has_value = bool(value) and not (isinstance(value, str) and value.lower().startswith("unknown"))
        status[key] = has_value
    return status


def main() -> None:
    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    coverage = Counter()
    missing = Counter()
    rows = []

    for entry in sorted(os.listdir(ANNOT_DIR)):
        if not entry.endswith(".json"):
            continue
        path = os.path.join(ANNOT_DIR, entry)
        try:
            with open(path, "r") as handle:
                data = json.load(handle)
        except Exception as exc:
            print(f"Skipping {entry}: {exc}")
            continue
        if not isinstance(data, dict):
            continue

        fields = data.get("fields") or {}
        status = classification(fields)
        missing_fields = [key for key, valid in status.items() if not valid]

        for key, valid in status.items():
            if valid:
                coverage[key] += 1
            else:
                missing[key] += 1

        if missing_fields:
            rows.append({
                "annotation": entry,
                "image_path": data.get("image_path", ""),
                "missing_fields": ";".join(missing_fields)
            })

    with open(OUTPUT_PATH, "w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=["annotation", "image_path", "missing_fields"])
        writer.writeheader()
        for row in rows:
            writer.writerow(row)

    print(f"Wrote {len(rows)} entries to {OUTPUT_PATH}")
    print("Coverage counts:")
    for key in FIELD_ORDER:
        print(f"  {key:12}: {coverage[key]}")
    print("Missing counts:")
    for key in FIELD_ORDER:
        print(f"  {key:12}: {missing[key]}")


if __name__ == "__main__":
    main()
