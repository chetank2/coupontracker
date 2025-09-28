import sys
import types
from datetime import datetime
from pathlib import Path

import pytest

# Provide lightweight stubs for optional heavy dependencies so the module can be imported
if 'numpy' not in sys.modules:
    sys.modules['numpy'] = types.SimpleNamespace(mean=lambda values: sum(values) / len(values) if values else 0.0)

if 'fuzzywuzzy' not in sys.modules:
    fuzz_module = types.SimpleNamespace(partial_ratio=lambda a, b: 0)
    process_module = types.SimpleNamespace()
    fuzzy_module = types.ModuleType('fuzzywuzzy')
    fuzzy_module.fuzz = fuzz_module
    fuzzy_module.process = process_module
    sys.modules['fuzzywuzzy'] = fuzzy_module
    sys.modules['fuzzywuzzy.fuzz'] = fuzz_module
    sys.modules['fuzzywuzzy.process'] = process_module

if 'dateutil' not in sys.modules:
    parser_module = types.SimpleNamespace(parse=lambda value, fuzzy=False: datetime.now())
    dateutil_module = types.ModuleType('dateutil')
    dateutil_module.parser = parser_module
    sys.modules['dateutil'] = dateutil_module
    sys.modules['dateutil.parser'] = parser_module

sys.path.append(str(Path(__file__).resolve().parents[1]))

from enhanced_field_extractor import EnhancedCouponFieldExtractor


def test_detect_store_prefers_mixed_case_brand_over_short_all_caps():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        SUPER SAVER SALE
        MULTI BUY DEALS
        Redeem at Minimalist skincare stores
        Minimalist glow kit available now
        MULTI MULTI MULTI
        minimal details only
    """

    cleaned_text = extractor._clean_text(ocr_text)
    result = extractor._detect_store(cleaned_text)

    assert result['type'] == 'extracted'
    assert result['name'] == 'Minimalist'


def test_extract_discount_handles_combined_percentage_phrases():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Get Upto 50% Off* + Extra 33% Off
        at PUMA
        Code: KQSKLBLBIR
        Expires on 05 May, 2025, 11:59 PM
    """

    cleaned_text = extractor._clean_text(ocr_text)
    discount = extractor._extract_discount(cleaned_text)

    assert discount['type'] == 'percentage'
    assert discount['value'] == 50
    assert discount['raw_text'] == 'Up to 50% Off + Extra 33% Off'
    assert discount['components'] == ['Up to 50% Off', 'Extra 33% Off']


@pytest.fixture
def frozen_datetime_now(monkeypatch):
    fixed_now = datetime(2026, 1, 1, 0, 0, 0)

    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            if tz is None:
                return fixed_now
            if fixed_now.tzinfo is None:
                return fixed_now.replace(tzinfo=tz)
            return fixed_now.astimezone(tz)

    monkeypatch.setattr("enhanced_field_extractor.datetime", FrozenDateTime)
    return fixed_now


def test_extract_expiry_date_with_comma_and_time(frozen_datetime_now):
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Expires on 05 May, 2025, 11:59 PM
    """

    cleaned_text = extractor._clean_text(ocr_text)
    expiry = extractor._extract_expiry_date(cleaned_text)

    assert expiry['raw_text'] == '05 May, 2025, 11:59 PM'
    assert expiry['confidence'] > 0.9
    assert expiry['parsed_date'] is not None
