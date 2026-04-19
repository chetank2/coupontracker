#!/usr/bin/env python3
"""
Multi-coupon synthetic page generator. Renders pages containing 2-4 stacked
coupons. Each page becomes one entry in benchmark/goldenset/multi/manifest.json
with `expected` as an ARRAY of canonical coupon JSONs (one per visible coupon).
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
IMG_DIR = ROOT / "benchmark" / "goldenset" / "multi" / "images"
REPLAY_DIR = ROOT / "benchmark" / "goldenset" / "multi" / "replay"
MANIFEST = ROOT / "benchmark" / "goldenset" / "multi" / "manifest.json"

PAGES = [
    {
        "id": "two_coupon_ajio_flipkart",
        "panels": [
            {
                "lines": ["AJIO", "Flat 50% off", "SAVE50", "Valid 01 Jun 2026"],
                "canonical": {
                    "storeName": "AJIO", "description": "Flat 50% off",
                    "redeemCode": "SAVE50", "expiryDate": "2026-06-01",
                    "storeNameSource": "ocr", "storeNameEvidence": ["AJIO"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["Flipkart", "Big Saving", "FLIP100", "Expires 15 Aug 2026"],
                "canonical": {
                    "storeName": "Flipkart", "description": "Big Saving",
                    "redeemCode": "FLIP100", "expiryDate": "2026-08-15",
                    "storeNameSource": "ocr", "storeNameEvidence": ["Flipkart"],
                    "needsAttention": False,
                },
            },
        ],
    },
    {
        "id": "three_coupon_mixed",
        "panels": [
            {
                "lines": ["Myntra", "End of Reason Sale", "MYNTRA20", "31 Aug 2026"],
                "canonical": {
                    "storeName": "Myntra", "description": "End of Reason Sale",
                    "redeemCode": "MYNTRA20", "expiryDate": "2026-08-31",
                    "storeNameSource": "ocr", "storeNameEvidence": ["Myntra"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["Zomato Gold", "20% cashback", "ZGOLD20", "31 Dec 2026"],
                "canonical": {
                    "storeName": "Zomato", "description": "20% cashback",
                    "redeemCode": "ZGOLD20", "expiryDate": "2026-12-31",
                    "storeNameSource": "ocr", "storeNameEvidence": ["Zomato Gold"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["Swiggy", "Free delivery", "SWIGGYFREE", "30 Sep 2026"],
                "canonical": {
                    "storeName": "Swiggy", "description": "Free delivery",
                    "redeemCode": "SWIGGYFREE", "expiryDate": "2026-09-30",
                    "storeNameSource": "ocr", "storeNameEvidence": ["Swiggy"],
                    "needsAttention": False,
                },
            },
        ],
    },
    {
        "id": "four_coupon_diverse",
        "panels": [
            {
                "lines": ["Amazon", "10% off electronics", "AMZ10ELEC", "30 Nov 2026"],
                "canonical": {
                    "storeName": "Amazon", "description": "10% off electronics",
                    "redeemCode": "AMZ10ELEC", "expiryDate": "2026-11-30",
                    "storeNameSource": "ocr", "storeNameEvidence": ["Amazon"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["CRED", "Pay bill cashback", "CREDPAY", "31 Oct 2026"],
                "canonical": {
                    "storeName": "CRED", "description": "Pay bill cashback",
                    "redeemCode": "CREDPAY", "expiryDate": "2026-10-31",
                    "storeNameSource": "ocr", "storeNameEvidence": ["CRED"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["PhonePe", "Wallet topup", "PPWALLET", "01 Dec 2026"],
                "canonical": {
                    "storeName": "PhonePe", "description": "Wallet topup",
                    "redeemCode": "PPWALLET", "expiryDate": "2026-12-01",
                    "storeNameSource": "ocr", "storeNameEvidence": ["PhonePe"],
                    "needsAttention": False,
                },
            },
            {
                "lines": ["Generic offer", "no brand", "GEN50", "limited time"],
                "canonical": {
                    "storeName": "unknown", "description": "Generic offer no brand limited time",
                    "redeemCode": "GEN50", "expiryDate": "unknown",
                    "storeNameSource": "fallback", "storeNameEvidence": [],
                    "needsAttention": True,
                },
            },
        ],
    },
]


def render_page(page: dict) -> Path:
    panels = page["panels"]
    panel_height = 140
    img = Image.new("RGB", (520, panel_height * len(panels) + 40), "white")
    draw = ImageDraw.Draw(img)
    y = 20
    for i, panel in enumerate(panels):
        if i > 0:
            draw.line([(0, y - 10), (520, y - 10)], fill="black", width=2)
        for line in panel["lines"]:
            draw.text((30, y), line, fill="black")
            y += 24
        y += 20
    path = IMG_DIR / f"{page['id']}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")
    return path


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    REPLAY_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    for page in PAGES:
        png = render_page(page)
        sha = sha256_of(png)
        expected = [panel["canonical"] for panel in page["panels"]]
        manifest.append({
            "id": page["id"],
            "image": f"images/{page['id']}.png",
            "imageSha256": sha,
            "expected": expected,
        })
        replay_path = REPLAY_DIR / f"{page['id']}.json"
        replay_path.write_text(json.dumps(expected, indent=2) + "\n")

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {len(manifest)} multi-coupon pages to {MANIFEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
