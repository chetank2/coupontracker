from __future__ import annotations

import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parents[2]))

from enhanced_field_extractor import EnhancedCouponFieldExtractor
from utils.ocr_helper import EnhancedOCRHelper


def test_currency_amount_has_confidence() -> None:
    extractor = EnhancedCouponFieldExtractor()
    result = extractor.extract_amount("Flat ₹200 off on footwear")

    assert result["confidence"] is True
    assert result["value"] == "₹200"


def test_plain_flat_number_is_low_confidence() -> None:
    extractor = EnhancedCouponFieldExtractor()
    result = extractor.extract_amount("Flat 200 off on footwear")

    assert result["confidence"] is False
    assert result["type"] == "numeric_context"
    assert result["raw_text"] is None


def test_helper_ignores_low_confidence_amount() -> None:
    helper = EnhancedOCRHelper()

    regenerated = helper.regenerate_amount("Flat 200 off on footwear", {})

    assert regenerated is None


def test_helper_respects_amount_removed_flag() -> None:
    helper = EnhancedOCRHelper()

    regenerated = helper.regenerate_amount(
        "Flat ₹200 off on footwear",
        {"amount": None, "amount_removed": True},
    )

    assert regenerated is None


def test_helper_regenerates_confident_amount_when_allowed() -> None:
    helper = EnhancedOCRHelper()

    regenerated = helper.regenerate_amount("Extra ₹500 cashback on travel", {"amount": None})

    assert regenerated == "₹500"
