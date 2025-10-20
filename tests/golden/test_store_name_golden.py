import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parents[1]))
sys.path.append(str(Path(__file__).resolve().parents[2]))

from enhanced_field_extractor import EnhancedCouponFieldExtractor


GOLDEN_DATA = [
    {
        "ocr": "Minimalist glow kit\nRedeem at Minimalist stores",
        "expected_store": "Minimalist",
    },
    {
        "ocr": "Amazon Festive Deals\nRedeem at Amazon",
        "expected_store": "Amazon",
    },
    {
        "ocr": "Flipkart Flash Sale\nRedeem at Flipkart Mall",
        "expected_store": "Flipkart",
    },
]


@pytest.mark.parametrize("case", GOLDEN_DATA)
def test_store_name_matches_golden_cases(case):
    extractor = EnhancedCouponFieldExtractor()
    cleaned = extractor._clean_text(case["ocr"])
    result = extractor._detect_store(cleaned)

    assert result["type"] == "extracted"
    assert result["name"].lower() == case["expected_store"].lower()
