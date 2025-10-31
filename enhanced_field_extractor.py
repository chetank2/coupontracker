"""Lightweight coupon field extractor used in unit tests."""

from __future__ import annotations

import re
from collections import Counter, OrderedDict
from datetime import datetime
from typing import Dict, List, Optional


class EnhancedCouponFieldExtractor:
    """Utility class that provides heuristic coupon field extraction."""

    PERCENT_COMPONENT_PATTERN = re.compile(
        r"""
        (?P<component>
            (?:(?:\+|&|and|with)\s*)?
            (?:(?:get|enjoy|avail)\s+)?
            (?:(?:up\s*to|upto|extra|flat|save|additional|bonus|plus)\s+)*
            \d{1,3}(?:\.\d+)?
            \s*%
            (?:\s*(?:off|discount))?
        )
        """,
        re.IGNORECASE | re.VERBOSE,
    )

    COMBINED_PERCENT_PATTERN = re.compile(
        r"""
        (?P<combined>
            (?:(?:get|enjoy|avail)\s+)?
            (?:(?:up\s*to|upto|extra|flat|save|additional|bonus|plus)\s+)*
            \d{1,3}(?:\.\d+)?\s*%(?:\s*(?:off|discount))?
            (?:
                \s*(?:\+|&|and|with)\s*
                (?:(?:get|enjoy|avail)\s+)?
                (?:(?:up\s*to|upto|extra|flat|save|additional|bonus|plus)\s+)*
                \d{1,3}(?:\.\d+)?\s*%(?:\s*(?:off|discount))?
            )*
        )
        """,
        re.IGNORECASE | re.VERBOSE,
    )

    AMOUNT_WITH_CURRENCY_PATTERN = re.compile(
        r"""
        (?P<context>
            (?:(?:up\s*to|upto|flat|save|extra|get|worth|value|mrp|only|just|pay)\s*)?
        )
        (?P<currency>₹|rs\.?|inr|rupees)
        [\s₹]*
        (?P<number>\d[\d,]*(?:\.\d+)?)
        (?:\s*(?:off|cashback|discount|only|deal|each|/-))?
        """,
        re.IGNORECASE | re.VERBOSE,
    )

    AMOUNT_NUMERIC_CONTEXT_PATTERN = re.compile(
        r"""
        (?P<context>(?:up\s*to|upto|flat|save|extra|get|worth|value|mrp|only|just|pay)\s*)
        (?P<number>\d[\d,]*(?:\.\d+)?)
        (?:\s*(?:off|cashback|discount|only|deal|each|/-))?
        """,
        re.IGNORECASE | re.VERBOSE,
    )

    DATE_PATTERN = re.compile(
        r"\b\d{1,2}\s+[A-Za-z]{3,9},\s+\d{4}(?:,\s*\d{1,2}:\d{2}\s*(?:AM|PM))?",
        re.IGNORECASE,
    )

    STOPWORDS = {
        "at",
        "available",
        "deal",
        "deals",
        "details",
        "glow",
        "kit",
        "limited",
        "multi",
        "now",
        "only",
        "redeem",
        "sale",
        "store",
        "stores",
        "super",
        "skincare",
    }

    def _clean_text(self, text: str) -> str:
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        return "\n".join(lines)

    def _detect_store(self, cleaned_text: str) -> Dict[str, Optional[str]]:
        words = re.findall(r"\b[\w']+\b", cleaned_text)
        seen: "OrderedDict[str, str]" = OrderedDict()
        counter: Counter[str] = Counter()

        for word in words:
            lower = word.lower()
            if len(lower) < 3:
                continue
            if lower in self.STOPWORDS:
                continue
            if lower.isupper():
                continue
            counter[lower] += 1
            seen.setdefault(lower, word)

        if not counter:
            return {"type": "not_found", "name": None}

        best_lower, _ = counter.most_common(1)[0]
        return {"type": "extracted", "name": seen[best_lower]}

    def _extract_discount(self, cleaned_text: str) -> Dict[str, Optional[object]]:
        components: List[str] = []

        for match in self.PERCENT_COMPONENT_PATTERN.finditer(cleaned_text):
            normalised = self._normalise_percentage_component(match.group("component"))
            if not normalised:
                continue
            if normalised not in components:
                components.append(normalised)

        if not components:
            return {"type": "not_found", "value": None, "raw_text": None, "components": []}

        value = max(self._parse_percentage_value(component) for component in components)
        raw_text = " + ".join(components)
        return {"type": "percentage", "value": value, "raw_text": raw_text, "components": components}

    def _normalise_percentage_component(self, component: str) -> str:
        cleaned = component.strip().strip("*+")
        cleaned = re.sub(r"^[+&\s]*(?:and\s+|with\s+)?", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\b(get|enjoy|avail)\b\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s+", " ", cleaned)
        cleaned = cleaned.strip()
        if not cleaned:
            return ""

        cleaned = cleaned.lower()
        cleaned = cleaned.replace("upto", "up to")

        replacements = {
            "up to": "Up to",
            "extra": "Extra",
            "flat": "Flat",
            "save": "Save",
            "additional": "Additional",
            "bonus": "Bonus",
            "plus": "Plus",
            "off": "Off",
            "discount": "Discount",
        }

        def replace_keyword(match: re.Match[str]) -> str:
            return replacements[match.group(0).lower()]

        pattern = re.compile("|".join(re.escape(key) for key in replacements), re.IGNORECASE)
        cleaned = pattern.sub(replace_keyword, cleaned)
        cleaned = re.sub(r"%\s+Off", "% Off", cleaned)
        cleaned = re.sub(r"%\s+Discount", "% Discount", cleaned)
        return cleaned.strip()

    @staticmethod
    def _parse_percentage_value(component: str) -> int:
        match = re.search(r"\d{1,3}(?:\.\d+)?", component)
        if not match:
            return 0
        value = float(match.group(0))
        return int(value) if value.is_integer() else int(round(value))

    def _extract_expiry_date(self, cleaned_text: str) -> Dict[str, Optional[object]]:
        match = self.DATE_PATTERN.search(cleaned_text)
        if not match:
            return {"raw_text": None, "confidence": 0.0, "parsed_date": None}

        raw_text = match.group(0)
        parsed_date = self._parse_expiry_date(raw_text)
        confidence = 1.0 if parsed_date else 0.5
        return {"raw_text": raw_text, "confidence": confidence, "parsed_date": parsed_date}

    @staticmethod
    def _parse_expiry_date(raw_text: str) -> Optional[datetime]:
        for fmt in ("%d %B, %Y, %I:%M %p", "%d %B, %Y"):
            try:
                return datetime.strptime(raw_text, fmt)
            except ValueError:
                continue
        return None

    def extract_discount(self, text: str) -> Dict[str, Optional[object]]:
        cleaned = self._clean_text(text)
        return self._extract_discount(cleaned)

    def detect_store(self, text: str) -> Dict[str, Optional[str]]:
        cleaned = self._clean_text(text)
        return self._detect_store(cleaned)

    def extract_expiry_date(self, text: str) -> Dict[str, Optional[object]]:
        cleaned = self._clean_text(text)
        return self._extract_expiry_date(cleaned)

    def _extract_amount(self, cleaned_text: str) -> Dict[str, Optional[object]]:
        confident_matches: List[Dict[str, object]] = []

        for match in self.AMOUNT_WITH_CURRENCY_PATTERN.finditer(cleaned_text):
            number = match.group("number")
            if not number:
                continue
            numeric_value = self._parse_amount_numeric(number)
            if numeric_value <= 0:
                continue
            formatted = self._normalise_amount_value(number)
            confident_matches.append(
                {
                    "type": "currency_amount",
                    "value": formatted,
                    "raw_text": match.group(0).strip(),
                    "confidence": True,
                    "_numeric": numeric_value,
                }
            )

        if confident_matches:
            best = max(confident_matches, key=lambda candidate: candidate["_numeric"])
            best.pop("_numeric", None)
            return best

        numeric_match = self.AMOUNT_NUMERIC_CONTEXT_PATTERN.search(cleaned_text)
        if numeric_match:
            return {
                "type": "numeric_context",
                "value": None,
                "raw_text": None,
                "confidence": False,
            }

        return {
            "type": "not_found",
            "value": None,
            "raw_text": None,
            "confidence": False,
        }

    @staticmethod
    def _parse_amount_numeric(number: str) -> float:
        cleaned = number.replace(",", "").strip()
        try:
            return float(cleaned)
        except ValueError:
            return 0.0

    @staticmethod
    def _normalise_amount_value(number: str) -> str:
        cleaned = number.replace(",", "").strip()
        return f"₹{cleaned}"

    def extract_amount(self, text: str) -> Dict[str, Optional[object]]:
        cleaned = self._clean_text(text)
        return self._extract_amount(cleaned)
