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
