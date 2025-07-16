#!/usr/bin/env python3
"""
Enhanced Field Extractor - ML-based coupon field extraction.

This module provides intelligent field extraction using machine learning
and context-aware algorithms for better coupon information extraction.
"""

import re
import json
import logging
import numpy as np
from datetime import datetime, timedelta
from typing import Dict, List, Tuple, Optional
import dateutil.parser as date_parser
from fuzzywuzzy import fuzz, process

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
                'identifiers': ['amazon', 'amzn', 'amazon.in'],
                'code_patterns': [r'[A-Z0-9]{8,12}'],
                'discount_patterns': [r'(\d+)%\s*off', r'save\s*₹(\d+)', r'flat\s*(\d+)%'],
                'common_terms': ['prime', 'delivery', 'shopping', 'cart']
            },
            'flipkart': {
                'identifiers': ['flipkart', 'fkrt', 'flipkart.com'],
                'code_patterns': [r'[A-Z]{2,4}\d{4,8}', r'FK[A-Z0-9]{6,10}'],
                'discount_patterns': [r'(\d+)%\s*off', r'extra\s*(\d+)%', r'flat\s*₹(\d+)'],
                'common_terms': ['plus', 'supercoins', 'delivery', 'exchange']
            },
            'myntra': {
                'identifiers': ['myntra', 'myntra.com'],
                'code_patterns': [r'[A-Z]{3,5}\d{3,6}', r'MYNTRA[0-9]{4,8}'],
                'discount_patterns': [r'(\d+)%\s*off', r'flat\s*(\d+)%', r'buy\s*\d+\s*get\s*\d+'],
                'common_terms': ['fashion', 'style', 'brands', 'clothing']
            },
            'swiggy': {
                'identifiers': ['swiggy', 'swiggy.com'],
                'code_patterns': [r'[A-Z]{4,6}\d{2,4}', r'SWIGGY[0-9]{3,6}'],
                'discount_patterns': [r'(\d+)%\s*off', r'flat\s*₹(\d+)', r'free\s*delivery'],
                'common_terms': ['food', 'delivery', 'restaurant', 'order']
            },
            'zomato': {
                'identifiers': ['zomato', 'zomato.com'],
                'code_patterns': [r'[A-Z]{4,6}\d{2,4}', r'ZOMATO[0-9]{3,6}'],
                'discount_patterns': [r'(\d+)%\s*off', r'flat\s*₹(\d+)', r'free\s*delivery'],
                'common_terms': ['food', 'delivery', 'dining', 'restaurant']
            }
        }
        
        # Common coupon code patterns
        self.general_code_patterns = [
            r'\b[A-Z]{3,6}\d{2,6}\b',  # 3-6 letters + 2-6 digits
            r'\b[A-Z0-9]{6,12}\b',     # 6-12 alphanumeric
            r'\b[A-Z]{2,4}[0-9]{4,8}\b',  # Letters + numbers
            r'\bCODE\s*[A-Z0-9]{4,10}\b',  # CODE prefix
            r'\bUSE\s*[A-Z0-9]{4,10}\b',   # USE prefix
        ]
        
        # Discount patterns with confidence scoring
        self.discount_patterns = [
            (r'(\d+)%\s*(?:off|discount)', 0.9, 'percentage'),
            (r'flat\s*(\d+)%\s*(?:off|discount)', 0.9, 'percentage'),
            (r'extra\s*(\d+)%\s*(?:off|discount)', 0.85, 'percentage'),
            (r'upto\s*(\d+)%\s*(?:off|discount)', 0.8, 'percentage'),
            (r'save\s*₹(\d+)', 0.9, 'amount'),
            (r'flat\s*₹(\d+)\s*(?:off|discount)', 0.9, 'amount'),
            (r'₹(\d+)\s*(?:off|discount)', 0.8, 'amount'),
            (r'rs\.?\s*(\d+)\s*(?:off|discount)', 0.8, 'amount'),
            (r'get\s*₹(\d+)\s*(?:back|cashback)', 0.85, 'cashback'),
            (r'(\d+)\s*rs\.?\s*(?:off|discount)', 0.75, 'amount'),
        ]
        
        # Expiry date patterns
        self.expiry_patterns = [
            (r'valid\s*(?:till|until)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'expires?\s*(?:on|at)?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'expiry\s*:?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})', 0.9),
            (r'valid\s*(?:till|until)\s*(\d{1,2}\s+\w+\s+\d{2,4})', 0.95),
            (r'expires?\s*(?:on|at)?\s*(\d{1,2}\s+\w+\s+\d{2,4})', 0.95),
            (r'(\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w*\s+\d{2,4})', 0.85),
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
        text_lower = text.lower()
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
        overall_confidence = np.mean(confidences) if confidences else 0.0
        
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
        best_match = {'name': None, 'confidence': 0.0, 'type': 'unknown'}
        
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
                ratio = fuzz.partial_ratio(identifier, text_lower)
                if ratio > 80:
                    confidence = max(confidence, ratio / 100.0 * 0.8)
            
            if confidence > best_match['confidence']:
                best_match = {
                    'name': store_name,
                    'confidence': confidence,
                    'type': 'identified'
                }
        
        # If no store detected, try to extract any brand-like words
        if best_match['confidence'] < 0.5:
            brand_patterns = [
                r'\b[A-Z][a-z]+\s*(?:\.com|\.in)?\b',  # Domain-like
                r'\b[A-Z]{2,8}\b'  # All caps words
            ]
            
            for pattern in brand_patterns:
                matches = re.findall(pattern, text)
                if matches:
                    best_match = {
                        'name': matches[0].strip(),
                        'confidence': 0.4,
                        'type': 'extracted'
                    }
                    break
        
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
        if store_info.get('name') in self.store_patterns:
            store_patterns = self.store_patterns[store_info['name']]['code_patterns']
            for pattern in store_patterns:
                matches = re.findall(pattern, text, re.IGNORECASE)
                for match in matches:
                    candidates.append({
                        'code': match,
                        'confidence': 0.9,
                        'source': 'store_specific'
                    })
        
        # Try general patterns
        for pattern in self.general_code_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                # Filter out common false positives
                if not self._is_likely_false_positive(match):
                    candidates.append({
                        'code': match,
                        'confidence': 0.7,
                        'source': 'general_pattern'
                    })
        
        # Look for explicit code indicators
        code_indicator_patterns = [
            r'(?:code|coupon)\s*:?\s*([A-Z0-9]{4,12})',
            r'use\s+(?:code\s+)?([A-Z0-9]{4,12})',
            r'promo\s*code\s*:?\s*([A-Z0-9]{4,12})'
        ]
        
        for pattern in code_indicator_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
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
        best_match = {'value': None, 'type': None, 'confidence': 0.0, 'raw_text': None}
        
        for pattern, confidence, discount_type in self.discount_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                try:
                    # Extract numeric value
                    if isinstance(match, tuple):
                        value = int(match[0])
                        raw_text = match[1] if len(match) > 1 else str(match)
                    else:
                        value = int(match)
                        raw_text = match
                    
                    # Validate reasonable discount values
                    if discount_type == 'percentage' and 1 <= value <= 99:
                        if confidence > best_match['confidence']:
                            best_match = {
                                'value': value,
                                'type': discount_type,
                                'confidence': confidence,
                                'raw_text': raw_text
                            }
                    elif discount_type in ['amount', 'cashback'] and 10 <= value <= 10000:
                        if confidence > best_match['confidence']:
                            best_match = {
                                'value': value,
                                'type': discount_type,
                                'confidence': confidence,
                                'raw_text': raw_text
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
        best_match = {'date': None, 'confidence': 0.0, 'raw_text': None, 'parsed_date': None}
        
        for pattern, confidence in self.expiry_patterns:
            matches = re.findall(pattern, text, re.IGNORECASE)
            for match in matches:
                try:
                    # Try to parse the date
                    parsed_date = date_parser.parse(match, fuzzy=True)
                    
                    # Check if date is in the future (reasonable for expiry)
                    if parsed_date.date() >= datetime.now().date():
                        if confidence > best_match['confidence']:
                            best_match = {
                                'date': match,
                                'confidence': confidence,
                                'raw_text': match,
                                'parsed_date': parsed_date.isoformat()
                            }
                    
                except (ValueError, TypeError):
                    # If parsing fails, still record with lower confidence
                    if confidence * 0.5 > best_match['confidence']:
                        best_match = {
                            'date': match,
                            'confidence': confidence * 0.5,
                            'raw_text': match,
                            'parsed_date': None
                        }
        
        return best_match
    
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
            'one time use', 'limited period'
        ]
        
        terms_text = []
        for keyword in terms_keywords:
            if keyword in text.lower():
                # Find sentences containing terms keywords
                sentences = re.split(r'[.!?]', text)
                for sentence in sentences:
                    if keyword in sentence.lower():
                        terms_text.append(sentence.strip())
        
        if terms_text:
            return {
                'text': '. '.join(terms_text),
                'confidence': 0.7,
                'source': 'keyword_match'
            }
        
        return {'text': None, 'confidence': 0.0, 'source': 'none'}
    
    def _is_likely_false_positive(self, code: str) -> bool:
        """Check if a potential code is likely a false positive.
        
        Args:
            code (str): Potential coupon code
            
        Returns:
            bool: True if likely false positive
        """
        false_positive_patterns = [
            r'^\d+$',  # Only numbers
            r'^[A-Z]+$',  # Only letters
            r'(AMAZON|FLIPKART|MYNTRA|SWIGGY|ZOMATO)',  # Store names
            r'(INDIA|DELHI|MUMBAI|BANGALORE)',  # City names
            r'(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)',  # Days
            r'(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)',  # Months
        ]
        
        for pattern in false_positive_patterns:
            if re.match(pattern, code, re.IGNORECASE):
                return True
        
        # Check for common English words
        common_words = ['CODE', 'COUPON', 'OFFER', 'DEAL', 'SAVE', 'FREE', 'EXTRA']
        if code.upper() in common_words:
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