"""Utility helpers for post-processing OCR extraction results."""

from __future__ import annotations

from typing import Dict, Optional

from enhanced_field_extractor import EnhancedCouponFieldExtractor


class EnhancedOCRHelper:
    """Helper that orchestrates field regeneration from OCR output."""

    def __init__(self, field_extractor: Optional[EnhancedCouponFieldExtractor] = None) -> None:
        self._field_extractor = field_extractor or EnhancedCouponFieldExtractor()

    def regenerate_amount(
        self,
        ocr_text: str,
        structured_record: Optional[Dict[str, object]] = None,
    ) -> Optional[str]:
        """Return a regenerated amount if the OCR text produces a confident value."""

        record = structured_record or {}
        if record.get("amount_removed"):
            return None

        amount_info = self._field_extractor.extract_amount(ocr_text)
        if amount_info.get("confidence"):
            return amount_info.get("value")  # type: ignore[return-value]
        return None

    def extract_amount_info(self, ocr_text: str) -> Dict[str, Optional[object]]:
        """Expose raw amount extraction details for diagnostics and testing."""

        return self._field_extractor.extract_amount(ocr_text)
