"""Generate pre-annotation candidates using OCR + heuristics."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import List

import pytesseract
from PIL import Image

CODE_PATTERN = re.compile(r"(?<![A-Z0-9])[A-Z0-9]{5,20}(?![A-Z0-9])")
EXPIRY_ANCHOR = re.compile(r"(valid|expires|until|by)", re.IGNORECASE)
DATE_PATTERN = re.compile(
    r"((?:\d{1,2}[/-]){1,2}\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9}\s*\d{2,4}|[A-Za-z]{3,9}\s+\d{1,2},?\s*'\d{2})"
)
DISCOUNT_PATTERN = re.compile(r"(\d+%|₹\s*\d+|rs\.?\s*\d+)", re.IGNORECASE)


@dataclass
class Candidate:
    category: str
    confidence: float
    text: str
    bbox: List[int]  # [xmin, ymin, xmax, ymax]


def _parse_boxes(image_path: Path) -> List[Candidate]:
    image = Image.open(image_path)
    data = pytesseract.image_to_data(image, output_type=pytesseract.Output.DICT)
    candidates: List[Candidate] = []
    n_boxes = len(data["text"])
    for i in range(n_boxes):
        text = data["text"][i].strip()
        if not text:
            continue
        conf = float(data["conf"][i]) if data["conf"][i] != "-1" else 0.0
        left = int(data["left"][i])
        top = int(data["top"][i])
        width = int(data["width"][i])
        height = int(data["height"][i])
        bbox = [left, top, left + width, top + height]
        candidates.append(Candidate(category="text", confidence=conf / 100.0, text=text, bbox=bbox))
    return candidates


def generate_candidates(image_path: Path | str) -> List[Candidate]:
    """Return candidate regions classified as code/expiry/benefit."""
    image_path = Path(image_path)
    raw_candidates = _parse_boxes(image_path)
    results: List[Candidate] = []

    for cand in raw_candidates:
        text = cand.text
        score = cand.confidence
        if CODE_PATTERN.search(text):
            results.append(Candidate("code_region", max(score, 0.8), text, cand.bbox))
        if EXPIRY_ANCHOR.search(text) or DATE_PATTERN.search(text):
            results.append(Candidate("expiry_region", max(score, 0.6), text, cand.bbox))
        if DISCOUNT_PATTERN.search(text):
            results.append(Candidate("benefit_region", max(score, 0.5), text, cand.bbox))

    return results
