#!/usr/bin/env python3
"""Utility helpers for extracting structured coupon details from OCR text."""

from __future__ import annotations

import logging
import re
from datetime import datetime
from difflib import SequenceMatcher
from typing import Dict, Iterable, List, Optional, Tuple

logger = logging.getLogger(__name__)


MONTH_NORMALIZATION = {
    "jan.": "Jan",
    "feb.": "Feb",
    "mar.": "Mar",
    "apr.": "Apr",
    "jun.": "Jun",
    "jul.": "Jul",
    "aug.": "Aug",
    "sept": "Sep",
    "sept.": "Sep",
    "sep.": "Sep",
    "oct.": "Oct",
    "nov.": "Nov",
    "dec.": "Dec",
}

EXPIRY_DATE_FORMATS = [
    "%d/%m/%Y",
    "%d/%m/%y",
    "%d-%m-%Y",
    "%d-%m-%y",
    "%Y-%m-%d",
    "%d %m %Y",
    "%d %b %Y",
    "%d %b, %Y",
    "%d %B %Y",
    "%d %B, %Y",
    "%d %b %Y %H:%M",
    "%d %b, %Y %H:%M",
    "%d %B %Y %H:%M",
    "%d %B, %Y %H:%M",
    "%d %b %Y, %H:%M",
    "%d %b, %Y, %H:%M",
    "%d %B %Y, %H:%M",
    "%d %B, %Y, %H:%M",
    "%d %b %Y %I:%M %p",
    "%d %b, %Y %I:%M %p",
    "%d %B %Y %I:%M %p",
    "%d %B, %Y %I:%M %p",
    "%d %b %Y, %I:%M %p",
    "%d %b, %Y, %I:%M %p",
    "%d %B %Y, %I:%M %p",
    "%d %B, %Y, %I:%M %p",
    "%d %b %Y %I:%M%p",
    "%d %b, %Y %I:%M%p",
    "%d %B %Y %I:%M%p",
    "%d %B, %Y %I:%M%p",
    "%d %b %Y, %I:%M%p",
    "%d %b, %Y, %I:%M%p",
    "%d %B %Y, %I:%M%p",
    "%d %B, %Y, %I:%M%p",
]


STORE_PROFILES: Dict[str, Dict[str, Iterable[str]]] = {
    "amazon": {
        "display_name": "Amazon",
        "identifiers": ("amazon", "amzn", "amazon.in"),
        "code_patterns": (r"[A-Z0-9]{8,12}",),
    },
    "flipkart": {
        "display_name": "Flipkart",
        "identifiers": ("flipkart", "fkrt", "flipkart.com"),
        "code_patterns": (r"[A-Z]{2,4}\d{4,8}", r"FK[A-Z0-9]{6,10}"),
    },
    "myntra": {
        "display_name": "Myntra",
        "identifiers": ("myntra", "myntra.com"),
        "code_patterns": (r"[A-Z]{3,5}\d{3,6}",),
    },
    "swiggy": {
        "display_name": "Swiggy",
        "identifiers": ("swiggy", "swiggy.com"),
        "code_patterns": (r"[A-Z]{4,6}\d{2,4}",),
    },
    "zomato": {
        "display_name": "Zomato",
        "identifiers": ("zomato", "zomato.com"),
        "code_patterns": (r"[A-Z]{4,6}\d{2,4}",),
    },
    "puma": {
        "display_name": "PUMA",
        "identifiers": ("puma", "puma.in", "puma.com"),
        "code_patterns": (r"[A-Z0-9]{6,12}",),
    },
    "aha": {
        "display_name": "Aha",
        "identifiers": ("aha", "aha.video", "aha app"),
        "code_patterns": (r"[A-Za-z0-9]{6,12}",),
    },
}

TRAILING_STORE_TOKENS = {
    "code",
    "codes",
    "coupon",
    "coupons",
    "offer",
    "offers",
    "promo",
    "promocode",
    "store",
    "stores",
    "outlet",
    "outlets",
    "shop",
    "shops",
}

STORE_STOPWORDS = {
    "redeem",
    "coupon",
    "coupons",
    "code",
    "codes",
    "offer",
    "offers",
    "flat",
    "discount",
    "save",
    "multi",
    "mega",
    "super",
    "valid",
    "expires",
    "get",
    "extra",
    "cashback",
    "details",
    "unknown",
    "purchase",
}

CODE_FALSE_POSITIVES = {
    "CODE",
    "COUPON",
    "COUPONCODE",
    "OFFER",
    "OFFERS",
    "DISCOUNT",
    "STORE",
    "STORES",
    "SHOP",
    "SHOPPING",
    "SAVE",
    "FREE",
    "EXTRA",
    "FLAT",
    "SPECIAL",
    "PUMA",
    "AHA",
    "AMAZON",
    "FLIPKART",
    "MYNTRA",
    "SWIGGY",
    "ZOMATO",
}

PERCENT_COMPONENT_PATTERN = re.compile(
    r"((?:up\s*to|upto|flat|extra|save)\s*\d+(?:\.\d+)?%\s*(?:off|discount)?)",
    re.IGNORECASE,
)
COMBINED_PERCENT_PATTERN = re.compile(
    r"((?:up\s*to|upto|flat|extra|save)\s*\d+(?:\.\d+)?%\s*(?:off|discount)?\s*(?:\+|&|and)\s*)+"
    r"(?:up\s*to|upto|flat|extra|save)\s*\d+(?:\.\d+)?%\s*(?:off|discount)?",
    re.IGNORECASE,
)
AMOUNT_PATTERN = re.compile(r"₹\s*(\d+(?:,\d{3})*)(?:\s*(?:off|discount))?", re.IGNORECASE)
EXPLICIT_CODE_PATTERNS = (
    r"(?:code|coupon|promo\s*code)\s*:?\s*([A-Za-z0-9]{4,12})",
    r"use\s+(?:code\s+)?([A-Za-z0-9]{4,12})",
)

EXPIRY_LINE_HINTS = ("expire", "valid", "expiry", "until", "till")
DATE_PATTERNS = (
    r"(\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]{3,9}\.?[,]?\s+\d{2,4}(?:,?\s*\d{1,2}:\d{2}\s*(?:[AP]M|am|pm)?)?)",
    r"(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}(?:\s*\d{1,2}:\d{2}\s*(?:[AP]M|am|pm)?)?)",
)

MIN_ORDER_PATTERNS = (
    (re.compile(r"minimum\s*(?:order|purchase)\s*₹\s*(\d+(?:,\d{3})*)", re.IGNORECASE), 0.9),
    (re.compile(r"order\s*above\s*₹\s*(\d+(?:,\d{3})*)", re.IGNORECASE), 0.8),
    (re.compile(r"min\.?\s*order\s*₹\s*(\d+(?:,\d{3})*)", re.IGNORECASE), 0.75),
)


def _partial_ratio(needle: str, haystack: str) -> float:
    """Return a lightweight partial ratio similar to fuzzy matching."""

    needle = needle.strip().lower()
    haystack = haystack.strip().lower()

    if not needle or not haystack:
        return 0.0

    if needle in haystack:
        return 1.0

    matcher = SequenceMatcher()
    matcher.set_seq2(needle)

    window = len(needle)
    best = 0.0
    for start in range(0, max(1, len(haystack) - window + 1)):
        segment = haystack[start : start + window]
        matcher.set_seq1(segment)
        best = max(best, matcher.ratio())
        if best > 0.98:
            break
    return best


class EnhancedCouponFieldExtractor:
    """Rule-driven coupon field extractor with lightweight heuristics."""

    def __init__(self) -> None:
        self.general_code_patterns: Tuple[str, ...] = (
            r"\b[A-Z0-9]{6,12}\b",
            r"\b[A-Za-z0-9]{4,12}(?=\s*(?:code|coupon))",
        )
        logger.debug("EnhancedCouponFieldExtractor initialised")

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def extract_fields_with_confidence(self, text: str) -> Dict[str, Dict[str, object]]:
        cleaned = self._clean_text(text)

        store = self._detect_store(cleaned)
        discount = self._extract_discount(cleaned)
        expiry = self._extract_expiry_date(cleaned)
        coupon_code = self._extract_coupon_code(cleaned, store)
        min_order = self._extract_min_order(cleaned)
        description = self._extract_description(cleaned)
        terms = self._extract_terms(cleaned)

        fields: Dict[str, Dict[str, object]] = {
            "store": store,
            "discount": discount,
            "coupon_code": coupon_code,
            "expiry_date": expiry,
            "min_order": min_order,
            "description": description,
            "terms": terms,
        }

        confidences = [
            value.get("confidence", 0.0)
            for value in fields.values()
            if isinstance(value, dict)
        ]
        overall_confidence = sum(confidences) / len(confidences) if confidences else 0.0
        fields["overall_confidence"] = overall_confidence

        metadata = {
            "text_length": len(text),
            "processed_text_length": len(cleaned),
            "store_detected": store.get("confidence", 0.0) >= 0.5,
            "fields_extracted": sum(1 for c in confidences if c >= 0.7),
        }
        fields["extraction_metadata"] = metadata
        return fields

    # ------------------------------------------------------------------
    # Cleaning utilities
    # ------------------------------------------------------------------
    def _clean_text(self, text: str) -> str:
        if not text:
            return ""

        cleaned = text.strip()
        cleaned = re.sub(r"\s+", " ", cleaned)
        cleaned = re.sub(r"rs\.?", "₹", cleaned, flags=re.IGNORECASE)
        cleaned = cleaned.replace("₹ ", "₹")
        return cleaned

    # ------------------------------------------------------------------
    # Store detection
    # ------------------------------------------------------------------
    def _detect_store(self, text: str) -> Dict[str, object]:
        lowered = text.lower()
        best_match: Dict[str, object] = {
            "name": None,
            "confidence": 0.0,
            "type": "unknown",
            "key": None,
        }

        for key, profile in STORE_PROFILES.items():
            confidence = 0.0
            for identifier in profile.get("identifiers", []):
                if identifier and identifier.lower() in lowered:
                    confidence = max(confidence, 0.9)
                else:
                    ratio = _partial_ratio(identifier, lowered)
                    confidence = max(confidence, 0.65 * ratio)

            if confidence > best_match["confidence"]:
                best_match = {
                    "name": profile.get("display_name", key.title()),
                    "confidence": confidence,
                    "type": "identified",
                    "key": key,
                }

        if best_match["confidence"] >= 0.5:
            return best_match

        candidate = self._extract_store_from_location(text)
        if candidate:
            return candidate

        fallback = self._most_frequent_capitalised_word(text)
        if fallback:
            return fallback

        return best_match

    def _extract_store_from_location(self, text: str) -> Optional[Dict[str, object]]:
        location_patterns = (
            r"(?:redeem|shop|buy|available|only)\s+at\s+([A-Z][A-Za-z&]*(?:\s+[A-Z][A-Za-z&]*){0,2})",
            r"(?:at|from)\s+([A-Z][A-Za-z&]*(?:\s+[A-Z][A-Za-z&]*){0,2})",
        )

        candidates: List[Tuple[str, float, int]] = []
        for pattern in location_patterns:
            for match in re.finditer(pattern, text):
                raw = match.group(1).strip()
                words = raw.split()
                cleaned_words: List[str] = []
                for word in words:
                    normalised = word.rstrip(":,.")
                    if normalised.lower() in TRAILING_STORE_TOKENS:
                        break
                    cleaned_words.append(normalised)
                if not cleaned_words:
                    continue
                candidate = " ".join(cleaned_words)
                score = 0.45 + 0.05 * (len(cleaned_words) - 1)
                candidates.append((candidate, min(score, 0.6), match.start()))

        if not candidates:
            return None

        candidates.sort(key=lambda item: (-item[1], -len(item[0]), item[2]))
        best_name, score, _ = candidates[0]
        return {
            "name": best_name,
            "confidence": score,
            "type": "extracted",
            "key": None,
        }

    def _most_frequent_capitalised_word(self, text: str) -> Optional[Dict[str, object]]:
        tokens = re.findall(r"\b[A-Za-z][A-Za-z&]{2,}\b", text)
        counts: Dict[str, int] = {}
        for token in tokens:
            lower = token.lower()
            if lower in STORE_STOPWORDS:
                continue
            counts[token] = counts.get(token, 0) + 1
        if not counts:
            return None
        selected = max(counts.items(), key=lambda item: (item[1], len(item[0])))
        if selected[1] < 2:
            return None
        return {
            "name": selected[0],
            "confidence": 0.38,
            "type": "extracted",
            "key": None,
        }

    # ------------------------------------------------------------------
    # Coupon code extraction
    # ------------------------------------------------------------------
    def _extract_coupon_code(self, text: str, store: Dict[str, object]) -> Dict[str, object]:
        candidates: List[Dict[str, object]] = []

        store_key = store.get("key")
        if store_key and store_key in STORE_PROFILES:
            for pattern in STORE_PROFILES[store_key].get("code_patterns", []):
                for match in re.findall(pattern, text, flags=re.IGNORECASE):
                    if self._is_plausible_code(match):
                        candidates.append(
                            {
                                "code": match,
                                "confidence": 0.9,
                                "source": "store_pattern",
                            }
                        )

        for pattern in EXPLICIT_CODE_PATTERNS:
            for match in re.findall(pattern, text, flags=re.IGNORECASE):
                if self._is_plausible_code(match):
                    candidates.append(
                        {
                            "code": match,
                            "confidence": 0.95,
                            "source": "explicit_indicator",
                        }
                    )

        for pattern in self.general_code_patterns:
            for match in re.findall(pattern, text):
                if self._is_plausible_code(match):
                    candidates.append(
                        {
                            "code": match,
                            "confidence": 0.7,
                            "source": "general_pattern",
                        }
                    )

        if not candidates:
            return {"code": None, "confidence": 0.0, "source": "none"}

        candidates.sort(key=lambda item: (item["confidence"], len(item.get("code", ""))), reverse=True)
        best = candidates[0].copy()
        best["code"] = best["code"].strip()
        return best

    def _is_plausible_code(self, code: str) -> bool:
        if not code:
            return False
        cleaned = code.strip()
        if len(cleaned) < 4:
            return False
        upper = cleaned.upper()
        if upper in CODE_FALSE_POSITIVES:
            return False
        if cleaned.isdigit():
            return False
        if cleaned.isalpha() and len(cleaned) < 6:
            return False
        return True

    # ------------------------------------------------------------------
    # Discount extraction
    # ------------------------------------------------------------------
    def _extract_discount(self, text: str) -> Dict[str, object]:
        normalised_text = re.sub(r"upto", "up to", text, flags=re.IGNORECASE)
        normalised_text = normalised_text.replace("% off", "% Off")
        normalised_text = normalised_text.replace("*", "")

        composite_match = COMBINED_PERCENT_PATTERN.search(normalised_text)
        if composite_match:
            phrase = composite_match.group(0)
            components = [
                self._normalise_percentage_component(comp)
                for comp in PERCENT_COMPONENT_PATTERN.findall(phrase)
            ]
            components = [c for c in components if c]
            if components:
                value = max(self._percentage_value(component) for component in components)
                raw_text = " + ".join(components)
                return {
                    "type": "percentage",
                    "value": value,
                    "confidence": 0.92,
                    "raw_text": raw_text,
                    "components": components,
                }

        single_match = PERCENT_COMPONENT_PATTERN.search(normalised_text)
        if single_match:
            component = self._normalise_percentage_component(single_match.group(0))
            value = self._percentage_value(component)
            return {
                "type": "percentage",
                "value": value,
                "confidence": 0.85,
                "raw_text": component,
                "components": [component],
            }

        amount_match = AMOUNT_PATTERN.search(text)
        if amount_match:
            amount = self._amount_value(amount_match.group(1))
            raw_text = amount_match.group(0).replace("  ", " ").strip()
            return {
                "type": "amount",
                "value": amount,
                "confidence": 0.75,
                "raw_text": raw_text,
                "components": [raw_text],
            }

        return {
            "type": None,
            "value": None,
            "confidence": 0.0,
            "raw_text": None,
            "components": [],
        }

    def _normalise_percentage_component(self, component: str) -> str:
        cleaned = component.strip().replace("*", "")
        cleaned = re.sub(r"\s+", " ", cleaned)
        cleaned = re.sub(r"upto", "Up to", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"up\s+to", "Up to", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"flat", "Flat", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"extra", "Extra", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"save", "Save", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"(\d+)\.0%", r"\1%", cleaned)
        cleaned = re.sub(r"\s*%\s*", "% ", cleaned).replace("% Off", "% Off")
        cleaned = cleaned.replace("% Discount", "% Discount")
        if not re.search(r"(Off|Discount)", cleaned, flags=re.IGNORECASE):
            cleaned = cleaned.rstrip() + " Off"
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        cleaned = cleaned.replace("% Discount", "% Discount")
        cleaned = cleaned.replace(" %", "%")
        cleaned = cleaned.replace("Off Off", "Off")
        cleaned = cleaned.replace("Discount Discount", "Discount")
        return cleaned

    def _percentage_value(self, component: str) -> int:
        match = re.search(r"(\d+(?:\.\d+)?)%", component)
        if not match:
            return 0
        return int(round(float(match.group(1))))

    def _amount_value(self, amount: str) -> int:
        return int(amount.replace(",", ""))

    # ------------------------------------------------------------------
    # Expiry extraction
    # ------------------------------------------------------------------
    def _extract_expiry_date(self, text: str) -> Dict[str, object]:
        best_candidate: Optional[Tuple[str, float]] = None

        for segment in re.split(r"[\n\.]", text):
            segment = segment.strip()
            if not segment:
                continue
            lowered = segment.lower()
            if not any(hint in lowered for hint in EXPIRY_LINE_HINTS):
                continue
            candidate = self._find_date_in_segment(segment)
            if candidate:
                confidence = 0.95 if "," in candidate or any(x in lowered for x in (",", ":")) else 0.88
                if not best_candidate or confidence > best_candidate[1]:
                    best_candidate = (candidate, confidence)

        if not best_candidate:
            return {"raw_text": None, "parsed_date": None, "confidence": 0.0, "is_expired": False}

        raw_text, confidence = best_candidate
        parsed = self._parse_date_value(raw_text)
        parsed_iso = parsed.isoformat() if parsed else None
        is_expired = False
        if parsed:
            is_expired = parsed < datetime.now()

        return {
            "raw_text": raw_text,
            "parsed_date": parsed_iso,
            "confidence": confidence if parsed else confidence * 0.8,
            "is_expired": is_expired,
        }

    def _find_date_in_segment(self, segment: str) -> Optional[str]:
        for pattern in DATE_PATTERNS:
            match = re.search(pattern, segment, flags=re.IGNORECASE)
            if match:
                return match.group(1).strip()
        return None

    def _parse_date_value(self, value: str) -> Optional[datetime]:
        cleaned = value.strip()
        cleaned = re.sub(r"(\d{1,2})(st|nd|rd|th)", r"\1", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s+", " ", cleaned)
        cleaned = re.sub(r"(\d)(am|pm)\b", r"\1 \2", cleaned, flags=re.IGNORECASE)
        lowered = cleaned.lower()
        for source, target in MONTH_NORMALIZATION.items():
            lowered = re.sub(r"\b" + re.escape(source) + r"\b", target.lower(), lowered)
        lowered = re.sub(
            r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b",
            lambda m: m.group(0).title(),
            lowered,
        )
        lowered = re.sub(
            r"\b(january|february|march|april|may|june|july|august|september|october|november|december)\b",
            lambda m: m.group(0).title(),
            lowered,
        )
        lowered = re.sub(r"\b(am|pm)\b", lambda m: m.group(0).upper(), lowered)
        normalized = lowered.replace(" ,", ",")
        normalized = normalized.replace(",", ", ")
        normalized = re.sub(r"\s+", " ", normalized).strip()
        normalized = normalized.replace(" ", " ")

        for fmt in EXPIRY_DATE_FORMATS:
            try:
                return datetime.strptime(normalized, fmt)
            except ValueError:
                continue
        return None

    # ------------------------------------------------------------------
    # Minimum order
    # ------------------------------------------------------------------
    def _extract_min_order(self, text: str) -> Dict[str, object]:
        best: Dict[str, object] = {"amount": None, "confidence": 0.0, "raw_text": None}
        for pattern, confidence in MIN_ORDER_PATTERNS:
            match = pattern.search(text)
            if not match:
                continue
            amount = self._amount_value(match.group(1))
            if amount < 50 or amount > 50000:
                continue
            if confidence > best["confidence"]:
                best = {
                    "amount": amount,
                    "confidence": confidence,
                    "raw_text": match.group(0).strip(),
                }
        return best

    # ------------------------------------------------------------------
    # Description and terms
    # ------------------------------------------------------------------
    def _extract_description(self, text: str) -> Dict[str, object]:
        description_patterns = (
            r"(get\s+[^.?!]+off[^.?!]*)",
            r"(save\s+[^.?!]+)",
            r"(free\s+delivery[^.?!]*)",
            r"(buy\s+\d+\s+get\s+\d+[^.?!]*)",
        )
        for pattern in description_patterns:
            match = re.search(pattern, text, flags=re.IGNORECASE)
            if match:
                snippet = match.group(1).strip()
                return {"text": snippet, "confidence": 0.75, "source": "pattern"}

        sentences = [s.strip() for s in re.split(r"[.!?]", text) if s.strip()]
        if sentences:
            longest = max(sentences, key=len)
            if len(longest) > 20:
                return {"text": longest, "confidence": 0.5, "source": "fallback"}
        return {"text": None, "confidence": 0.0, "source": "none"}

    def _extract_terms(self, text: str) -> Dict[str, object]:
        sentences = [s.strip() for s in re.split(r"[.!?]", text) if s.strip()]
        collected: List[str] = []

        keywords = ("terms", "condition", "applicable", "valid", "plan", "subscription", "purchase")
        for sentence in sentences:
            lowered = sentence.lower()
            if any(keyword in lowered for keyword in keywords):
                amounts = re.findall(r"₹\s*(\d+(?:,\d{3})*)", sentence)
                unique_amounts: List[str] = []
                for amount in amounts:
                    normalized = amount.replace(",", "")
                    if normalized not in unique_amounts:
                        unique_amounts.append(normalized)
                if unique_amounts:
                    descriptor_match = re.search(r"([A-Za-z& ]{3,}?Plan[s]?)", sentence, flags=re.IGNORECASE)
                    descriptor = descriptor_match.group(1).strip() if descriptor_match else "this offer"
                    formatted_amounts = ", ".join(f"₹{amt}" for amt in unique_amounts)
                    formatted = f"Valid on {descriptor} of {formatted_amounts}"
                    if formatted not in collected:
                        collected.append(formatted)
                else:
                    collected.append(sentence)

        if collected:
            return {"text": ". ".join(collected), "confidence": 0.7, "source": "keyword"}
        return {"text": None, "confidence": 0.0, "source": "none"}

    # ------------------------------------------------------------------
    # Validation helper (optional public API)
    # ------------------------------------------------------------------
    def validate_extracted_fields(self, fields: Dict[str, Dict[str, object]]) -> Dict[str, object]:
        validated = dict(fields)
        discount = fields.get("discount", {})
        expiry = fields.get("expiry_date", {})
        store = fields.get("store", {})
        code = fields.get("coupon_code", {})

        overall = fields.get("overall_confidence", 0.0)
        if expiry.get("parsed_date") and expiry.get("confidence", 0.0) >= 0.7:
            overall = min(1.0, overall + 0.1)
        if store.get("confidence", 0.0) >= 0.7 and code.get("confidence", 0.0) >= 0.7:
            overall = min(1.0, overall + 0.15)
        if discount.get("type") == "percentage" and (discount.get("value") or 0) > 90:
            discount_conf = discount.get("confidence", 0.0) * 0.7
            validated.setdefault("discount", discount).update({"confidence": discount_conf})

        validated["overall_confidence"] = overall
        validated["validation_flags"] = {
            "has_essential_fields": code.get("confidence", 0.0) >= 0.5
            or discount.get("confidence", 0.0) >= 0.5,
            "has_store_info": store.get("confidence", 0.0) >= 0.5,
            "has_expiry_info": expiry.get("confidence", 0.0) >= 0.5,
            "overall_quality": "high"
            if overall > 0.8
            else "medium" if overall > 0.5 else "low",
        }
        return validated
