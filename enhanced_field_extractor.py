#!/usr/bin/env python3
"""
Enhanced Field Extractor - ML-based coupon field extraction.

This module provides intelligent field extraction using machine learning
and context-aware algorithms for better coupon information extraction.
"""

import re
import json
import logging
from datetime import datetime
from difflib import SequenceMatcher
from typing import Dict, List, Tuple, Optional


MONTH_NORMALIZATION = {
    'jan.': 'Jan',
    'feb.': 'Feb',
    'mar.': 'Mar',
    'apr.': 'Apr',
    'jun.': 'Jun',
    'jul.': 'Jul',
    'aug.': 'Aug',
    'sep.': 'Sep',
    'sep': 'Sep',
    'sept': 'Sep',
    'sept.': 'Sep',
    'oct.': 'Oct',
    'nov.': 'Nov',
    'dec.': 'Dec',
}


EXPIRY_DATE_FORMATS = [
    '%d/%m/%Y',
    '%d/%m/%y',
    '%d-%m-%Y',
    '%d-%m-%y',
    '%Y-%m-%d',
    '%d %m %Y',
    '%d %b %Y',
    '%d %b, %Y',
    '%d %B %Y',
    '%d %B, %Y',
    '%d %b %Y %H:%M',
    '%d %b, %Y %H:%M',
    '%d %B %Y %H:%M',
    '%d %B, %Y %H:%M',
    '%d %b %Y, %H:%M',
    '%d %b, %Y, %H:%M',
    '%d %B %Y, %H:%M',
    '%d %B, %Y, %H:%M',
    '%d %b %Y %I:%M %p',
    '%d %b, %Y %I:%M %p',
    '%d %B %Y %I:%M %p',
    '%d %B, %Y %I:%M %p',
    '%d %b %Y, %I:%M %p',
    '%d %b, %Y, %I:%M %p',
    '%d %B %Y, %I:%M %p',
    '%d %B, %Y, %I:%M %p',
    '%d %b %Y %I:%M%p',
    '%d %b, %Y %I:%M%p',
    '%d %B %Y %I:%M%p',
    '%d %B, %Y %I:%M%p',
    '%d %b %Y, %I:%M%p',
    '%d %b, %Y, %I:%M%p',
    '%d %B %Y, %I:%M%p',
    '%d %B, %Y, %I:%M%p',
]


def _partial_ratio(needle: str, haystack: str) -> int:
    """Lightweight partial ratio implementation without external dependencies."""

    if not needle or not haystack:
        return 0

    needle = needle.lower()
    haystack = haystack.lower()

    if needle in haystack:
        return 100

    matcher = SequenceMatcher()
    matcher.set_seq2(needle)

    best = 0.0
    window = len(needle)

    for start in range(0, len(haystack) - window + 1):
        segment = haystack[start:start + window]
        matcher.set_seq1(segment)
        best = max(best, matcher.ratio())
        if best >= 0.99:
            break

    return int(round(best * 100))

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("enhanced_field_extractor.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("enhanced_field_extractor")

class EnhancedCouponFieldExtractor:
    """Enhanced field extraction with ML and context awareness."""
    
    def __init__(self):
        """Initialize the enhanced field extractor."""
        
        # Store-specific patterns (learned from data)
        self.store_patterns = {
            'amazon': {
                'display_name': 'Amazon',
                'identifiers': ['amazon', 'amzn', 'amazon.in'],
                'code_patterns': [r'[A-Z0-9]{8,12}'],
                'discount_patterns': [r'(\d+(?:\.\d+)?)%\s*off', r'save\s*₹(\d+)', r'flat\s*(\d+(?:\.\d+)?)%'],
                'common_terms': ['prime', 'delivery', 'shopping', 'cart']
            },
            'flipkart': {
                'display_name': 'Flipkart',
                'identifiers': ['flipkart', 'fkrt', 'flipkart.com'],
                'code_patterns': [r'[A-Z]{2,4}\d{4,8}', r'FK[A-Z0-9]{6,10}'],
                'discount_patterns': [r'(\d+(?:\.\d+)?)%\s*off', r'extra\s*(\d+(?:\.\d+)?)%', r'flat\s*₹(\d+)'],
                'common_terms': ['plus', 'supercoins', 'delivery', 'exchange']
            },
            'myntra': {
                'display_name': 'Myntra',
                'identifiers': ['myntra', 'myntra.com'],
                'code_patterns': [r'[A-Z]{3,5}\d{3,6}', r'MYNTRA[0-9]{4,8}'],
                'discount_patterns': [r'(\d+(?:\.\d+)?)%\s*off', r'flat\s*(\d+(?:\.\d+)?)%', r'buy\s*\d+\s*get\s*\d+'],
                'common_terms': ['fashion', 'style', 'brands', 'clothing']
            },
            'swiggy': {
                'display_name': 'Swiggy',
                'identifiers': ['swiggy', 'swiggy.com'],
                'code_patterns': [r'[A-Z]{4,6}\d{2,4}', r'SWIGGY[0-9]{3,6}'],
                'discount_patterns': [r'(\d+(?:\.\d+)?)%\s*off', r'flat\s*₹(\d+)', r'free\s*delivery'],
                'common_terms': ['food', 'delivery', 'restaurant', 'order']
            },
            'zomato': {
                'display_name': 'Zomato',
                'identifiers': ['zomato', 'zomato.com'],
                'code_patterns': [r'[A-Z]{4,6}\d{2,4}', r'ZOMATO[0-9]{3,6}'],
                'discount_patterns': [r'(\d+(?:\.\d+)?)%\s*off', r'flat\s*₹(\d+)', r'free\s*delivery'],
                'common_terms': ['food', 'delivery', 'dining', 'restaurant']
            },
            'aha': {
                'display_name': 'Aha',
                'identifiers': ['aha'],
                'code_patterns': [],
                'discount_patterns': [r'flat\s*(\d+(?:\.\d+)?)%'],
                'common_terms': ['telugu', 'plan', 'subscription']
            }
        }
        
        # Common coupon code patterns
        self.general_code_patterns = [
            r'\b[A-Za-z]{3,6}\d{2,6}\b',  # 3-6 letters + 2-6 digits
            r'\b[A-Za-z]{2,4}[0-9]{4,8}\b',  # Letters + numbers
            r'\b[A-Za-z0-9]{6,12}\b',     # 6-12 alphanumeric
            r'\bCODE\s*[A-Za-z0-9]{4,10}\b',  # CODE prefix
            r'\bUSE\s*[A-Za-z0-9]{4,10}\b',   # USE prefix
        ]
        
        # Discount patterns with confidence scoring
        self.discount_phrase_patterns = [
            r'(?:up\s*to|upto)\s*\d+(?:\.\d+)?%[^\w]*(?:off|discount)',
            r'extra\s*\d+(?:\.\d+)?%[^\w]*(?:off|discount)',
            r'flat\s*\d+(?:\.\d+)?%[^\w]*(?:off|discount)',
            r'\d+(?:\.\d+)?%[^\w]*(?:off|discount)'
        ]

        self.discount_patterns = [
            (r'(\d+(?:\.\d+)?)%\s*(?:off|discount)', 0.9, 'percentage'),
            (r'flat\s*(\d+(?:\.\d+)?)%\s*(?:off|discount)', 0.9, 'percentage'),
            (r'extra\s*(\d+(?:\.\d+)?)%\s*(?:off|discount)', 0.85, 'percentage'),
            (r'upto\s*(\d+(?:\.\d+)?)%\s*(?:off|discount)', 0.8, 'percentage'),
            (r'save\s*₹(\d+)', 0.9, 'amount'),
            (r'flat\s*₹(\d+)\s*(?:off|discount)', 0.9, 'amount'),
            (r'₹(\d+)\s*(?:off|discount)', 0.8, 'amount'),
            (r'rs\.?\s*(\d+)\s*(?:off|discount)', 0.8, 'amount'),
            (r'get\s*₹(\d+)\s*(?:back|cashback)', 0.85, 'cashback'),
            (r'(?<!%)\b(\d+)\s*(?:off|discount|cashback)', 0.75, 'amount'),
        ]
        
        # Expiry date patterns
        self.expiry_patterns = [
            (r'valid\s*(?:till|until)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'expires?\s*(?:on|at)?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'expiry\s*:?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'valid\s*(?:till|until)\s*(\d{1,2}(?:st|nd|rd|th)?\s+\w+,?\s+\d{2,4}(?:,?\s*\d{1,2}:\d{2}\s*(?:am|pm))?)', 0.95),
            (r'expires?\s*(?:on|at)?\s*(\d{1,2}(?:st|nd|rd|th)?\s+\w+,?\s+\d{2,4}(?:,?\s*\d{1,2}:\d{2}\s*(?:am|pm))?)', 0.95),
            (r'(\d{1,2}(?:st|nd|rd|th)?\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w*\s+\d{2,4})', 0.85),
        ]
        
        # Minimum order patterns
        self.min_order_patterns = [
            (r'min(?:imum)?\s*order\s*₹(\d+)', 0.9),
            (r'min(?:imum)?\s*order\s*rs\.?\s*(\d+)', 0.9),
            (r'order\s*above\s*₹(\d+)', 0.85),
            (r'order\s*above\s*rs\.?\s*(\d+)', 0.85),
            (r'minimum\s*purchase\s*₹(\d+)', 0.8),
        ]
        
        # Initialize confidence threshold
        self.confidence_threshold = 0.7
        
        logger.info("Enhanced field extractor initialized")
    
    def extract_fields_with_confidence(self, text: str) -> Dict:
        """Extract all coupon fields with confidence scores.
        
        Args:
            text (str): OCR extracted text
            
        Returns:
            Dict: Extracted fields with confidence scores
        """
        text_clean = self._clean_text(text)
        
        # Detect store first to apply store-specific patterns
        store_info = self._detect_store(text_clean)
        
        # Extract all fields
        fields = {
            'store': store_info,
            'coupon_code': self._extract_coupon_code(text_clean, store_info),
            'discount': self._extract_discount(text_clean),
            'expiry_date': self._extract_expiry_date(text_clean),
            'min_order': self._extract_min_order(text_clean),
            'description': self._extract_description(text_clean),
            'terms': self._extract_terms(text_clean)
        }
        
        # Calculate overall confidence
        confidences = [field.get('confidence', 0) for field in fields.values() if isinstance(field, dict)]
        overall_confidence = sum(confidences) / len(confidences) if confidences else 0.0
        
        fields['overall_confidence'] = overall_confidence
        fields['extraction_metadata'] = {
            'text_length': len(text),
            'processed_text_length': len(text_clean),
            'store_detected': store_info.get('confidence', 0) > 0.5,
            'fields_extracted': sum(1 for field in fields.values() 
                                  if isinstance(field, dict) and field.get('confidence', 0) > self.confidence_threshold)
        }
        
        return fields
    
    def _clean_text(self, text: str) -> str:
        """Clean and normalize text for processing.
        
        Args:
            text (str): Raw text
            
        Returns:
            str: Cleaned text
        """
        # Remove extra whitespace
        cleaned = re.sub(r'\s+', ' ', text.strip())
        
        # Normalize currency symbols
        cleaned = re.sub(r'rs\.?|rupees?', '₹', cleaned, flags=re.IGNORECASE)
        
        # Normalize common terms
        cleaned = re.sub(r'\boff\b', 'off', cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r'\bdiscount\b', 'discount', cleaned, flags=re.IGNORECASE)
        
        return cleaned
    
    def _detect_store(self, text: str) -> Dict:
        """Detect store name with confidence.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Store detection result
        """
        text_lower = text.lower()
        best_match = {'name': None, 'confidence': 0.0, 'type': 'unknown', 'key': None}
        
        for store_name, store_data in self.store_patterns.items():
            confidence = 0.0
            
            # Check direct identifiers
            for identifier in store_data['identifiers']:
                if identifier in text_lower:
                    confidence = max(confidence, 0.9)
            
            # Check common terms (weighted less)
            term_matches = sum(1 for term in store_data['common_terms'] if term in text_lower)
            if term_matches > 0:
                confidence = max(confidence, 0.3 + (term_matches * 0.1))
            
            # Use fuzzy matching for partial matches
            for identifier in store_data['identifiers']:
                ratio = _partial_ratio(identifier, text_lower)
                if ratio > 80:
                    confidence = max(confidence, ratio / 100.0 * 0.8)
            
            if confidence > best_match['confidence']:
                best_match = {
                    'name': store_data.get('display_name', store_name),
                    'confidence': confidence,
                    'type': 'identified',
                    'key': store_name
                }
        
        # If no store detected, try to extract any brand-like words
        if best_match['confidence'] < 0.5:
            # Try to capture "at <Brand>" constructs first
            location_patterns = [
                r'(?:at|@)\s+([A-Z][A-Za-z&]*(?:\s+[A-Z][A-Za-z&]*){0,2})',
                r'(?:from)\s+([A-Z][A-Za-z&]*(?:\s+[A-Z][A-Za-z&]*){0,2})',
            ]
            location_candidates: List[Tuple[str, int]] = []
            generic_suffixes = re.compile(r'\b(store|stores|shop|shops|outlet|outlets|mall|malls|retail|online|sale|offer)s?\b', re.IGNORECASE)

            for pattern in location_patterns:
                for match in re.finditer(pattern, text):
                    candidate = match.group(1).strip()
                    candidate = generic_suffixes.sub('', candidate).strip()
                    candidate = re.sub(r'\s{2,}', ' ', candidate)
                    if candidate:
                        words = candidate.split()
                        cleaned_words = []
                        for word in words:
                            if word.lower() in {'the', 'at', 'and'}:
                                continue
                            cleaned_words.append(word)
                        if cleaned_words:
                            cleaned = ' '.join(cleaned_words)
                            location_candidates.append((cleaned, match.start()))

            if location_candidates:
                # Prefer the candidate that appears first but prioritise longer names
                location_candidates.sort(key=lambda item: (-len(item[0]), item[1]))
                best_match = {
                    'name': location_candidates[0][0].strip(),
                    'confidence': 0.45,
                    'type': 'extracted',
                    'key': None
                }
            else:
                brand_patterns = [
                    r'\b[A-Z][a-z]+\s*(?:\.com|\.in)?\b',  # Domain-like
                    r'\b[A-Z]{2,8}\b'  # All caps words
                ]

                stopwords = {'redeem', 'super', 'mega', 'offer', 'offers', 'coupon', 'coupons', 'multi', 'valid', 'expires',
                             'get', 'save', 'extra', 'flat', 'cashback', 'discount'}

                selected = None
                for pattern in brand_patterns:
                    matches = re.findall(pattern, text)
                    if not matches:
                        continue

                    for match in matches:
                        candidate = match.strip()
                        if candidate.lower() in stopwords:
                            continue
                        selected = candidate
                        break

                    if selected:
                        break

                if selected:
                    best_match = {
                        'name': selected,
                        'confidence': 0.4,
                        'type': 'extracted',
                        'key': None
                    }
                else:
                    lowercase_stopwords = stopwords | {'purchase', 'details', 'terms', 'condition', 'conditions', 'plan',
                                                       'plans', 'annual', 'telugu', 'unknown', 'couponcode', 'code'}

                    words = re.findall(r'\b[a-z]{3,}\b', text_lower)
                    frequency: Dict[str, int] = {}
                    for word in words:
                        if word in lowercase_stopwords:
                            continue
                        frequency[word] = frequency.get(word, 0) + 1

                    if frequency:
                        candidate_word, count = max(frequency.items(), key=lambda item: (item[1], len(item[0])))
                        if count > 1:
                            match = re.search(r'\b' + re.escape(candidate_word) + r'\b', text, re.IGNORECASE)
                            name = match.group(0) if match else candidate_word
                            best_match = {
                                'name': name,
                                'confidence': 0.38,
                                'type': 'extracted',
                                'key': None
                            }

        return best_match
    
    def _extract_coupon_code(self, text: str, store_info: Dict) -> Dict:
        """Extract coupon code with confidence.
        
        Args:
            text (str): Cleaned text
            store_info (Dict): Store detection result
            
        Returns:
            Dict: Coupon code extraction result
        """
        candidates = []
        
        # Try store-specific patterns first
        store_key = store_info.get('key')
        if store_key and store_key in self.store_patterns:
            store_patterns = self.store_patterns[store_key]['code_patterns']
            for pattern in store_patterns:
                matches = re.findall(pattern, text, re.IGNORECASE)
                for match in matches:
                    if not self._is_plausible_code(match):
                        continue
                    candidates.append({
                        'code': match,
                        'confidence': 0.9,
                        'source': 'store_specific'
                    })
        
        # Try general patterns
        for pattern in self.general_code_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                if not self._is_plausible_code(match):
                    continue
                if not self._is_likely_false_positive(match):
                    candidates.append({
                        'code': match,
                        'confidence': 0.7,
                        'source': 'general_pattern'
                    })
        
        # Look for explicit code indicators
        code_indicator_patterns = [
            r'(?:code|coupon)\s*:?\s*([A-Za-z0-9]{4,12})',
            r'use\s+(?:code\s+)?([A-Za-z0-9]{4,12})',
            r'promo\s*code\s*:?\s*([A-Za-z0-9]{4,12})'
        ]
        
        for pattern in code_indicator_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                if not self._is_plausible_code(match):
                    continue
                if self._is_likely_false_positive(match):
                    continue
                candidates.append({
                    'code': match,
                    'confidence': 0.95,
                    'source': 'explicit_indicator'
                })
        
        # Select best candidate
        if candidates:
            best_candidate = max(candidates, key=lambda x: x['confidence'])
            return best_candidate
        
        return {'code': None, 'confidence': 0.0, 'source': 'none'}
    
    def _extract_discount(self, text: str) -> Dict:
        """Extract discount information with confidence.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Discount extraction result
        """
        best_match = {
            'value': None,
            'type': None,
            'confidence': 0.0,
            'raw_text': None,
            'text': None,
            'components': []
        }

        # First try to capture composite discount phrases like
        # "Up to 50% off + Extra 33% off"
        phrase_matches: List[Tuple[int, str]] = []
        for pattern in self.discount_phrase_patterns:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                phrase_matches.append((match.start(), match.group(0)))

        if phrase_matches:
            phrase_matches.sort(key=lambda item: item[0])
            normalized_phrases: List[str] = []

            for _, phrase in phrase_matches:
                normalized = re.sub(r'\s+', ' ', phrase.strip(' ,;:+-*')).strip()
                normalized = normalized.lower().replace('upto', 'up to')
                normalized = re.sub(r'(\d+)\.0+%', r'\1%', normalized)
                if normalized and normalized not in normalized_phrases:
                    normalized_phrases.append(normalized)

            if normalized_phrases:
                filtered_phrases = []
                for phrase in normalized_phrases:
                    if any(phrase != other and phrase in other for other in normalized_phrases):
                        continue
                    filtered_phrases.append(phrase)
                normalized_phrases = filtered_phrases

            if normalized_phrases:
                pretty_phrases = []
                for phrase in normalized_phrases:
                    pretty = phrase
                    pretty = re.sub(r'\bup to\b', 'Up to', pretty)
                    pretty = re.sub(r'\bextra\b', 'Extra', pretty)
                    pretty = re.sub(r'\bflat\b', 'Flat', pretty)
                    pretty = re.sub(r'\boff\b', 'Off', pretty)
                    pretty = re.sub(r'(\d+)\.0+%', r'\1%', pretty)
                    pretty_phrases.append(pretty)

                combined_phrase = ' + '.join(pretty_phrases)
                percentages = [
                    float(num)
                    for phrase in normalized_phrases
                    for num in re.findall(r'(\d+(?:\.\d+)?)%', phrase)
                ]

                if percentages:
                    max_value = max(percentages)
                    if abs(max_value - int(max_value)) < 1e-6:
                        max_value = int(max_value)
                    best_match = {
                        'value': max_value,
                        'type': 'percentage',
                        'confidence': 0.92,
                        'raw_text': combined_phrase,
                        'text': combined_phrase,
                        'components': pretty_phrases
                    }

        for pattern, confidence, discount_type in self.discount_patterns:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                try:
                    groups = match.groups()
                    value_str = None
                    if groups:
                        for group in groups:
                            if group and group.strip().replace('.', '', 1).isdigit():
                                value_str = group
                                break
                    if value_str is None:
                        digits = re.search(r'\d+', match.group(0))
                        value_str = digits.group(0) if digits else None

                    if not value_str:
                        continue

                    if '.' in value_str:
                        value = float(value_str)
                    else:
                        value = int(value_str)

                    raw_text = re.sub(r'\s+', ' ', match.group(0).strip())
                    raw_text = re.sub(r'(\d+)\.0+%', r'\1%', raw_text)
                    normalized_text = raw_text

                    # If the raw text contains a percentage symbol, ensure we treat it as percentage
                    normalized_type = 'percentage' if '%' in raw_text else discount_type

                    # Validate reasonable discount values
                    if normalized_type == 'percentage':
                        numeric_value = float(value)
                        if 1 <= numeric_value <= 99:
                            output_value = int(numeric_value) if abs(numeric_value - int(numeric_value)) < 1e-6 else numeric_value
                            if confidence > best_match['confidence']:
                                best_match = {
                                    'value': output_value,
                                    'type': 'percentage',
                                    'confidence': confidence,
                                    'raw_text': normalized_text,
                                    'text': normalized_text,
                                    'components': [normalized_text]
                                }
                    elif normalized_type in ['amount', 'cashback']:
                        numeric_value = float(value)
                        if 10 <= numeric_value <= 10000:
                            output_value = int(numeric_value) if abs(numeric_value - int(numeric_value)) < 1e-6 else numeric_value
                            if confidence > best_match['confidence']:
                                best_match = {
                                    'value': output_value,
                                    'type': normalized_type,
                                    'confidence': confidence,
                                    'raw_text': normalized_text,
                                    'text': normalized_text,
                                    'components': [normalized_text]
                                }

                except (ValueError, IndexError):
                    continue

        return best_match
    
    def _extract_expiry_date(self, text: str) -> Dict:
        """Extract expiry date with confidence.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Expiry date extraction result
        """
        best_match = {'date': None, 'confidence': 0.0, 'raw_text': None, 'parsed_date': None, 'is_expired': None}
        
        for pattern, confidence in self.expiry_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                parsed_date = self._parse_expiry_candidate(match)

                if parsed_date:
                    is_expired = parsed_date.date() < datetime.now().date()
                    effective_confidence = confidence

                    if effective_confidence > best_match['confidence']:
                        best_match = {
                            'date': match,
                            'confidence': effective_confidence,
                            'raw_text': match,
                            'parsed_date': parsed_date.isoformat(),
                            'is_expired': is_expired
                        }
                else:
                    if confidence * 0.5 > best_match['confidence']:
                        best_match = {
                            'date': match,
                            'confidence': confidence * 0.5,
                            'raw_text': match,
                            'parsed_date': None,
                            'is_expired': None
                        }

        return best_match

    def _parse_expiry_candidate(self, value: str) -> Optional[datetime]:
        """Parse a matched expiry string using known date formats."""

        if not value:
            return None

        normalized = value.strip()
        normalized = re.sub(r'(\d{1,2})(st|nd|rd|th)', r'\1', normalized, flags=re.IGNORECASE)
        normalized = re.sub(r'\s+', ' ', normalized)
        normalized = re.sub(r'(\d)(am|pm)\b', r'\1 \2', normalized, flags=re.IGNORECASE)

        lowered = normalized.lower()
        for source, target in MONTH_NORMALIZATION.items():
            lowered = re.sub(r'\b' + re.escape(source) + r'\b', target.lower(), lowered)

        # Restore title case for month tokens
        lowered = re.sub(
            r'\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b',
            lambda m: m.group(0).title(),
            lowered,
            flags=re.IGNORECASE
        )
        lowered = re.sub(
            r'\b(january|february|march|april|may|june|july|august|september|october|november|december)\b',
            lambda m: m.group(0).title(),
            lowered,
            flags=re.IGNORECASE
        )
        lowered = re.sub(r'\b(am|pm)\b', lambda m: m.group(0).upper(), lowered, flags=re.IGNORECASE)

        # Remove stray periods after normalization
        normalized = re.sub(r'\.(?=\s|$)', '', lowered)

        for fmt in EXPIRY_DATE_FORMATS:
            try:
                parsed = datetime.strptime(normalized, fmt)
                return parsed
            except ValueError:
                continue

        return None
    
    def _extract_min_order(self, text: str) -> Dict:
        """Extract minimum order amount with confidence.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Minimum order extraction result
        """
        best_match = {'amount': None, 'confidence': 0.0, 'raw_text': None}
        
        for pattern, confidence in self.min_order_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                try:
                    amount = int(match)
                    
                    # Validate reasonable minimum order amounts
                    if 50 <= amount <= 50000:
                        if confidence > best_match['confidence']:
                            best_match = {
                                'amount': amount,
                                'confidence': confidence,
                                'raw_text': match
                            }
                
                except ValueError:
                    continue
        
        return best_match
    
    def _extract_description(self, text: str) -> Dict:
        """Extract coupon description.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Description extraction result
        """
        # Look for descriptive phrases
        description_patterns = [
            r'(get\s+\w+\s+off\s+on\s+[^.!?]+)',
            r'(save\s+[^.!?]+)',
            r'(flat\s+\d+%\s+off\s+on\s+[^.!?]+)',
            r'(extra\s+\d+%\s+off\s+on\s+[^.!?]+)',
            r'(free\s+delivery\s+on\s+[^.!?]+)',
            r'(buy\s+\d+\s+get\s+\d+[^.!?]+)'
        ]
        
        descriptions = []
        for pattern in description_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            descriptions.extend(matches)
        
        if descriptions:
            # Take the longest description
            best_description = max(descriptions, key=len)
            return {
                'text': best_description.strip(),
                'confidence': 0.8,
                'source': 'pattern_match'
            }
        
        # Fallback: take first sentence-like structure
        sentences = re.split(r'[.!?]', text)
        if sentences:
            longest_sentence = max(sentences, key=len).strip()
            if len(longest_sentence) > 20:
                return {
                    'text': longest_sentence,
                    'confidence': 0.5,
                    'source': 'longest_sentence'
                }
        
        return {'text': None, 'confidence': 0.0, 'source': 'none'}
    
    def _extract_terms(self, text: str) -> Dict:
        """Extract terms and conditions.
        
        Args:
            text (str): Cleaned text
            
        Returns:
            Dict: Terms extraction result
        """
        terms_keywords = [
            'terms', 'conditions', 'applicable', 'valid', 'offer',
            'cannot be combined', 'not applicable', 'restrictions',
            'one time use', 'limited period', 'purchase', 'plan'
        ]

        sentences = [s.strip() for s in re.split(r'[.!?]', text) if s.strip()]
        collected_terms: List[str] = []

        for sentence in sentences:
            lower_sentence = sentence.lower()
            if any(keyword in lower_sentence for keyword in terms_keywords):
                collected_terms.append(sentence)

        amount_pattern = re.compile(r'₹\s*(\d+(?:,\d{3})*)')
        for sentence in sentences:
            lower_sentence = sentence.lower()
            amounts = amount_pattern.findall(sentence)
            if amounts and ('plan' in lower_sentence or 'subscription' in lower_sentence or 'purchase' in lower_sentence):
                descriptor_match = re.search(r'([A-Za-z&\s]{3,}?\bplan[s]?)', sentence, re.IGNORECASE)
                if descriptor_match:
                    descriptor = descriptor_match.group(1).strip()
                else:
                    descriptor = 'this offer'

                unique_amounts: List[str] = []
                for amount in amounts:
                    normalized_amount = amount.replace(',', '')
                    if normalized_amount not in unique_amounts:
                        unique_amounts.append(normalized_amount)

                formatted_amounts = ', '.join(f'₹{amt}' for amt in unique_amounts)
                formatted_sentence = f"Valid on {descriptor} of {formatted_amounts}"

                if formatted_sentence not in collected_terms:
                    collected_terms.append(formatted_sentence)

        if collected_terms:
            return {
                'text': '. '.join(collected_terms),
                'confidence': 0.7,
                'source': 'keyword_match'
            }

        return {'text': None, 'confidence': 0.0, 'source': 'none'}
    
    def _is_plausible_code(self, code: str) -> bool:
        """Determine if a detected token looks like a real coupon code."""

        cleaned = code.strip()
        if not cleaned:
            return False

        if any(char.isdigit() for char in cleaned):
            return True

        if cleaned.isalpha() and len(cleaned) >= 6:
            return True

        return False

    def _is_likely_false_positive(self, code: str) -> bool:
        """Check if a potential code is likely a false positive."""

        cleaned = code.strip()
        if not cleaned:
            return True

        upper_code = cleaned.upper()

        if cleaned.isdigit():
            return True

        generic_words = {
            'CODE', 'COUPON', 'OFFER', 'DEAL', 'SAVE', 'FREE', 'EXTRA',
            'FLAT', 'DISCOUNT', 'SHOP', 'STORE', 'SUPER', 'SPECIAL'
        }

        if upper_code in generic_words:
            return True

        store_names = {'AMAZON', 'FLIPKART', 'MYNTRA', 'SWIGGY', 'ZOMATO', 'AHA', 'PUMA'}
        if upper_code in store_names:
            return True

        cities = {'INDIA', 'DELHI', 'MUMBAI', 'BANGALORE', 'CHENNAI', 'HYDERABAD'}
        if upper_code in cities:
            return True

        weekdays = {
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'
        }
        if upper_code in weekdays:
            return True

        months = {
            'JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'MAY', 'JUNE',
            'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER',
            'JAN', 'FEB', 'MAR', 'APR', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'
        }
        if upper_code in months:
            return True

        if cleaned.isalpha() and len(cleaned) <= 4:
            return True

        return False
    
    def validate_extracted_fields(self, fields: Dict) -> Dict:
        """Validate and cross-check extracted fields.
        
        Args:
            fields (Dict): Extracted fields
            
        Returns:
            Dict: Validated fields with updated confidence scores
        """
        validated = fields.copy()
        
        # Cross-validation rules
        
        # 1. If expiry date is detected and parsed, boost overall confidence
        if (fields['expiry_date'].get('parsed_date') and 
            fields['expiry_date'].get('confidence', 0) > 0.7):
            validated['overall_confidence'] = min(1.0, validated['overall_confidence'] + 0.1)
        
        # 2. If store and coupon code are both detected with high confidence
        if (fields['store'].get('confidence', 0) > 0.7 and 
            fields['coupon_code'].get('confidence', 0) > 0.7):
            validated['overall_confidence'] = min(1.0, validated['overall_confidence'] + 0.15)
        
        # 3. If discount value seems unrealistic, reduce confidence
        discount = fields['discount']
        if discount.get('type') == 'percentage' and discount.get('value', 0) > 90:
            validated['discount']['confidence'] *= 0.7
        
        # 4. Add validation flags
        validated['validation_flags'] = {
            'has_essential_fields': (
                fields['coupon_code'].get('confidence', 0) > 0.5 or 
                fields['discount'].get('confidence', 0) > 0.5
            ),
            'has_store_info': fields['store'].get('confidence', 0) > 0.5,
            'has_expiry_info': fields['expiry_date'].get('confidence', 0) > 0.5,
            'overall_quality': 'high' if validated['overall_confidence'] > 0.8 else 
                             'medium' if validated['overall_confidence'] > 0.5 else 'low'
        }
        
        return validated
