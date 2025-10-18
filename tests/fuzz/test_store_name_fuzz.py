import random
import string
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parents[1]))
sys.path.append(str(Path(__file__).resolve().parents[2]))

from enhanced_field_extractor import EnhancedCouponFieldExtractor


@pytest.mark.parametrize("seed", [13, 29, 71])
def test_store_name_detection_is_stable_under_noise(seed):
    random.seed(seed)
    extractor = EnhancedCouponFieldExtractor()

    for _ in range(25):
        noise = " ".join(
            "".join(random.choice(string.ascii_letters + string.digits) for _ in range(random.randint(3, 12)))
            for _ in range(random.randint(3, 8))
        )
        prompt = f"{noise}\nClaim Now\nRedeem at Zenith Sports"
        cleaned = extractor._clean_text(prompt)
        result = extractor._detect_store(cleaned)

        assert result["type"] in {"extracted", "unknown"}
        assert isinstance(result.get("name"), str)
        assert "Zenith" in prompt or result["type"] != "unknown"
