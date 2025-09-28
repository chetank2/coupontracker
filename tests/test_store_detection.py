import sys
import types
from datetime import datetime
from pathlib import Path

import pytest

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


def test_detect_store_ignores_trailing_code_token_for_known_brand():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Exclusive coupon launch
        Redeem at PUMA
        Code: KQSKLBLBIR
    """

    cleaned_text = extractor._clean_text(ocr_text)
    result = extractor._detect_store(cleaned_text)

    assert result['type'] == 'identified'
    assert result['name'] == 'PUMA'
    assert result['confidence'] >= 0.9


def test_detect_store_trims_trailing_code_word_when_brand_unknown():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Redeem now at Glow Code
        Use code GLOW2025 today
    """

    cleaned_text = extractor._clean_text(ocr_text)
    result = extractor._detect_store(cleaned_text)

    assert result['type'] == 'extracted'
    assert result['name'] == 'Glow'
    assert result['confidence'] >= 0.4


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


def test_extract_expiry_date_with_comma_and_time():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Expires on 05 May, 2025, 11:59 PM
    """

    cleaned_text = extractor._clean_text(ocr_text)
    expiry = extractor._extract_expiry_date(cleaned_text)

    assert expiry['raw_text'] == '05 May, 2025, 11:59 PM'
    assert expiry['confidence'] > 0.9
    assert expiry['parsed_date'] is not None
    assert expiry['is_expired'] is True


def test_extract_expiry_date_handles_ordinals_and_compact_meridiem():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Expires 30th Sept 2025 11:59pm
    """

    cleaned_text = extractor._clean_text(ocr_text)
    expiry = extractor._extract_expiry_date(cleaned_text)

    assert expiry['raw_text'] == '30th Sept 2025 11:59pm'
    assert expiry['parsed_date'] is not None
    assert expiry['parsed_date'].startswith('2025-09-30T23:59:00')
    assert expiry['confidence'] >= 0.9


def test_aha_coupon_fields_are_preserved():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        Get Flat 20.0% off
        on purchase of aha Telugu Annual Plan of ₹499, ₹699 or ₹999
        Code: ahappe20
        Expires on 30 Sep, 2025, 11:59 PM
    """

    cleaned_text = extractor._clean_text(ocr_text)

    store = extractor._detect_store(cleaned_text)
    assert store['name'] == 'Aha'

    discount = extractor._extract_discount(cleaned_text)
    assert discount['type'] == 'percentage'
    assert discount['raw_text'] == 'Flat 20% Off'
    assert discount['components'] == ['Flat 20% Off']

    expiry = extractor._extract_expiry_date(cleaned_text)
    assert expiry['raw_text'] == '30 Sep, 2025, 11:59 PM'

    code = extractor._extract_coupon_code(cleaned_text, store)
    assert code['code'] == 'ahappe20'

    terms = extractor._extract_terms(cleaned_text)
    assert '₹499' in terms['text']
    assert '₹699' in terms['text']
    assert '₹999' in terms['text']


def test_extract_fields_with_confidence_handles_combined_offer():
    extractor = EnhancedCouponFieldExtractor()

    ocr_text = """
        PUMA Coupon Up to 50.0% off + Extra 33% Off
        Redeem at PUMA outlets today
        Use code KQSKLBLBIR before it expires
        Expires on 05 May, 2025, 11:59 PM
    """

    fields = extractor.extract_fields_with_confidence(ocr_text)

    assert fields['store']['name'] == 'PUMA'
    assert fields['discount']['type'] == 'percentage'
    assert fields['discount']['raw_text'] == 'Up to 50% Off + Extra 33% Off'
    assert fields['discount']['components'] == ['Up to 50% Off', 'Extra 33% Off']
    assert fields['coupon_code']['code'] == 'KQSKLBLBIR'
    assert fields['expiry_date']['raw_text'] == '05 May, 2025, 11:59 PM'
    assert fields['expiry_date']['is_expired'] is True
    assert fields['overall_confidence'] > 0
