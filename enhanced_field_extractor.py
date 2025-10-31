"""Lightweight coupon field extractor used in unit tests."""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set

try:  # pragma: no cover - optional dependency for test environment
    import yaml
except Exception:  # pragma: no cover - fall back when PyYAML is unavailable
    yaml = None


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

    VERIFIED_MERCHANT_KEYWORDS = {"minimalist", "beminimalist"}

    _MERCHANT_HINTS_CACHE: Optional[Set[str]] = None

    def _clean_text(self, text: str) -> str:
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        return "\n".join(lines)

    def _detect_store(self, cleaned_text: str) -> Dict[str, Optional[str]]:
        tokens = re.findall(r"\b[\w']+\b", cleaned_text)
        if not tokens:
            return {"type": "not_found", "name": None}

        candidate_data: Dict[str, Dict[str, object]] = {}
        hints = self._get_known_merchant_hints()

        for index, token in enumerate(tokens):
            lower = token.lower()
            if not self._is_candidate_token(token):
                continue

            candidate = candidate_data.setdefault(
                lower,
                {
                    "display": token,
                    "count": 0,
                    "positions": [],
                    "penalty": 0.0,
                },
            )
            candidate["count"] = int(candidate["count"]) + 1
            positions: List[int] = candidate["positions"]  # type: ignore[assignment]
            positions.append(index)

            if index > 0 and tokens[index - 1].lower() == "the":
                candidate["penalty"] = float(candidate["penalty"]) + 0.35

            if str(candidate["display"]).islower() and not token.islower():
                candidate["display"] = token

        best_name: Optional[str] = None
        best_score = float("-inf")

        for lower, data in candidate_data.items():
            if lower in self.STOPWORDS:
                continue

            count = int(data["count"])
            penalty = float(data["penalty"])
            score = self._score_candidate(lower, count, penalty, data["positions"], hints)

            if score > best_score:
                best_score = score
                best_name = str(data["display"])

        if best_name is None or best_score <= 0:
            return {"type": "not_found", "name": None}

        return {"type": "extracted", "name": best_name}

    def _score_candidate(
        self,
        token: str,
        count: int,
        penalty: float,
        positions: Sequence[int],
        hints: Set[str],
    ) -> float:
        alpha_count = sum(1 for char in token if char.isalpha())
        if alpha_count < 3:
            return float("-inf")

        score = float(count) - penalty

        if token in hints:
            score += 1.5

        if token in self.VERIFIED_MERCHANT_KEYWORDS:
            score += count  # reward repeated mentions
            if len(positions) > 1:
                span = positions[-1] - positions[0]
                proximity_bonus = (len(positions) / (1 + span)) * 1.5
                score += proximity_bonus

        return score

    def _is_candidate_token(self, token: str) -> bool:
        lower = token.lower()
        if len(lower) < 3:
            return False
        if lower in self.STOPWORDS:
            return False
        if token.isupper():
            return False
        if not any(char.isalpha() for char in token):
            return False
        return True

    @classmethod
    def _get_known_merchant_hints(cls) -> Set[str]:
        if cls._MERCHANT_HINTS_CACHE is not None:
            return cls._MERCHANT_HINTS_CACHE

        hints: Set[str] = set()
        if yaml is not None:
            config_path = Path(__file__).resolve().parent / "config" / "merchant_aliases.yaml"
            if config_path.exists():
                try:
                    data = yaml.safe_load(config_path.read_text()) or {}
                    hints.update(cls._flatten_merchant_aliases(data))
                except Exception:
                    hints.update(cls.VERIFIED_MERCHANT_KEYWORDS)
        hints.update(cls.VERIFIED_MERCHANT_KEYWORDS)
        cls._MERCHANT_HINTS_CACHE = {hint.lower() for hint in hints}
        return cls._MERCHANT_HINTS_CACHE

    @staticmethod
    def _flatten_merchant_aliases(data: object) -> Iterable[str]:
        if isinstance(data, dict):
            for key, value in data.items():
                if isinstance(key, str):
                    yield key
                if isinstance(value, (list, tuple, set)):
                    for item in value:
                        if isinstance(item, str):
                            yield item
                elif isinstance(value, dict):
                    yield from EnhancedCouponFieldExtractor._flatten_merchant_aliases(value)
        elif isinstance(data, (list, tuple, set)):
            for item in data:
                if isinstance(item, str):
                    yield item
                elif isinstance(item, (list, tuple, set, dict)):
                    yield from EnhancedCouponFieldExtractor._flatten_merchant_aliases(item)

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
