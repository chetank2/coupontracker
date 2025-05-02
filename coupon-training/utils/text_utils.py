#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import re
import string
import pytesseract
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple, Union

# Enhanced regex patterns for different coupon elements
# Coupon codes are typically 5-12 uppercase alphanumeric characters
RE_COUPON_CODE = re.compile(r'\b[A-Z0-9]{5,12}\b')

# Date patterns in various formats
RE_DATE = re.compile(r'\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})|(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})\b', re.IGNORECASE)

# Amount/discount patterns
RE_AMOUNT = re.compile(r'(₹\s*\d+|\d+\s*%|\$\s*\d+|RS\.?\s*\d+|INR\s*\d+|OFF|CASHBACK|DISCOUNT)', re.IGNORECASE)

# Store name patterns - often at the beginning of text or all caps
RE_STORE = re.compile(r'^([A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,3})|([A-Z]{2,}(?:\s+[A-Z]+){0,3})')

# Description patterns - often contains these keywords
RE_DESCRIPTION = re.compile(r'(offer|deal|save|get|use|valid|applicable|min|purchase|order|worth)', re.IGNORECASE)

# Expiry patterns - often contains these keywords with dates
RE_EXPIRY = re.compile(r'(valid\s+(?:till|until|before)|expires?(?:\s+on)?|ends?(?:\s+on)?|till|until|before)', re.IGNORECASE)

def extract_store_name(text):
    """Extract store name from text"""
    # Check for known store names
    known_stores = ["Myntra", "ABHIBUS", "NEWMEE", "IXIGO", "BOAT", "XYXX", "Mivi", "CRED"]
    for store in known_stores:
        if re.search(r'\b' + re.escape(store) + r'\b', text, re.IGNORECASE):
            return store

    # Look for "Brand:" pattern
    brand_match = re.search(r'(?i)Brand:\s*([A-Za-z0-9]+)', text)
    if brand_match:
        return brand_match.group(1)

    # If no specific pattern is found, return the first line if it's reasonably short
    lines = text.split('\n')
    if lines:
        first_line = lines[0].strip()
        if 2 <= len(first_line) <= 25 and not re.search(r'(?i)code', first_line):
            return first_line

    return None

def extract_coupon_code(text):
    """Extract coupon code from text"""
    # Look for "Code:" pattern
    code_patterns = [
        r'(?i)Code[:\s]+([A-Z0-9\-_]+)',
        r'(?i)Coupon[:\s]+([A-Z0-9\-_]+)',
        r'(?i)Use[:\s]+([A-Z0-9\-_]+)',
        r'(?i)Apply[:\s]+([A-Z0-9\-_]+)'
    ]

    for pattern in code_patterns:
        match = re.search(pattern, text)
        if match:
            return match.group(1).upper()

    # Look for standalone coupon code pattern
    standalone_pattern = r'\b([A-Z0-9]{5,20})\b'
    for match in re.finditer(standalone_pattern, text):
        potential_code = match.group(1)
        # Validate code format - must have both letters and numbers
        if (len(potential_code) >= 5 and
            re.search(r'[A-Za-z]', potential_code) and
            re.search(r'[0-9]', potential_code)):
            return potential_code.upper()

    return None

def extract_expiry_date(text):
    """Extract expiry date from text"""
    # Look for common expiry date patterns
    expiry_patterns = [
        r'(?i)Expires?\s+(?:on|in|by)?[\s:;]*([\d]{1,2}[/-][\d]{1,2}[/-][\d]{2,4})',
        r'(?i)Valid\s+(?:till|until|through)[\s:;]*([\d]{1,2}[/-][\d]{1,2}[/-][\d]{2,4})',
        r'(?i)Expiry[\s:;]*([\d]{1,2}[/-][\d]{1,2}[/-][\d]{2,4})',
        r'(?i)Expires?\s+(?:on|in|by)?[\s:;]*([\d]{1,2}\s+[A-Za-z]+\s+[\d]{2,4})',
        r'(?i)Valid\s+(?:till|until|through)[\s:;]*([\d]{1,2}\s+[A-Za-z]+\s+[\d]{2,4})',
        r'(?i)Expires?\s+in\s+([\d]+)\s+days?'
    ]

    for pattern in expiry_patterns:
        match = re.search(pattern, text)
        if match:
            date_str = match.group(1)

            # Handle "Expires in X days" format
            if re.match(r'^\d+$', date_str):
                days = int(date_str)
                future_date = datetime.now() + timedelta(days=days)
                return future_date.strftime('%d/%m/%Y')

            return date_str

    return None

def extract_description(text):
    """Extract description from text"""
    # Look for description patterns
    description_patterns = [
        r'(?i)Get\s+(.+?)(?=\n|$)',
        r'(?i)Offer:\s*(.+?)(?=\n|$)',
        r'(?i)Description:\s*(.+?)(?=\n|$)',
        r'(?i)(\d+%\s+(?:off|discount|cashback).+?)(?=\n|$)',
        r'(?i)(₹\d+\s+(?:off|discount|cashback).+?)(?=\n|$)'
    ]

    for pattern in description_patterns:
        match = re.search(pattern, text)
        if match:
            return match.group(1).strip()

    # If no specific pattern is found, try to find a sentence with discount information
    discount_pattern = r'(?i).*(discount|off|save|cashback|deal).*'
    for line in text.split('\n'):
        if re.match(discount_pattern, line) and len(line) > 10:
            return line.strip()

    return None

def extract_amount(text):
    """Extract amount/discount from text"""
    # Look for percentage discount patterns
    percentage_patterns = [
        r'(?i)(\d+(?:\.\d+)?)\s*%\s*(?:off|cashback|discount)',
        r'(?i)(?:up to|upto|flat)\s*(\d+(?:\.\d+)?)\s*%'
    ]

    for pattern in percentage_patterns:
        match = re.search(pattern, text)
        if match:
            percentage = match.group(1)
            return f"{percentage}%"

    # Look for amount patterns
    amount_patterns = [
        r'(?i)(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)\s*(?:off|cashback|discount)',
        r'(?i)(?:up to|upto|flat)\s*(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)'
    ]

    for pattern in amount_patterns:
        match = re.search(pattern, text)
        if match:
            amount = match.group(1)
            return f"₹{amount}"

    return None

def extract_all_elements(text):
    """Extract all coupon elements from text"""
    elements = {}

    store_name = extract_store_name(text)
    if store_name:
        elements['store_name'] = store_name

    coupon_code = extract_coupon_code(text)
    if coupon_code:
        elements['coupon_code'] = coupon_code

    expiry_date = extract_expiry_date(text)
    if expiry_date:
        elements['expiry_date'] = expiry_date

    description = extract_description(text)
    if description:
        elements['description'] = description

    amount = extract_amount(text)
    if amount:
        elements['amount'] = amount

    return elements

def extract_text_from_image(image, lang='eng'):
    """Extract text from an image using Tesseract OCR"""
    text = pytesseract.image_to_string(image, lang=lang)
    return text.strip()

def extract_text_from_region(image, region, lang='eng', element_type=None):
    """Extract text from a specific region of the image

    Args:
        image: Input image
        region: Region coordinates (x1, y1, x2, y2)
        lang: Language for OCR
        element_type: Type of element (code, amount, expiry, store, description)

    Returns:
        str: Extracted text
    """
    if region is None:
        return None

    x1, y1, x2, y2 = region
    roi = image[y1:y2, x1:x2]

    # Use appropriate Tesseract config based on element type
    config = '--psm 6'

    if element_type == 'code':
        # For codes, use PSM 7 (single line) and whitelist alphanumeric chars
        config = '--psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    elif element_type == 'amount':
        # For amounts, use PSM 6 (block) and include currency symbols
        config = '--psm 6 -c tessedit_char_whitelist=0123456789%.₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
    elif element_type == 'expiry':
        # For expiry dates, use PSM 6 (block)
        config = '--psm 6'

    # Use Tesseract to extract text
    text = pytesseract.image_to_string(roi, lang=lang, config=config)
    text = text.strip()

    # Apply post-processing if element type is specified
    if element_type and text:
        text = post_process_ocr_text(text, element_type)

    return text


def clean_text(text):
    """Clean text by removing extra whitespace and normalizing characters

    Args:
        text (str): Input text to clean

    Returns:
        str: Cleaned text
    """
    # Replace multiple spaces with a single space
    text = re.sub(r'\s+', ' ', text)

    # Remove leading/trailing whitespace
    text = text.strip()

    # Replace common OCR errors
    text = text.replace('|', 'I')
    text = text.replace('0', 'O')
    text = text.replace('1', 'I')

    return text


def post_process_ocr_text(text, element_type):
    """Post-process OCR text based on the element type

    Args:
        text (str): OCR extracted text
        element_type (str): Type of element (code, amount, expiry, store, description)

    Returns:
        str: Post-processed text
    """
    # Clean the text first
    cleaned_text = clean_text(text)

    if not cleaned_text:
        return ""

    # Apply specific processing based on element type
    if element_type == 'code':
        # For codes, extract alphanumeric sequences and prefer uppercase
        code_match = RE_COUPON_CODE.search(cleaned_text.upper())
        if code_match:
            return code_match.group(0)
        # If no match, just return uppercase alphanumeric chars
        return ''.join(c for c in cleaned_text.upper() if c.isalnum())

    elif element_type == 'amount':
        # For amounts, extract discount/price information
        amount_match = RE_AMOUNT.search(cleaned_text)
        if amount_match:
            return amount_match.group(0)
        return cleaned_text

    elif element_type == 'expiry':
        # For expiry, extract date information
        date_match = RE_DATE.search(cleaned_text)
        if date_match:
            return date_match.group(0)
        return cleaned_text

    elif element_type == 'store':
        # For store names, prefer capitalized words
        store_match = RE_STORE.search(cleaned_text)
        if store_match:
            return store_match.group(0)
        # If no match, capitalize first letter of each word
        return string.capwords(cleaned_text)

    elif element_type == 'description':
        # For descriptions, just return the cleaned text
        return cleaned_text

    # Default case
    return cleaned_text
