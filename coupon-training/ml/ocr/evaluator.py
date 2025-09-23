"""OCR evaluation helpers using Tesseract as a baseline recognizer."""

from __future__ import annotations

import logging
from collections import defaultdict
from difflib import SequenceMatcher
from typing import Dict

import pytesseract
from PIL import Image

from ml.data.manifest import Manifest

LOGGER = logging.getLogger("ml.ocr.evaluator")


def _sequence_similarity(a: str | None, b: str | None) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return SequenceMatcher(None, a.strip().upper(), b.strip().upper()).ratio()


def evaluate_with_tesseract(manifest: Manifest) -> Dict[str, float]:
    """Compute average OCR similarity per category on the test split."""
    scores: Dict[str, list[float]] = defaultdict(list)

    for annotation in manifest.by_split("test"):
        try:
            image = Image.open(annotation.image_path).convert("RGB")
        except Exception as exc:  # pragma: no cover
            LOGGER.warning("Failed to load %s: %s", annotation.image_path, exc)
            continue

        for bbox in annotation.bboxes:
            if not bbox.text:
                continue
            xmin = max(0, int(bbox.xmin))
            ymin = max(0, int(bbox.ymin))
            xmax = max(xmin + 1, int(bbox.xmax))
            ymax = max(ymin + 1, int(bbox.ymax))
            crop = image.crop((xmin, ymin, xmax, ymax))
            ocr_text = pytesseract.image_to_string(crop, config="--psm 7").strip()
            similarity = _sequence_similarity(bbox.text, ocr_text)
            scores[bbox.category].append(similarity)

    return {label: sum(vals) / len(vals) for label, vals in scores.items() if vals}
