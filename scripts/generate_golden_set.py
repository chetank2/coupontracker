#!/usr/bin/env python3
"""
Deterministic synthetic coupon-screenshot generator for the Phase 1 golden set.

- Reads a list of synthetic coupon definitions from inside this file.
- Renders one PNG per definition using Pillow (no external fonts required;
  falls back to PIL's default bitmap font so results are reproducible across
  machines).
- Writes:
    benchmark/goldenset/images/<id>.png
    benchmark/goldenset/manifest.json
    benchmark/goldenset/replay/<id>.json   (replay fixture equals expected)

Run: python3 scripts/generate_golden_set.py
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.stderr.write("Pillow is required: pip install Pillow\n")
    raise

ROOT = Path(__file__).resolve().parents[1]
IMG_DIR = ROOT / "benchmark" / "goldenset" / "images"
REPLAY_DIR = ROOT / "benchmark" / "goldenset" / "replay"
MANIFEST = ROOT / "benchmark" / "goldenset" / "manifest.json"

SAMPLES = [
    {
        "id": "ajio_flat50_clean",
        "brand": "AJIO",
        "lines": ["AJIO", "Flat 50% off", "Use code: SAVE50", "Valid till 01 Jun 2026"],
        "expected": {
            "storeName": "AJIO",
            "description": "Flat 50% off",
            "redeemCode": "SAVE50",
            "expiryDate": "2026-06-01",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["AJIO"],
            "needsAttention": False,
        },
    },
    {
        "id": "flipkart_big_saving",
        "brand": "Flipkart",
        "lines": ["Flipkart", "Big Saving Days", "Code FLIPBIG100", "Expires 15 Aug 2026"],
        "expected": {
            "storeName": "Flipkart",
            "description": "Big Saving Days",
            "redeemCode": "FLIPBIG100",
            "expiryDate": "2026-08-15",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Flipkart"],
            "needsAttention": False,
        },
    },
    {
        "id": "myntra_no_code",
        "brand": "Myntra",
        "lines": ["Myntra", "End of Reason Sale", "No code needed", "Ends 30 Sep 2026"],
        "expected": {
            "storeName": "Myntra",
            "description": "End of Reason Sale",
            "redeemCode": "unknown",
            "expiryDate": "2026-09-30",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Myntra"],
            "needsAttention": True,
        },
    },
    {
        "id": "zomato_gold_cashback",
        "brand": "Zomato",
        "lines": ["Zomato Gold", "Flat 20% cashback on dining", "ZGOLD20", "Till 31 Dec 2026"],
        "expected": {
            "storeName": "Zomato",
            "description": "Flat 20% cashback on dining",
            "redeemCode": "ZGOLD20",
            "expiryDate": "2026-12-31",
            "storeNameSource": "ocr",
            "storeNameEvidence": ["Zomato Gold"],
            "needsAttention": False,
        },
    },
    {
        "id": "ambiguous_low_signal",
        "brand": "unknown",
        "lines": ["SAVE NOW", "use code ABC123", "limited time"],
        "expected": {
            "storeName": "unknown",
            "description": "SAVE NOW limited time",
            "redeemCode": "ABC123",
            "expiryDate": "unknown",
            "storeNameSource": "fallback",
            "storeNameEvidence": [],
            "needsAttention": True,
        },
    },
]


def write_png(sample: dict) -> Path:
    img = Image.new("RGB", (480, 320), "white")
    draw = ImageDraw.Draw(img)
    y = 40
    for line in sample["lines"]:
        draw.text((30, y), line, fill="black")
        y += 40
    path = IMG_DIR / f"{sample['id']}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")
    return path


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    manifest = []
    REPLAY_DIR.mkdir(parents=True, exist_ok=True)
    for sample in SAMPLES:
        png = write_png(sample)
        sha = sha256_of(png)
        manifest.append({
            "id": sample["id"],
            "image": f"images/{sample['id']}.png",
            "brand": sample["brand"],
            "imageSha256": sha,
            "expected": sample["expected"],
        })
        replay_path = REPLAY_DIR / f"{sample['id']}.json"
        replay_path.write_text(json.dumps(sample["expected"], indent=2) + "\n")

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {len(manifest)} samples to {MANIFEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
